package org.documentknowledge.inspector.diagnostics;

import java.util.Objects;
import java.util.UUID;

/** Worker-owned technical port. No SDK, transport, content or authorization capability. */
@FunctionalInterface
public interface InspectorDiagnostics {
    enum Phase { STARTUP,SHUTDOWN,REQUEST,OPERATION,CLEANUP,DEADLINE }
    enum Outcome { SUCCEEDED,FAILED,CANCELLED,UNKNOWN }
    enum Error { INVALID_INPUT,UNAUTHENTICATED,NOT_FOUND,CAPACITY,OPERATION_FAILED,CLEANUP_FAILED,DEADLINE_EXCEEDED }
    record Context(UUID correlationId,String traceId,String spanId) {
        public Context {
            Objects.requireNonNull(correlationId);
            if(correlationId.getMostSignificantBits()==0 && correlationId.getLeastSignificantBits()==0)
                throw new IllegalArgumentException("INVALID_INSPECTOR_CONTEXT");
            if(traceId==null) {
                if(spanId!=null)throw new IllegalArgumentException("INVALID_INSPECTOR_CONTEXT");
            } else {
                hex(traceId,32);
                if(spanId!=null)hex(spanId,16);
            }
        }
        private static void hex(String value,int length) {
            if(!value.matches("[0-9a-f]{"+length+"}") || value.chars().allMatch(c->c=='0'))
                throw new IllegalArgumentException("INVALID_INSPECTOR_CONTEXT");
        }
        public static Context fresh() {return new Context(UUID.randomUUID(),null,null);}
    }
    record Facts(Integer status,Long remainingDeadlineMs,long count,boolean cleanupSettled,Error error) {
        public static final Facts EMPTY=new Facts(null,null,0,false,null);
        public Facts {
            if(status!=null && (status<100 || status>599)
                || remainingDeadlineMs!=null && (remainingDeadlineMs<0 || remainingDeadlineMs>9_007_199_254_740_991L)
                || count<0 || count>9_007_199_254_740_991L)
                throw new IllegalArgumentException("INVALID_INSPECTOR_FACTS");
        }
    }
    interface Observation {
        /** Capture sanitized immutable symbols now; never retain this Throwable in a queue. */
        void failure(Throwable failure);
        void completed(Outcome outcome,Facts facts);
    }
    Observation NONE=new Observation() {
        public void failure(Throwable failure) { }
        public void completed(Outcome outcome,Facts facts) { }
    };
    InspectorDiagnostics NOOP=(context,phase)->NONE;
    Observation started(Context context,Phase phase);
    default void rejected() { }

    /** Technical callback failures cannot change inspection or public HTTP behavior. */
    static InspectorDiagnostics safe(InspectorDiagnostics delegate) {
        Objects.requireNonNull(delegate);
        if(delegate==NOOP)return NOOP;
        Runnable lost=()->{try{delegate.rejected();}catch(RuntimeException ignored){}};
        return new InspectorDiagnostics() {
            public void rejected() {lost.run();}
            public Observation started(Context context,Phase phase) {
                Observation original;
                try {original=Objects.requireNonNull(delegate.started(context,phase));}
                catch(RuntimeException unavailable){lost.run();return NONE;}
                return new Observation() {
                    private boolean terminal;
                    public synchronized void failure(Throwable failure) {
                        if(terminal)return;
                        try {original.failure(failure);}catch(RuntimeException unavailable){lost.run();}
                    }
                    public synchronized void completed(Outcome outcome,Facts facts) {
                        if(terminal)return;terminal=true;
                        try {original.completed(outcome,facts);}catch(RuntimeException unavailable){lost.run();}
                    }
                };
            }
        };
    }
}
