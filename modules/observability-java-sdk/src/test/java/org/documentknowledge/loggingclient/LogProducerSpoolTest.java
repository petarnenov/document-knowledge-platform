package org.documentknowledge.loggingclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LogProducerSpoolTest {
    @TempDir Path root;
    private static final String FINGERPRINT="a".repeat(64);
    private static LogSpool.Binding binding() {return new LogSpool.Binding(LogWireTest.EXECUTION,FINGERPRINT);}
    private static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<stop)Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private static LogTransport bound(LogTransport delegate) {
        return new LogTransport() {
            public Response post(Route route,byte[] body) throws Exception {return delegate.post(route,body);}
            public Optional<String> credentialFingerprint() {return Optional.of(FINGERPRINT);}
        };
    }
    private static LogProducer producer(LogTransport transport,LogSpool spool) {
        return new LogProducer(LogWireTest.EXECUTION,LogWireTest.VOCABULARY,transport,spool);
    }
    private static LogTransport committed(UUID id,List<LogTransport.Route> routes) {
        return bound((route,body)->{
            routes.add(route);assertTrue(new String(body,StandardCharsets.UTF_8).contains(id.toString()));
            return new LogTransport.Response(200,LogWireTest.receipt(List.of(new LogWire.Prepared(id,new byte[0])),"COMMITTED"));
        });
    }
    private void offline(Path directory,LifecycleEvent event) throws Exception {
        var producer=producer(bound((route,body)->{throw new IOException();}),new FileLogSpool(directory,binding()));
        try {
            assertEquals(LogProducer.Admission.ACCEPTED,producer.emit(event));
            until(()->producer.status().spooledEvents()==1 && producer.status().unknownEvents()==1);
            assertEquals(0,producer.status().unspooledEvents());assertEquals(0,producer.status().committed());
        } finally {producer.close();assertTrue(producer.awaitClosed(Duration.ofSeconds(2)));}
    }
    @Test void restartUsesOriginalScopedReceiptBeforeReplayAndCleanupFailureIsIdempotent() throws Exception {
        Path directory=root.resolve("queue");var event=LogWireTest.event(UUID.randomUUID());offline(directory,event);
        var actual=new FileLogSpool(directory,binding());var routes=new CopyOnWriteArrayList<LogTransport.Route>();
        var failingCleanup=new LogSpool() {
            public Binding binding() {return actual.binding();}
            public List<Stored> load() throws IOException {return actual.load();}
            public void replace(List<Stored> events) throws IOException {if(events.isEmpty())throw new IOException();actual.replace(events);}
            public void close() {actual.close();}
        };
        var second=producer(committed(event.metadata().eventId(),routes),failingCleanup);
        try {until(()->second.status().committed()==1 && second.status().spoolDegraded());assertEquals(0,second.status().pendingEvents());}
        finally {second.close();assertTrue(second.awaitClosed(Duration.ofSeconds(2)));}
        assertEquals(List.of(LogTransport.Route.RECEIPTS),routes);routes.clear();
        var third=producer(committed(event.metadata().eventId(),routes),new FileLogSpool(directory,binding()));
        try {until(()->third.status().committed()==1);assertEquals(List.of(LogTransport.Route.RECEIPTS),routes);}
        finally {third.close();assertTrue(third.awaitClosed(Duration.ofSeconds(2)));}
    }
    @Test void recoveredUnknownReplaysOriginalEventAfterScopedUnknownReceipt() throws Exception {
        Path directory=root.resolve("queue");var event=LogWireTest.event(UUID.randomUUID());offline(directory,event);
        var routes=new CopyOnWriteArrayList<LogTransport.Route>();var id=event.metadata().eventId();
        var transport=bound((route,body)->{
            routes.add(route);assertTrue(new String(body,StandardCharsets.UTF_8).contains(id.toString()));
            return new LogTransport.Response(200,LogWireTest.receipt(List.of(new LogWire.Prepared(id,new byte[0])),
                route==LogTransport.Route.RECEIPTS?"UNKNOWN":"COMMITTED"));
        });
        var producer=producer(transport,new FileLogSpool(directory,binding()));
        try {until(()->producer.status().committed()==1);assertEquals(List.of(LogTransport.Route.RECEIPTS,LogTransport.Route.BATCHES),routes);}
        finally {producer.close();assertTrue(producer.awaitClosed(Duration.ofSeconds(2)));}
    }
    @Test void delayedStorageDoesNotGateAdmissionAndCloseDoesNotRecycleLiveOwner() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var released=new AtomicBoolean();var network=new AtomicInteger();
        var spool=new LogSpool() {
            public Binding binding() {return LogProducerSpoolTest.binding();}
            public List<Stored> load() {return List.of();}
            public void replace(List<Stored> events) {
                entered.countDown();boolean interrupted=false;
                while(release.getCount()!=0)try {release.await();}catch(InterruptedException ignored) {interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
            public void close() {released.set(true);}
        };
        var producer=producer(bound((route,body)->{network.incrementAndGet();throw new IOException();}),spool);
        try {
            producer.emit(LogWireTest.event(UUID.randomUUID()));assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=1;i<LogProducer.MAX_EVENTS;i++)assertEquals(LogProducer.Admission.ACCEPTED,producer.emit(LogWireTest.event(UUID.randomUUID())));
            assertEquals(LogProducer.Admission.FULL,producer.emit(LogWireTest.event(UUID.randomUUID())));
            assertEquals(LogProducer.MAX_EVENTS,producer.status().unspooledEvents());assertEquals(0,network.get());
            producer.close();assertFalse(producer.awaitClosed(Duration.ofMillis(20)));assertFalse(released.get());
            assertTrue(producer.status().dispatcherAlive());release.countDown();assertTrue(producer.awaitClosed(Duration.ofSeconds(2)));
            assertTrue(released.get());assertEquals(0,network.get());
        } finally {release.countDown();producer.close();producer.awaitClosed(Duration.ofSeconds(2));}
    }
    @Test void storageFailureIsVisibleAndRecoveryPersistsBeforeNetwork() throws Exception {
        var failing=new AtomicBoolean(true);var persisted=new AtomicBoolean();var calls=new AtomicInteger();var id=UUID.randomUUID();
        var spool=new LogSpool() {
            public Binding binding() {return LogProducerSpoolTest.binding();}
            public List<Stored> load() {return List.of();}
            public void replace(List<Stored> events) throws IOException {if(failing.get())throw new IOException();persisted.set(true);}
            public void close() {}
        };
        try(var producer=producer(bound((route,body)->{
            assertTrue(persisted.get());calls.incrementAndGet();
            return new LogTransport.Response(200,LogWireTest.receipt(List.of(new LogWire.Prepared(id,new byte[0])),"COMMITTED"));
        }),spool)) {
            producer.emit(LogWireTest.event(id));until(()->producer.status().spoolDegraded());
            assertEquals(1,producer.status().unspooledEvents());assertEquals(0,calls.get());failing.set(false);
            until(()->producer.status().committed()==1);assertFalse(producer.status().spoolDegraded());
        }
    }
    @Test void recoveredUntrustedBytesOrDifferentTransportCannotStartHttp() throws Exception {
        Path directory=root.resolve("queue");var encoded=LogWire.prepare(LogWireTest.event(UUID.randomUUID()),LogWireTest.EXECUTION,LogWireTest.VOCABULARY);
        byte[] invalid=new String(encoded.bytes(),StandardCharsets.UTF_8).replace("\"phase\":\"STARTUP\"","\"phase\":\"UNREGISTERED\"").getBytes(StandardCharsets.UTF_8);
        try(var spool=new FileLogSpool(directory,binding())) {spool.load();spool.replace(List.of(new LogSpool.Stored(encoded.id(),invalid)));}
        var calls=new AtomicInteger();
        assertThrows(IllegalArgumentException.class,()->producer(bound((route,body)->{calls.incrementAndGet();throw new IOException();}),new FileLogSpool(directory,binding())));
        assertEquals(0,calls.get());
        assertThrows(IllegalArgumentException.class,()->producer((route,body)->{throw new IOException();},new FileLogSpool(directory,binding())));
        assertEquals(0,calls.get());
        assertArrayEquals(invalid,readRetained(directory));
    }
    private byte[] readRetained(Path directory) throws Exception {
        try(var spool=new FileLogSpool(directory,binding())) {return spool.load().getFirst().bytes();}
    }
}
