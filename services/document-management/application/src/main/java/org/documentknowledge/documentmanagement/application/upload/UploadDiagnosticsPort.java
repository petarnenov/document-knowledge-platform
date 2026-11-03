package org.documentknowledge.documentmanagement.application.upload;

import java.util.Objects;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;

/** Component-owned technical observations. No logging SDK, provider configuration or domain authority. */
@FunctionalInterface
public interface UploadDiagnosticsPort {
    enum Operation {
        PROVIDER_STORAGE_PUT,PROVIDER_STORAGE_OPEN,PROVIDER_STORAGE_READ,PROVIDER_STORAGE_CLOSE,
        PROVIDER_STORAGE_COPY,PROVIDER_STORAGE_VERSIONS,PROVIDER_STORAGE_DELETE,PROVIDER_SCAN,PROVIDER_INSPECT,
        RETRY,DEADLINE,CANCELLATION,CLEANUP
    }
    enum Outcome { SUCCEEDED,FAILED,CANCELLED,UNKNOWN,REJECTED,RETRYING }
    enum Error { PROVIDER_UNAVAILABLE,DEADLINE_EXCEEDED,CANCELLED,CLEANUP_FAILED }
    record Facts(Integer status,Long remainingDeadlineMs,long attempt,long count,boolean cleanupSettled,
                 Boolean cancelRequested,Error error) {
        public static final Facts EMPTY=new Facts(null,null,0,1,false,null,null);
        private static final long MAX=9_007_199_254_740_991L;
        public Facts {
            if(status!=null && (status<100 || status>599) || remainingDeadlineMs!=null && (remainingDeadlineMs<0 || remainingDeadlineMs>MAX)
                || attempt<0 || attempt>MAX || count<0 || count>MAX)throw new IllegalArgumentException("INVALID_UPLOAD_DIAGNOSTIC");
        }
    }
    @FunctionalInterface interface FailureObserver {
        void failure(Throwable failure);
        default void status(int status) { }
        default void cancelRequested() { }
    }
    interface Observation extends FailureObserver {
        /** Note only; the single invocation owner completes the observation after the actual call. */
        void failure(Throwable failure);
        void completed(Outcome outcome,Throwable failure,Facts facts);
    }
    Observation NONE=new Observation() {
        public void failure(Throwable failure) { }
        public void completed(Outcome outcome,Throwable failure,Facts facts) { }
    };
    UploadDiagnosticsPort NOOP=(context,operation,facts)->NONE;
    Observation started(DiagnosticContext context,Operation operation,Facts facts);
    default void rejected() { }
    static void note(FailureObserver observer,Throwable failure) {try{observer.failure(failure);}catch(RuntimeException ignored){}}
    static void status(FailureObserver observer,int status) {if(status>=100 && status<=599)try{observer.status(status);}catch(RuntimeException ignored){}}
    static void cancel(FailureObserver observer) {try{observer.cancelRequested();}catch(RuntimeException ignored){}}

    /** Isolate a technical observer, including a failed rejection callback, from original business behavior. */
    static UploadDiagnosticsPort safe(UploadDiagnosticsPort delegate,Runnable rejected) {
        Objects.requireNonNull(delegate);Objects.requireNonNull(rejected);
        if(delegate==NOOP)return NOOP;
        Runnable loss=()->{try{rejected.run();}catch(RuntimeException ignored){}};
        return new UploadDiagnosticsPort() {
          public void rejected() {loss.run();}
          public Observation started(DiagnosticContext context,Operation operation,Facts facts) {
            Observation original;
            try{original=Objects.requireNonNull(delegate.started(context,operation,facts));}
            catch(RuntimeException diagnostic){loss.run();return NONE;}
            return new Observation() {
                private boolean terminal;
                public synchronized void failure(Throwable failure) {
                    if(terminal)return;
                    try{original.failure(failure);}catch(RuntimeException diagnostic){loss.run();}
                }
                public synchronized void status(int status) {
                    if(terminal)return;
                    try{original.status(status);}catch(RuntimeException diagnostic){loss.run();}
                }
                public synchronized void cancelRequested() {
                    if(terminal)return;
                    try{original.cancelRequested();}catch(RuntimeException diagnostic){loss.run();}
                }
                public synchronized void completed(Outcome outcome,Throwable failure,Facts facts) {
                    if(terminal)return;terminal=true;
                    try{original.completed(outcome,failure,facts);}catch(RuntimeException diagnostic){loss.run();}
                }
            };
          }
        };
    }
}
