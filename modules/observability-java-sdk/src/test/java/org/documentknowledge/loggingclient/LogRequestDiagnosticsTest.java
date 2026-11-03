package org.documentknowledge.loggingclient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.documentknowledge.observability.EventCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class LogRequestDiagnosticsTest {
    private static final Set<String> ERRORS=Set.of("INVALID_INPUT","UNAUTHENTICATED","FORBIDDEN","NOT_FOUND","CONFLICT",
        "CAPACITY","DEADLINE_EXCEEDED","CANCELLED","OPERATION_FAILED");
    private static final LogDiagnosticCatalog CATALOG=new LogDiagnosticCatalog(ERRORS,Map.of(),
        Set.of(IllegalStateException.class.getName(),CancellationException.class.getName()),Set.of());
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final class Harness implements AutoCloseable {
        final List<Map<?,?>> rows=new CopyOnWriteArrayList<>();
        final CountDownLatch entered=new CountDownLatch(1),release;
        final LogProducer producer;
        final LogRequestDiagnostics requests;
        Harness(LogDiagnosticCatalog catalog,boolean held) {
            release=new CountDownLatch(held?1:0);
            producer=new LogProducer(LogWireTest.EXECUTION,new LogVocabulary(EnumSet.allOf(EventCode.class),Set.of("REQUEST"),catalog),
                (route,bytes)->{
                    entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
                    var body=JSON.readValue(bytes,Map.class);var prepared=new ArrayList<LogWire.Prepared>();
                    if(route==LogTransport.Route.BATCHES)for(Object item:(List<?>)body.get("events")) {
                        var row=(Map<?,?>)item;rows.add(row);prepared.add(new LogWire.Prepared(UUID.fromString((String)row.get("eventId")),new byte[0]));
                    } else for(Object id:(List<?>)body.get("eventIds"))prepared.add(new LogWire.Prepared(UUID.fromString((String)id),new byte[0]));
                    return new LogTransport.Response(200,LogWireTest.receipt(prepared,"COMMITTED"));
                });
            requests=new LogRequestDiagnostics(LogWireTest.EXECUTION,producer,catalog);
        }
        void await(int expected) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(producer.status().committed()<expected && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(expected,producer.status().committed());
        }
        public void close() {release.countDown();producer.close();}
    }
    private static Map<?,?> context(Map<?,?> row) {return (Map<?,?>)row.get("context");}
    @Test void successfulRequestRetainsOneContextAndCompletesOnlyOnce() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            String correlation=UUID.randomUUID().toString();String trace="a".repeat(32),span="b".repeat(16);
            var token=h.requests.started(correlation,"00-"+trace+"-"+span+"-01");
            Thread.sleep(5);
            assertEquals(LogProducer.Admission.ACCEPTED,h.requests.completed(token,204,null));
            assertEquals(LogProducer.Admission.DUPLICATE,h.requests.completed(token,500,new IllegalStateException("private-canary")));
            h.await(2);assertEquals(2,h.rows.size());
            assertEquals(List.of("operation.started","operation.succeeded"),h.rows.stream().map(row->row.get("eventName")).toList());
            for(var row:h.rows) {
                assertEquals(correlation,row.get("correlationId"));assertEquals(trace,row.get("traceId"));assertEquals(span,row.get("spanId"));
                assertEquals("REQUEST",context(row).get("phase"));assertEquals(LogWireTest.EXECUTION.build().buildId().toString(),row.get("buildId"));
            }
            var result=context(h.rows.getLast());assertEquals(204,result.get("status"));
            assertTrue(((Number)result.get("durationMs")).longValue()>=5);assertFalse(result.containsKey("errorCode"));assertFalse(result.containsKey("exceptionClass"));
            assertFalse(JSON.writeValueAsString(h.rows).contains("private-canary"));
        }
    }
    @Test void rejectedResponsesMapActualStatusWithoutInventingAnException() throws Exception {
        var expected=Map.ofEntries(Map.entry(400,"INVALID_INPUT"),Map.entry(422,"INVALID_INPUT"),Map.entry(401,"UNAUTHENTICATED"),
            Map.entry(403,"FORBIDDEN"),Map.entry(404,"NOT_FOUND"),Map.entry(409,"CONFLICT"),Map.entry(429,"CAPACITY"),
            Map.entry(408,"DEADLINE_EXCEEDED"),Map.entry(504,"DEADLINE_EXCEEDED"),Map.entry(503,"OPERATION_FAILED"));
        try(var h=new Harness(CATALOG,false)) {
            for(var entry:expected.entrySet())h.requests.completed(h.requests.started(null,null),entry.getKey(),null);
            h.await(expected.size()*2);
            var failed=h.rows.stream().filter(row->row.get("eventName").equals("operation.failed")).toList();assertEquals(expected.size(),failed.size());
            for(var row:failed) {
                var context=context(row);int status=((Number)context.get("status")).intValue();
                assertEquals(expected.get(status),context.get("errorCode"));assertFalse(context.containsKey("exceptionClass"));
            }
        }
    }
    @Test void untrustedAndAbsentContextNeverFailsOrLeaksAndGetsFreshCorrelation() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            String[] correlations={null,"private-canary","00000000-0000-0000-0000-000000000000","1-1-1-1-1"};
            String[] traces={null,"private-canary","00-"+"0".repeat(32)+"-"+"b".repeat(16)+"-00","00-"+"a".repeat(32)+"-"+"0".repeat(16)+"-01"};
            for(int index=0;index<correlations.length;index++)h.requests.completed(h.requests.started(correlations[index],traces[index]),200,null);
            h.await(8);var starts=h.rows.stream().filter(row->row.get("eventName").equals("operation.started")).toList();
            assertEquals(4,starts.stream().map(row->row.get("correlationId")).distinct().count());
            for(var row:h.rows) {assertFalse(row.containsKey("traceId"));assertFalse(row.containsKey("spanId"));}
            assertFalse(JSON.writeValueAsString(h.rows).contains("private-canary"));
        }
    }
    @Test void realFailureAndCancellationUseOnlyCataloguedTypesAndRedactedCounts() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            Throwable failure;
            try {throw new IllegalStateException("private-operation-canary");}catch(IllegalStateException actual) {failure=actual;}
            h.requests.completed(h.requests.started(null,null),500,failure);
            h.requests.completed(h.requests.started(null,null),503,new CancellationException("private-cancel-canary"));
            h.await(4);
            var failed=h.rows.stream().filter(row->row.get("eventName").equals("operation.failed")).findFirst().orElseThrow();
            assertEquals(IllegalStateException.class.getName(),context(failed).get("exceptionClass"));assertEquals(1,context(failed).get("redactedFields"));
            var cancelled=h.rows.stream().filter(row->row.get("eventName").equals("operation.cancelled")).findFirst().orElseThrow();
            assertEquals("CANCELLED",context(cancelled).get("errorCode"));assertEquals(true,((Map<?,?>)context(cancelled).get("attributes")).get("cancelRequested"));
            assertFalse(JSON.writeValueAsString(h.rows).contains("canary"));
        }
    }
    @Test void heldDeliveryCannotGateCompletionAndForeignTokensOrInvalidStatusDoNotConsumeTheToken() throws Exception {
        try(var h=new Harness(LogDiagnosticCatalog.EMPTY,true);var other=new Harness(CATALOG,false)) {
            var token=h.requests.started(null,null);assertTrue(h.entered.await(2,TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,()->other.requests.completed(token,200,null));
            for(int status:new int[]{99,600})assertThrows(IllegalArgumentException.class,()->h.requests.completed(token,status,null));
            assertThrows(NullPointerException.class,()->h.requests.completed(null,200,null));
            assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertEquals(LogProducer.Admission.ACCEPTED,h.requests.completed(token,403,null)));
            assertEquals(0,h.producer.status().committed());assertEquals(2,h.producer.status().pendingEvents());
            h.release.countDown();h.await(2);assertFalse(context(h.rows.getLast()).containsKey("errorCode"));
        }
    }
}
