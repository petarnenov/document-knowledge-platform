package org.documentknowledge.loggingclient;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.documentknowledge.observability.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class LogOperationDiagnosticsTest {
    static final Set<String> PHASES=Set.of("OPERATION","DATABASE","PROVIDER","RETRY","DEADLINE","CANCELLATION","CLEANUP",
        "PROVIDER_STORAGE_PUT","PROVIDER_STORAGE_OPEN","PROVIDER_STORAGE_READ","PROVIDER_STORAGE_CLOSE",
        "PROVIDER_STORAGE_COPY","PROVIDER_STORAGE_VERSIONS","PROVIDER_STORAGE_DELETE","PROVIDER_SCAN","PROVIDER_INSPECT");
    static final String SYMBOL=LogOperationDiagnosticsTest.class.getName()+".actualSqlFailure";
    static final String SOURCE="modules/observability-java-sdk/src/test/java/org/documentknowledge/loggingclient/LogOperationDiagnosticsTest.java";
    static final LogDiagnosticCatalog CATALOG=new LogDiagnosticCatalog(Set.of("DB_UNAVAILABLE","OPERATION_FAILED","CANCELLED"),Map.of(),
        Set.of(SQLException.class.getName(),IllegalStateException.class.getName(),CancellationException.class.getName()),
        Set.of(new LogDiagnosticCatalog.Frame(SYMBOL,SOURCE)));
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final class Harness implements AutoCloseable {
        final List<Map<?,?>> rows=new CopyOnWriteArrayList<>();
        final CountDownLatch entered=new CountDownLatch(1),release;
        final LogProducer producer;
        final LogOperationDiagnostics operations;
        Harness(LogDiagnosticCatalog catalog,boolean held) {
            release=new CountDownLatch(held?1:0);
            producer=new LogProducer(LogWireTest.EXECUTION,new LogVocabulary(EnumSet.allOf(EventCode.class),PHASES,catalog),
                (route,bytes)->{
                    entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
                    var body=JSON.readValue(bytes,Map.class);var prepared=new ArrayList<LogWire.Prepared>();
                    if(route==LogTransport.Route.BATCHES)for(Object item:(List<?>)body.get("events")) {
                        var row=(Map<?,?>)item;rows.add(row);prepared.add(new LogWire.Prepared(UUID.fromString((String)row.get("eventId")),new byte[0]));
                    } else for(Object id:(List<?>)body.get("eventIds"))prepared.add(new LogWire.Prepared(UUID.fromString((String)id),new byte[0]));
                    return new LogTransport.Response(200,LogWireTest.receipt(prepared,"COMMITTED"));
                });
            operations=new LogOperationDiagnostics(LogWireTest.EXECUTION,producer,catalog);
        }
        void await(int expected) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(producer.status().committed()<expected && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(expected,producer.status().committed());
        }
        public void close() {release.countDown();producer.close();}
    }
    static Map<?,?> context(Map<?,?> row) {return (Map<?,?>)row.get("context");}
    static SQLException actualSqlFailure(String state) {
        try {throw new SQLException("SELECT private-document-token-canary",state,new IllegalStateException("private-cause-canary"));}
        catch(SQLException actual) {return actual;}
    }
    @Test void preservesOriginalSqlCauseBeforePublicMappingAndCompletesOnlyOnce() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            var correlation=UUID.randomUUID();var token=h.operations.started(correlation,new TraceContext("a".repeat(32),"b".repeat(16)),"DATABASE");
            assertTrue(h.operations.recordFailure(token,actualSqlFailure("42501")));
            assertFalse(h.operations.recordFailure(token,new SQLException("later-private-canary","42P01")));
            assertEquals(LogProducer.Admission.ACCEPTED,h.operations.completed(token,new IllegalStateException("mapped-private-canary")));
            assertEquals(LogProducer.Admission.DUPLICATE,h.operations.completed(token,null));
            assertFalse(h.operations.recordFailure(token,new SQLException("late","08006")));
            h.await(2);assertEquals(List.of("operation.started","operation.failed"),h.rows.stream().map(row->row.get("eventName")).toList());
            for(var row:h.rows) {
                assertEquals(correlation.toString(),row.get("correlationId"));assertEquals("a".repeat(32),row.get("traceId"));
                assertEquals("b".repeat(16),row.get("spanId"));assertEquals(LogWireTest.EXECUTION.runId().toString(),row.get("runId"));
            }
            var result=context(h.rows.getLast());assertEquals("42501",result.get("sqlState"));assertEquals("DB_UNAVAILABLE",result.get("errorCode"));
            assertEquals(SQLException.class.getName(),result.get("exceptionClass"));assertEquals(List.of(IllegalStateException.class.getName()),result.get("causeClasses"));
            var frame=(Map<?,?>)((List<?>)result.get("stackFrames")).getFirst();assertEquals(SYMBOL,frame.get("symbol"));
            assertEquals(SOURCE,frame.get("source"));assertTrue(((Number)frame.get("line")).longValue()>0);
            assertFalse(result.containsKey("status"));assertFalse(result.containsKey("remainingDeadlineMs"));assertFalse((Boolean)((Map<?,?>)result.get("attributes")).get("cleanupSettled"));
            assertFalse(JSON.writeValueAsString(h.rows).contains("canary"));
        }
    }
    @Test void actualSuccessAcrossRegisteredPhasesAndRecoveredFailureNeverInventsFailedOutcome() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            for(String phase:PHASES) {
                var token=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),phase);
                if(phase.equals("DATABASE"))h.operations.recordFailure(token,actualSqlFailure("42P01"));
                h.operations.completed(token,null);
            }
            h.await(PHASES.size()*2);
            for(var row:h.rows) {
                assertTrue(PHASES.contains(context(row).get("phase")));assertFalse(row.containsKey("traceId"));
                assertFalse(context(row).containsKey("exceptionClass"));assertFalse(context(row).containsKey("sqlState"));
                assertFalse(context(row).containsKey("errorCode"));assertTrue(((Number)context(row).get("durationMs")).longValue()>=0);
                assertTrue(Set.of("operation.started","operation.succeeded").contains(row.get("eventName")));
            }
        }
    }
    @Test void invalidOrAbsentSqlStateStaysOmittedWithHonestRedactionAndCatalogOmissions() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            for(String state:new String[]{null,"private-state-canary"}) {
                var token=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"DATABASE");
                h.operations.completed(token,actualSqlFailure(state));
            }
            h.await(4);var failed=h.rows.stream().filter(row->row.get("eventName").equals("operation.failed")).toList();
            assertEquals(2,failed.size());for(var row:failed){assertFalse(context(row).containsKey("sqlState"));assertEquals("DB_UNAVAILABLE",context(row).get("errorCode"));}
            assertTrue(((Number)context(failed.getLast()).get("redactedFields")).longValue()>=3);
            assertFalse(JSON.writeValueAsString(h.rows).contains("canary"));
        }
        try(var h=new Harness(new LogDiagnosticCatalog(Set.of(),Map.of(),Set.of(),Set.of()),false)) {
            h.operations.completed(h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"DATABASE"),actualSqlFailure("08006"));
            h.await(2);var result=context(h.rows.getLast());assertFalse(result.containsKey("errorCode"));assertFalse(result.containsKey("exceptionClass"));
            assertEquals("08006",result.get("sqlState"));assertTrue(((Number)result.get("unregisteredSymbols")).longValue()>0);
        }
    }
    @Test void cancellationAndOrdinaryFailureUseOnlyActualObservedOutcomes() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            h.operations.completed(h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"OPERATION"),new CancellationException("private-canary"));
            h.operations.completed(h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"OPERATION"),new IllegalStateException("private-canary"));
            h.await(4);
            var cancelled=h.rows.stream().filter(row->row.get("eventName").equals("operation.cancelled")).findFirst().orElseThrow();
            assertEquals("CANCELLED",context(cancelled).get("errorCode"));assertEquals(true,((Map<?,?>)context(cancelled).get("attributes")).get("cancelRequested"));
            var failed=h.rows.stream().filter(row->row.get("eventName").equals("operation.failed")).findFirst().orElseThrow();
            assertEquals("OPERATION_FAILED",context(failed).get("errorCode"));assertFalse(context(failed).containsKey("sqlState"));
        }
    }
    @Test void heldDeliveryDoesNotGateCompletionAndRejectsCrossOwnerOrInvalidContexts() throws Exception {
        try(var h=new Harness(CATALOG,true);var other=new Harness(CATALOG,false)) {
            var token=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"DATABASE");assertTrue(h.entered.await(2,TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,()->other.operations.completed(token,null));
            assertThrows(IllegalArgumentException.class,()->h.operations.completed(null,null));
            assertThrows(IllegalArgumentException.class,()->other.operations.recordFailure(token,actualSqlFailure("08006")));
            assertThrows(NullPointerException.class,()->h.operations.recordFailure(token,null));
            assertThrows(IllegalArgumentException.class,()->h.operations.started(new UUID(0,0),new TraceContext(null,null),"DATABASE"));
            assertThrows(IllegalArgumentException.class,()->h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"unregistered"));
            assertThrows(NullPointerException.class,()->h.operations.started(null,new TraceContext(null,null),"DATABASE"));
            assertThrows(NullPointerException.class,()->h.operations.started(UUID.randomUUID(),null,"DATABASE"));
            assertTimeoutPreemptively(Duration.ofSeconds(2),()->{
                assertTrue(h.operations.recordFailure(token,actualSqlFailure("08006")));
                assertEquals(LogProducer.Admission.ACCEPTED,h.operations.completed(token,new IllegalStateException()));
            });
            assertEquals(0,h.producer.status().committed());assertEquals(2,h.producer.status().pendingEvents());
            assertEquals(LogProducer.Admission.REJECTED,h.operations.reportRejected());assertEquals(1,h.producer.status().rejected());
            h.release.countDown();h.await(2);
        }
    }
    @Test void providerFactsPreserveActualStatusDeadlineDependencyAndOriginalCause() throws Exception {
        var dependency=new LogDiagnosticCatalog.Dependency("aws.s3","2.0",null,LogDiagnosticCatalog.Provenance.UNKNOWN);
        var catalog=new LogDiagnosticCatalog(Set.of("PROVIDER_UNAVAILABLE"),Map.of("aws.s3",dependency),
            Set.of(java.net.ConnectException.class.getName()),Set.of());
        try(var h=new Harness(catalog,false)) {
            assertEquals(dependency,h.operations.dependency("aws.s3"));assertNull(h.operations.dependency("rustfs"));
            var start=new LogOperationDiagnostics.Facts(null,60000L,dependency,1,1,false,null,null);
            var token=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"PROVIDER_STORAGE_PUT",start);
            h.operations.recordFailure(token,new java.net.ConnectException("private-provider-message-canary"));
            var terminal=new LogOperationDiagnostics.Facts(503,59000L,dependency,1,1,false,false,"PROVIDER_UNAVAILABLE");
            h.operations.completed(token,new IllegalStateException("mapped-canary"),LifecycleEvent.Outcome.FAILED,terminal);
            h.await(2);var first=context(h.rows.getFirst());var last=context(h.rows.getLast());
            assertEquals(60000,((Number)first.get("remainingDeadlineMs")).intValue());assertFalse(first.containsKey("status"));
            assertEquals(503,last.get("status"));assertEquals(59000,((Number)last.get("remainingDeadlineMs")).intValue());
            assertEquals("aws.s3",last.get("dependency"));assertEquals("2.0",last.get("dependencyVersion"));
            assertEquals("UNKNOWN",((Map<?,?>)last.get("attributes")).get("vendorBuildProvenance"));assertFalse(last.containsKey("dependencyImageDigest"));
            assertEquals(java.net.ConnectException.class.getName(),last.get("exceptionClass"));
            assertEquals("PROVIDER_UNAVAILABLE",last.get("errorCode"));assertEquals(1,((Number)last.get("attempt")).intValue());
            assertFalse(JSON.writeValueAsString(h.rows).contains("canary"));
        }
    }
    @Test void incompleteReadAndActualPolicyDecisionsHaveNoInventedFailureOrCancellation() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            var read=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"PROVIDER_STORAGE_READ");
            h.operations.completed(read,null,LifecycleEvent.Outcome.UNKNOWN,LogOperationDiagnostics.Facts.EMPTY);
            var retry=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"RETRY");
            h.operations.completed(retry,null,LifecycleEvent.Outcome.REJECTED,new LogOperationDiagnostics.Facts(null,null,null,3,3,false,null,null));
            var defer=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"RETRY");
            h.operations.completed(defer,null,LifecycleEvent.Outcome.RETRYING,new LogOperationDiagnostics.Facts(null,null,null,1,3,false,null,null));
            h.await(6);var terminals=h.rows.stream().filter(row->row.get("eventName").equals("operation.failed")).toList();
            assertEquals(List.of("UNKNOWN","REJECTED","RETRYING"),terminals.stream().map(LogOperationDiagnosticsTest::context).map(row->row.get("outcome")).toList());
            for(var row:terminals) {
                assertEquals("DEBUG",row.get("severity"));assertFalse(context(row).containsKey("errorCode"));assertFalse(context(row).containsKey("exceptionClass"));
                assertFalse(((Map<?,?>)context(row).get("attributes")).containsKey("cancelRequested"));
            }
            assertEquals(3,((Number)context(terminals.get(1)).get("attempt")).intValue());
            assertEquals(3,((Number)((Map<?,?>)context(terminals.get(1)).get("attributes")).get("count")).intValue());
        }
    }
    @Test void invalidFactsCannotConsumeTerminalAndConcurrentCompletionEmitsOnlyOne() throws Exception {
        try(var h=new Harness(CATALOG,false)) {
            var token=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"CLEANUP");
            assertThrows(IllegalArgumentException.class,()->new LogOperationDiagnostics.Facts(99,null,null,0,1,false,null,null));
            assertThrows(IllegalArgumentException.class,()->new LogOperationDiagnostics.Facts(null,-1L,null,0,1,false,null,null));
            assertThrows(IllegalArgumentException.class,()->new LogOperationDiagnostics.Facts(null,null,null,-1,1,false,null,null));
            assertThrows(IllegalArgumentException.class,()->new LogOperationDiagnostics.Facts(null,null,null,0,LifecycleEvent.MAX_INTEGER+1,false,null,null));
            var invented=new LogDiagnosticCatalog.Dependency("unregistered","1",null,LogDiagnosticCatalog.Provenance.UNKNOWN);
            assertThrows(IllegalArgumentException.class,()->h.operations.completed(token,null,LifecycleEvent.Outcome.UNKNOWN,new LogOperationDiagnostics.Facts(null,null,invented,0,1,false,null,null)));
            assertThrows(IllegalArgumentException.class,()->h.operations.completed(token,null,LifecycleEvent.Outcome.UNKNOWN,new LogOperationDiagnostics.Facts(null,null,null,0,1,false,null,"UNREGISTERED")));
            assertThrows(IllegalArgumentException.class,()->h.operations.completed(token,null,LifecycleEvent.Outcome.STARTED,LogOperationDiagnostics.Facts.EMPTY));
            assertThrows(IllegalArgumentException.class,()->h.operations.completed(token,new IllegalStateException(),LifecycleEvent.Outcome.SUCCEEDED,LogOperationDiagnostics.Facts.EMPTY));
            assertThrows(NullPointerException.class,()->h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"CLEANUP",null));
            assertThrows(NullPointerException.class,()->h.operations.dependency(null));
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks=new ArrayList<Future<LogProducer.Admission>>();
                for(int index=0;index<16;index++)tasks.add(executor.submit(()->h.operations.completed(token,null,LifecycleEvent.Outcome.SUCCEEDED,
                    new LogOperationDiagnostics.Facts(null,10L,null,0,1,true,null,null))));
                int accepted=0;for(var task:tasks)if(task.get()==LogProducer.Admission.ACCEPTED)accepted++;
                assertEquals(1,accepted);
            }
            h.await(2);assertEquals(true,((Map<?,?>)context(h.rows.getLast()).get("attributes")).get("cleanupSettled"));
        }
    }
    @Test void unknownRemoteOutcomeStillKeepsTheActualObservedTimeoutWithoutInventingSettlement()throws Exception {
        var catalog=new LogDiagnosticCatalog(Set.of("PROVIDER_UNAVAILABLE","DEADLINE_EXCEEDED"),Map.of(),Set.of(java.net.SocketTimeoutException.class.getName()),Set.of());
        try(var h=new Harness(catalog,false)) {
            var token=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"PROVIDER_STORAGE_DELETE");
            h.operations.recordFailure(token,new java.net.SocketTimeoutException("private-response-canary"));
            h.operations.completed(token,null,LifecycleEvent.Outcome.UNKNOWN,new LogOperationDiagnostics.Facts(null,0L,null,1,1,false,null,"PROVIDER_UNAVAILABLE"));
            var deadline=h.operations.started(UUID.randomUUID(),new TraceContext(null,null),"DEADLINE");
            h.operations.completed(deadline,null,LifecycleEvent.Outcome.FAILED,new LogOperationDiagnostics.Facts(null,0L,null,0,1,false,null,"DEADLINE_EXCEEDED"));
            h.await(4);var unknown=h.rows.stream().filter(row->context(row).get("outcome").equals("UNKNOWN")).findFirst().orElseThrow();
            assertEquals("java.net.SocketTimeoutException",context(unknown).get("exceptionClass"));assertEquals("PROVIDER_UNAVAILABLE",context(unknown).get("errorCode"));
            assertEquals(false,((Map<?,?>)context(unknown).get("attributes")).get("cleanupSettled"));assertFalse(context(unknown).containsKey("status"));
            assertFalse(((Map<?,?>)context(unknown).get("attributes")).containsKey("cancelRequested"));
            assertFalse(JSON.writeValueAsString(h.rows).contains("canary"));
            var failed=context(h.rows.getLast());assertEquals("DEADLINE_EXCEEDED",failed.get("errorCode"));assertFalse(failed.containsKey("exceptionClass"));
        }
    }
}
