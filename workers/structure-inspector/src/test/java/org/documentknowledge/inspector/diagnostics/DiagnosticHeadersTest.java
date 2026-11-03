package org.documentknowledge.inspector.diagnostics;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticHeadersTest {
    private static final String ID="f1f35098-d396-4cdf-bbc7-9fcaf17e5c11",TRACE="1".repeat(32),SPAN="2".repeat(16);
    @Test void carriesOnlyCanonicalDiagnosticIdentityAndAbsentHeadersGetFreshContext() {
        var context=DiagnosticHeaders.read(List.of(ID),List.of("00-"+TRACE+"-"+SPAN+"-01"),InspectorDiagnostics.NOOP);
        assertEquals(UUID.fromString(ID),context.correlationId());assertEquals(TRACE,context.traceId());assertEquals(SPAN,context.spanId());
        var first=DiagnosticHeaders.read(null,null,InspectorDiagnostics.NOOP);var next=DiagnosticHeaders.read(null,null,InspectorDiagnostics.NOOP);
        assertNotEquals(first.correlationId(),next.correlationId());assertNull(first.traceId());assertNull(first.spanId());
    }
    @Test void rejectsDuplicateOversizedZeroAndMalformedMetadataWithoutChangingRequestOrThrowing() {
        var rejected=new AtomicInteger();InspectorDiagnostics port=new InspectorDiagnostics(){public Observation started(Context c,Phase p){return NONE;}public void rejected(){rejected.incrementAndGet();}};
        var badIds=new ArrayList<List<String>>(List.of(List.of(),List.of(ID,ID),List.of("SECRET".repeat(100)),List.of("00000000-0000-0000-0000-000000000000"),List.of(ID.toUpperCase()),List.of("x".repeat(36))));badIds.add(Collections.singletonList(null));
        for(var bad:badIds){var result=DiagnosticHeaders.read(bad,null,port);assertNotEquals(UUID.fromString(ID),result.correlationId());assertNull(result.traceId());}
        var badTraces=new ArrayList<List<String>>(List.of(List.of(),List.of("x","y"),List.of("SECRET".repeat(100)),List.of("01-"+TRACE+"-"+SPAN+"-01"),List.of("00-"+"0".repeat(32)+"-"+SPAN+"-01"),List.of("00-"+TRACE+"-"+"0".repeat(16)+"-01"),List.of("00-"+"Z".repeat(32)+"-"+SPAN+"-01")));badTraces.add(Collections.singletonList(null));
        for(var bad:badTraces){var result=DiagnosticHeaders.read(List.of(ID),bad,port);assertEquals(UUID.fromString(ID),result.correlationId());assertNull(result.traceId());assertNull(result.spanId());}
        assertEquals(badIds.size()+badTraces.size(),rejected.get());
        InspectorDiagnostics broken=new InspectorDiagnostics(){public Observation started(Context c,Phase p){return NONE;}public void rejected(){throw new IllegalStateException("PRIVATE");}};
        assertDoesNotThrow(()->DiagnosticHeaders.read(List.of("bad"),List.of("bad"),broken));
    }
}
