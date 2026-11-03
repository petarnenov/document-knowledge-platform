package org.documentknowledge.inspector.diagnostics;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.documentknowledge.inspector.diagnostics.InspectorDiagnostics.*;

class InspectorDiagnosticsTest {
    @Test void typedContextAndFactsRejectInvalidAuthorityFreeMetadata() {
        var context=new Context(UUID.randomUUID(),"a".repeat(32),"b".repeat(16));
        assertNotEquals(Context.fresh(),Context.fresh());assertEquals("a".repeat(32),context.traceId());
        assertDoesNotThrow(()->new Context(UUID.randomUUID(),"a".repeat(32),null));
        assertThrows(NullPointerException.class,()->new Context(null,null,null));
        assertThrows(IllegalArgumentException.class,()->new Context(new UUID(0,0),null,null));
        assertThrows(IllegalArgumentException.class,()->new Context(UUID.randomUUID(),null,"a".repeat(16)));
        for(String invalid:new String[]{"0".repeat(32),"A".repeat(32),"a".repeat(31)})
            assertThrows(IllegalArgumentException.class,()->new Context(UUID.randomUUID(),invalid,null));
        assertThrows(IllegalArgumentException.class,()->new Context(UUID.randomUUID(),"a".repeat(32),"0".repeat(16)));
        assertThrows(IllegalArgumentException.class,()->new Context(UUID.randomUUID(),"a".repeat(32),"bad"));
        assertDoesNotThrow(()->new Facts(100,0L,0,true,null));assertDoesNotThrow(()->new Facts(599,9_007_199_254_740_991L,9_007_199_254_740_991L,false,InspectorDiagnostics.Error.OPERATION_FAILED));
        for(int status:new int[]{99,600})assertThrows(IllegalArgumentException.class,()->new Facts(status,null,0,false,null));
        for(long count:new long[]{-1,9_007_199_254_740_992L}) {
            assertThrows(IllegalArgumentException.class,()->new Facts(null,null,count,false,null));
            assertThrows(IllegalArgumentException.class,()->new Facts(null,count,0,false,null));
        }
    }
    @Test void safeObservationPreservesContextOriginalFailureAndSingleTerminal() {
        var starts=new AtomicInteger();var failures=new AtomicInteger();var terminals=new AtomicInteger();
        var context=Context.fresh();var failure=new IllegalStateException("untrusted content must not reach output");
        InspectorDiagnostics delegate=(actual,phase)->{
            assertSame(context,actual);assertEquals(Phase.OPERATION,phase);starts.incrementAndGet();
            return new Observation() {
                public void failure(Throwable actualFailure){assertSame(failure,actualFailure);failures.incrementAndGet();}
                public void completed(Outcome outcome,Facts facts){assertEquals(Outcome.FAILED,outcome);assertSame(Facts.EMPTY,facts);terminals.incrementAndGet();}
            };
        };
        var observation=safe(delegate).started(context,Phase.OPERATION);
        observation.failure(failure);observation.completed(Outcome.FAILED,Facts.EMPTY);
        observation.completed(Outcome.SUCCEEDED,Facts.EMPTY);observation.failure(failure);
        assertEquals(1,starts.get());assertEquals(1,failures.get());assertEquals(1,terminals.get());
        assertSame(NOOP,safe(NOOP));NOOP.started(context,Phase.REQUEST).failure(failure);
        NONE.completed(Outcome.UNKNOWN,Facts.EMPTY);
    }
    @Test void everyFailingTechnicalCallbackIsIsolatedAndLossIsVisible() {
        var rejected=new AtomicInteger();
        InspectorDiagnostics broken=new InspectorDiagnostics() {
            public Observation started(Context context,Phase phase) {
                return new Observation() {
                    public void failure(Throwable failure){throw new IllegalStateException();}
                    public void completed(Outcome outcome,Facts facts){throw new IllegalStateException();}
                };
            }
            public void rejected(){rejected.incrementAndGet();}
        };
        var safe=safe(broken);var observation=safe.started(Context.fresh(),Phase.REQUEST);
        assertDoesNotThrow(()->observation.failure(new RuntimeException()));
        assertDoesNotThrow(()->observation.completed(Outcome.FAILED,Facts.EMPTY));
        safe.rejected();assertEquals(3,rejected.get());
        for(boolean nullResult:new boolean[]{false,true}) {
            InspectorDiagnostics invalid=new InspectorDiagnostics() {
                public Observation started(Context context,Phase phase){if(nullResult)return null;throw new IllegalStateException();}
                public void rejected(){throw new IllegalStateException();}
            };
            assertSame(NONE,safe(invalid).started(Context.fresh(),Phase.REQUEST));
            assertDoesNotThrow(()->safe(invalid).rejected());
        }
        assertThrows(NullPointerException.class,()->safe(null));
    }
}
