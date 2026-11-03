package org.documentknowledge.documentmanagement.bootstrap;

import java.util.Objects;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;
import org.documentknowledge.documentmanagement.application.upload.UploadDiagnosticsPort;
import org.documentknowledge.loggingclient.ApplicationLogRuntime;
import org.documentknowledge.loggingclient.LifecycleEvent;
import org.documentknowledge.loggingclient.LogOperationDiagnostics;
import org.documentknowledge.observability.TraceContext;

/** Original producer composition; the pure upload port never acquires SDK or runtime authority. */
final class LoggingUploadDiagnostics implements UploadDiagnosticsPort {
    private final LogOperationDiagnostics operations;
    private LoggingUploadDiagnostics(LogOperationDiagnostics operations) {this.operations=Objects.requireNonNull(operations);}
    static UploadDiagnosticsPort compose(ApplicationLogRuntime logging) {
        if(logging==null)return UploadDiagnosticsPort.NOOP;
        var operations=logging.operations();
        return UploadDiagnosticsPort.safe(new LoggingUploadDiagnostics(operations),operations::reportRejected);
    }
    public Observation started(DiagnosticContext context,Operation operation,Facts facts) {
        var dependency=operation.name().startsWith("PROVIDER_STORAGE_")?operations.dependency("software.amazon.awssdk.s3"):null;
        var initial=convert(facts,dependency);
        var token=operations.started(context.correlationId(),new TraceContext(context.traceId(),context.spanId()),operation.name(),initial);
        return new Observation() {
            private Integer status;
            private boolean cancelRequested;
            public synchronized void status(int value) {if(value>=100 && value<=599)status=value;}
            public synchronized void cancelRequested() {cancelRequested=true;}
            public void failure(Throwable failure) {operations.recordFailure(token,failure);}
            public synchronized void completed(Outcome outcome,Throwable failure,Facts facts) {
                var terminal=new Facts(status==null?facts.status():status,facts.remainingDeadlineMs(),facts.attempt(),facts.count(),
                    facts.cleanupSettled(),cancelRequested?Boolean.TRUE:facts.cancelRequested(),facts.error());
                operations.completed(token,failure,LifecycleEvent.Outcome.valueOf(outcome.name()),convert(terminal,dependency));
            }
        };
    }
    public void rejected() {operations.reportRejected();}
    private static LogOperationDiagnostics.Facts convert(Facts facts,org.documentknowledge.loggingclient.LogDiagnosticCatalog.Dependency dependency) {
        return new LogOperationDiagnostics.Facts(facts.status(),facts.remainingDeadlineMs(),dependency,facts.attempt(),facts.count(),
            facts.cleanupSettled(),facts.cancelRequested(),facts.error()==null?null:facts.error().name());
    }
}
