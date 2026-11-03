package org.documentknowledge.documentmanagement.application.upload;

import java.io.*;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;
import org.documentknowledge.documentmanagement.application.DmFailure;
import static org.documentknowledge.documentmanagement.application.upload.UploadDiagnosticsPort.*;

/** Per-caller coordinator. The callbacks only observe; actual resource and settlement ownership stays with callers. */
public final class UploadObservations {
    private final UploadDiagnosticsPort diagnostics;
    private final DiagnosticContext context;
    public UploadObservations(UploadDiagnosticsPort diagnostics,DiagnosticContext context) {
        Objects.requireNonNull(diagnostics);this.diagnostics=UploadDiagnosticsPort.safe(diagnostics,diagnostics::rejected);
        this.context=Objects.requireNonNull(context);
    }
    private Facts snapshot(Supplier<Facts> facts) {
        try{return Objects.requireNonNull(facts.get());}catch(RuntimeException diagnostic){diagnostics.rejected();return Facts.EMPTY;}
    }
    private <T> Outcome classify(Function<T,Outcome> resultOutcome,T result) {
        try{return Objects.requireNonNull(resultOutcome.apply(result));}catch(RuntimeException diagnostic){diagnostics.rejected();return Outcome.UNKNOWN;}
    }
    public <T> T call(Operation operation,Supplier<Facts> facts,Function<FailureObserver,T> action) {
        return call(operation,facts,action,result->Outcome.SUCCEEDED);
    }
    public <T> T call(Operation operation,Supplier<Facts> facts,Function<FailureObserver,T> action,Function<T,Outcome> resultOutcome) {
        var observation=diagnostics.started(context,operation,snapshot(facts));
        try {
            T result=action.apply(observation);var actual=snapshot(facts);var terminal=classify(resultOutcome,result);
            if(operation==Operation.CLEANUP && terminal==Outcome.SUCCEEDED)actual=new Facts(actual.status(),actual.remainingDeadlineMs(),actual.attempt(),actual.count(),true,actual.cancelRequested(),actual.error());
            observation.completed(terminal,null,actual);return result;
        }catch(RuntimeException failure) {
            boolean rejected=failure instanceof DmFailure mapped && mapped.status()<500;
            var outcome=failure instanceof CancellationException?Outcome.CANCELLED:rejected?Outcome.REJECTED:Outcome.FAILED;
            var actual=snapshot(facts);
            var error=outcome==Outcome.REJECTED?null:switch(operation){
                case CLEANUP->UploadDiagnosticsPort.Error.CLEANUP_FAILED;
                case CANCELLATION->UploadDiagnosticsPort.Error.CANCELLED;
                case DEADLINE->UploadDiagnosticsPort.Error.DEADLINE_EXCEEDED;
                default->UploadDiagnosticsPort.Error.PROVIDER_UNAVAILABLE;
            };
            observation.completed(outcome,failure,new Facts(actual.status(),actual.remainingDeadlineMs(),actual.attempt(),actual.count(),
                actual.cleanupSettled(),actual.cancelRequested(),error));
            throw failure;
        }
    }
    public static Facts remaining(long millis) {return new Facts(null,millis,0,1,false,null,null);}
    public InputStream open(Supplier<Facts> facts,Function<FailureObserver,InputStream> action) {
        return stream(call(Operation.PROVIDER_STORAGE_OPEN,facts,action),facts);
    }
    /** One READ aggregate per opened stream. No event allocation per chunk and no implicit settlement. */
    public InputStream stream(InputStream input,Supplier<Facts> facts) {
        Objects.requireNonNull(input);if(diagnostics==UploadDiagnosticsPort.NOOP)return input;
        var read=diagnostics.started(context,Operation.PROVIDER_STORAGE_READ,snapshot(facts));
        return new InputStream() {
            private boolean terminal;
            private synchronized void finish(Outcome outcome,Throwable failure) {
                if(terminal)return;terminal=true;read.completed(outcome,failure,snapshot(facts));
            }
            public int read()throws IOException {
                try{int result=input.read();if(result<0)finish(Outcome.SUCCEEDED,null);return result;}
                catch(IOException|RuntimeException failure){finish(Outcome.FAILED,failure);throw failure;}
            }
            public int read(byte[] bytes,int offset,int length)throws IOException {
                try{int result=input.read(bytes,offset,length);if(result<0)finish(Outcome.SUCCEEDED,null);return result;}
                catch(IOException|RuntimeException failure){finish(Outcome.FAILED,failure);throw failure;}
            }
            public long skip(long count)throws IOException {
                try{return input.skip(count);}catch(IOException|RuntimeException failure){finish(Outcome.FAILED,failure);throw failure;}
            }
            public int available()throws IOException {return input.available();}
            public boolean markSupported(){return input.markSupported();}
            public synchronized void mark(int limit){input.mark(limit);}
            public synchronized void reset()throws IOException {input.reset();}
            public void close()throws IOException {
                var close=diagnostics.started(context,Operation.PROVIDER_STORAGE_CLOSE,snapshot(facts));
                try{input.close();close.completed(Outcome.SUCCEEDED,null,snapshot(facts));}
                catch(IOException|RuntimeException failure){close.completed(Outcome.FAILED,failure,snapshot(facts));throw failure;}
                finally{finish(Outcome.UNKNOWN,null);}
            }
        };
    }
    public void decision(Operation operation,Outcome outcome,Facts facts) {
        diagnostics.started(context,operation,facts).completed(outcome,null,facts);
    }
}
