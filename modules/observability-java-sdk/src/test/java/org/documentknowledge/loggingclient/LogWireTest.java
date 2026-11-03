package org.documentknowledge.loggingclient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.observability.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogWireTest {
    static final ExecutionIdentity EXECUTION=new ExecutionIdentity(new BuildIdentity(UUID.randomUUID(),"runtime-tools","a".repeat(40),"b".repeat(64)),UUID.randomUUID(),UUID.randomUUID());
    static final LogVocabulary VOCABULARY=new LogVocabulary(Set.of(EventCode.OPERATION_STARTED,EventCode.OPERATION_SUCCEEDED),Set.of("STARTUP"));
    static LifecycleEvent event(UUID id) {return event(id,new TraceContext(null,null));}
    static LifecycleEvent event(UUID id,TraceContext trace) {
        return new LifecycleEvent(new EventMetadata(id,EXECUTION,Instant.parse("2026-11-03T00:00:00Z"),Severity.DEBUG,
            EventCode.OPERATION_STARTED,UUID.fromString("11111111-1111-4111-8111-111111111111"),trace),"STARTUP",LifecycleEvent.Outcome.STARTED,0,1,2,true);
    }
    static byte[] receipt(List<LogWire.Prepared> events,String state) {
        String rows=String.join(",",events.stream().map(event->"{\"eventId\":\""+event.id()+"\",\"state\":\""+state+"\""
            +(state.equals("COMMITTED")?",\"receivedAt\":\"2026-11-03T00:00:00Z\"":"")+"}").toList());
        return ("{\"receipts\":["+rows+"]}").getBytes(StandardCharsets.UTF_8);
    }
    @Test void typedEventPreservesIdentityAndOnlyClosedContext() {
        for(var trace:List.of(new TraceContext(null,null),new TraceContext("a".repeat(32),null),new TraceContext("a".repeat(32),"b".repeat(16)))) {
            var event=event(UUID.randomUUID(),trace);var encoded=LogWire.prepare(event,EXECUTION,VOCABULARY);
            String json=new String(encoded.bytes(),StandardCharsets.UTF_8);
            assertTrue(json.contains(EXECUTION.build().buildId().toString()));assertTrue(json.contains("\"phase\":\"STARTUP\""));
            assertTrue(json.contains("\"cleanupSettled\":true"));assertFalse(json.contains("tenant"));assertFalse(json.contains("message"));
            assertEquals(trace.traceId()!=null,json.contains("\"traceId\""));assertEquals(trace.spanId()!=null,json.contains("\"spanId\""));
        }
    }
    @Test void vocabularyAndNumbersRejectBeforeQueue() {
        assertThrows(IllegalArgumentException.class,()->new LogVocabulary(Set.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->new LogVocabulary(Set.of(EventCode.OPERATION_STARTED),Set.of("raw secret text")));
        var event=event(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,()->LogWire.prepare(event,EXECUTION,new LogVocabulary(Set.of(EventCode.OPERATION_SUCCEEDED),Set.of("STARTUP"))));
        assertThrows(IllegalArgumentException.class,()->LogWire.prepare(event,EXECUTION,new LogVocabulary(Set.of(EventCode.OPERATION_STARTED),Set.of("OTHER"))));
        var other=new ExecutionIdentity(EXECUTION.build(),UUID.randomUUID(),UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,()->LogWire.prepare(event,other,VOCABULARY));
        for(long invalid:new long[]{-1,LifecycleEvent.MAX_INTEGER+1}) {
            assertThrows(IllegalArgumentException.class,()->new LifecycleEvent(event.metadata(),"STARTUP",event.outcome(),invalid,0,0,false));
            assertThrows(IllegalArgumentException.class,()->new LifecycleEvent(event.metadata(),"STARTUP",event.outcome(),0,invalid,0,false));
            assertThrows(IllegalArgumentException.class,()->new LifecycleEvent(event.metadata(),"STARTUP",event.outcome(),0,0,invalid,false));
        }
        assertThrows(IllegalArgumentException.class,()->new LifecycleEvent(event.metadata(),null,event.outcome(),0,0,0,false));
        assertThrows(IllegalArgumentException.class,()->new LifecycleEvent(event.metadata(),"bad phase",event.outcome(),0,0,0,false));
        assertDoesNotThrow(()->new LifecycleEvent(event.metadata(),"STARTUP",event.outcome(),LifecycleEvent.MAX_INTEGER,0,0,false));
    }
    @Test void batchesAndReceiptsAreExactAndBounded() {
        var first=LogWire.prepare(event(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")),EXECUTION,VOCABULARY);
        var second=LogWire.prepare(event(UUID.randomUUID()),EXECUTION,VOCABULARY);var events=List.of(first,second);
        assertTrue(LogWire.batch(events).length<32768);assertTrue(new String(LogWire.lookup(events),StandardCharsets.UTF_8).contains(first.id().toString()));
        assertEquals(Map.of(first.id(),LogWire.Receipt.COMMITTED,second.id(),LogWire.Receipt.COMMITTED),LogWire.receipts(receipt(events,"COMMITTED"),events,true));
        assertEquals(LogWire.Receipt.UNKNOWN,LogWire.receipts(receipt(List.of(first),"UNKNOWN"),List.of(first),false).get(first.id()));
        assertEquals(LogWire.Receipt.EXPIRED,LogWire.receipts(receipt(List.of(first),"EXPIRED"),List.of(first),false).get(first.id()));
        assertThrows(IllegalArgumentException.class,()->LogWire.batch(List.of()));
        assertThrows(IllegalArgumentException.class,()->LogWire.batch(Collections.nCopies(33,first)));
        assertThrows(IllegalArgumentException.class,()->LogWire.batch(List.of(first,first)));
        var huge=new LogWire.Prepared(UUID.randomUUID(),new byte[32768]);
        assertThrows(IllegalArgumentException.class,()->LogWire.batch(List.of(huge)));
        for(String invalid:List.of("{}","{\"receipts\":[]}","{\"receipts\":[],\"extra\":true}",
                new String(receipt(events,"COMMITTED"),StandardCharsets.UTF_8)+"{}",
                new String(receipt(events,"COMMITTED"),StandardCharsets.UTF_8).replace(second.id().toString(),first.id().toString()),
                new String(receipt(events,"COMMITTED"),StandardCharsets.UTF_8).replace(first.id().toString(),first.id().toString().toUpperCase(Locale.ROOT)),
                new String(receipt(events,"COMMITTED"),StandardCharsets.UTF_8).replace("2026-11-03T00:00:00Z","bad"),
                new String(receipt(events,"COMMITTED"),StandardCharsets.UTF_8).replace("\"state\":","\"state\":\"UNKNOWN\",\"state\":")))
            assertThrows(IllegalArgumentException.class,()->LogWire.receipts(invalid.getBytes(StandardCharsets.UTF_8),events,true));
        assertThrows(IllegalArgumentException.class,()->LogWire.receipts(receipt(events,"UNKNOWN"),events,true));
        assertThrows(IllegalArgumentException.class,()->LogWire.receipts(new byte[32769],events,false));
        assertThrows(IllegalArgumentException.class,()->LogWire.receipts(new byte[]{(byte)0xff},events,false));
        assertThrows(IllegalArgumentException.class,()->LogWire.receipts(new byte[0],events,false));
    }
}
