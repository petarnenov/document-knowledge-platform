package org.documentknowledge.loggingclient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class LogProducerTest {
    private static List<LogWire.Prepared> events(byte[] bytes,LogTransport.Route route) {
        Map<?,?> value=JsonMapper.builder().build().readValue(bytes,Map.class);
        if(route==LogTransport.Route.RECEIPTS)return ((List<?>)value.get("eventIds")).stream().map(id->new LogWire.Prepared(UUID.fromString((String)id),new byte[0])).toList();
        return ((List<?>)value.get("events")).stream().map(row->new LogWire.Prepared(UUID.fromString((String)((Map<?,?>)row).get("eventId")),new byte[0])).toList();
    }
    private static LogProducer producer(LogTransport transport) {return new LogProducer(LogWireTest.EXECUTION,LogWireTest.VOCABULARY,transport);}
    private static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<stop)Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    @Test void delayedTransportDoesNotGateEmitAndQueueIncludesInflight() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var count=new AtomicInteger();
        try(var producer=producer((route,body)->{
            entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));count.incrementAndGet();
            return new LogTransport.Response(200,LogWireTest.receipt(events(body,route),"COMMITTED"));
        })) {
            var first=LogWireTest.event(UUID.randomUUID());assertEquals(LogProducer.Admission.ACCEPTED,producer.emit(first));
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(LogProducer.Admission.DUPLICATE,producer.emit(first));
            var changed=new LifecycleEvent(first.metadata(),first.phase(),first.outcome(),10,0,0,false);
            assertEquals(LogProducer.Admission.REJECTED,producer.emit(changed));
            assertEquals(LogProducer.Admission.REJECTED,producer.emit(null));
            for(int index=1;index<LogProducer.MAX_EVENTS;index++)assertEquals(LogProducer.Admission.ACCEPTED,producer.emit(LogWireTest.event(UUID.randomUUID())));
            assertEquals(0,count.get());assertEquals(LogProducer.MAX_EVENTS,producer.status().pendingEvents());
            assertEquals(LogProducer.Admission.FULL,producer.emit(LogWireTest.event(UUID.randomUUID())));
            assertTrue(producer.status().pendingBytes()<=LogProducer.MAX_BYTES);release.countDown();
            until(()->producer.status().pendingEvents()==0);assertEquals(LogProducer.MAX_EVENTS,producer.status().committed());
            assertEquals(2,producer.status().rejected());assertEquals(1,producer.status().dropped());
        } finally {release.countDown();}
    }
    @Test void unknownCommitUsesScopedLookupBeforeReplay() throws Exception {
        var routes=new CopyOnWriteArrayList<LogTransport.Route>();
        try(var producer=producer((route,body)->{
            routes.add(route);var events=events(body,route);
            if(routes.size()==1)throw new java.io.IOException("unavailable");
            return new LogTransport.Response(200,LogWireTest.receipt(events,"COMMITTED"));
        })) {
            producer.emit(LogWireTest.event(UUID.randomUUID()));until(()->producer.status().unknownEvents()==1);
            until(()->producer.status().committed()==1);assertEquals(List.of(LogTransport.Route.BATCHES,LogTransport.Route.RECEIPTS),routes);
        }
    }
    @Test void queueAcceptanceAndInvalidReceiptNeverClaimPersistenceAndRecoverNormally() throws Exception {
        var calls=new AtomicInteger();
        try(var producer=producer((route,body)->{
            int call=calls.incrementAndGet();
            if(call==1)return new LogTransport.Response(202,"{}".getBytes(StandardCharsets.UTF_8));
            if(route==LogTransport.Route.RECEIPTS)return new LogTransport.Response(200,LogWireTest.receipt(events(body,route),"UNKNOWN"));
            if(call==3)return new LogTransport.Response(200,"{}".getBytes(StandardCharsets.UTF_8));
            return new LogTransport.Response(200,LogWireTest.receipt(events(body,route),"COMMITTED"));
        })) {
            producer.emit(LogWireTest.event(UUID.randomUUID()));until(()->producer.status().unknownEvents()==1);
            assertEquals(0,producer.status().committed());until(()->producer.status().committed()==1);assertTrue(calls.get()>=5);
        }
    }
    @Test void permanentRejectionAndExpiredReceiptAreVisible() throws Exception {
        for(int status:new int[]{400,409,413}) {
            try(var producer=producer((route,body)->new LogTransport.Response(status,new byte[0]))) {
                producer.emit(LogWireTest.event(UUID.randomUUID()));until(()->producer.status().rejected()==1);
                assertEquals(0,producer.status().committed());assertEquals(0,producer.status().pendingEvents());
            }
        }
        try(var producer=producer((route,body)->route==LogTransport.Route.BATCHES?new LogTransport.Response(410,new byte[0])
                :new LogTransport.Response(200,LogWireTest.receipt(events(body,route),"EXPIRED")))) {
            producer.emit(LogWireTest.event(UUID.randomUUID()));until(()->producer.status().expired()==1);assertEquals(0,producer.status().committed());
        }
    }
    @Test void closeIsBoundedAndUnconfirmedPendingIsRetained() throws Exception {
        var entered=new CountDownLatch(1);var transportClosed=new AtomicBoolean();
        var transport=new LogTransport() {
            public Response post(Route route,byte[] bytes) throws InterruptedException {entered.countDown();new CountDownLatch(1).await();throw new AssertionError();}
            public void close() {transportClosed.set(true);}
        };
        var producer=producer(transport);producer.emit(LogWireTest.event(UUID.randomUUID()));assertTrue(entered.await(2,TimeUnit.SECONDS));
        producer.close();assertTrue(producer.awaitClosed(Duration.ofSeconds(2)));assertTrue(transportClosed.get());
        assertEquals(1,producer.status().unknownEvents());assertEquals(1,producer.status().pendingEvents());assertEquals(0,producer.status().committed());
        assertTrue(producer.status().closed());assertFalse(producer.status().dispatcherAlive());
        assertEquals(LogProducer.Admission.CLOSED,producer.emit(LogWireTest.event(UUID.randomUUID())));
        assertTrue(producer.awaitClosed(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,()->producer.awaitClosed(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,()->producer.awaitClosed(Duration.ofSeconds(6)));
        assertThrows(IllegalArgumentException.class,()->new LogTransport.Response(99,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->new LogTransport.Response(200,new byte[32769]));
    }
}
