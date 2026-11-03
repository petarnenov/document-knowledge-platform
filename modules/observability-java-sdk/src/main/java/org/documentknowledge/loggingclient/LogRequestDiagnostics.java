package org.documentknowledge.loggingclient;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.observability.*;

/** Framework-independent request observation; no HTTP object, authority or raw exception is queued. */
public final class LogRequestDiagnostics {
    /** Context is immutable; the private terminal latch prevents duplicate completion callbacks. */
    public static final class Token {
        private final LogRequestDiagnostics owner;
        private final UUID correlation;
        private final TraceContext trace;
        private final long openedAt=System.nanoTime();
        private final AtomicBoolean terminal=new AtomicBoolean();
        private Token(LogRequestDiagnostics owner,UUID correlation,TraceContext trace) {
            this.owner=owner;this.correlation=correlation;this.trace=trace;
        }
    }
    private final ExecutionIdentity execution;
    private final LogProducer producer;
    private final LogDiagnosticCatalog catalog;
    LogRequestDiagnostics(ExecutionIdentity execution,LogProducer producer,LogDiagnosticCatalog catalog) {
        this.execution=Objects.requireNonNull(execution);this.producer=Objects.requireNonNull(producer);
        this.catalog=Objects.requireNonNull(catalog);
    }
    public Token started(String correlation,String traceparent) {
        var token=new Token(this,correlation(correlation),trace(traceparent));
        emit(token,EventCode.OPERATION_STARTED,Severity.TRACE,LifecycleEvent.Outcome.STARTED,null);
        return token;
    }
    public LogProducer.Admission completed(Token token,int status,Throwable failure) {
        Objects.requireNonNull(token);
        if(token.owner!=this || status<100 || status>599)throw new IllegalArgumentException("INVALID_LOG_REQUEST_COMPLETION");
        if(!token.terminal.compareAndSet(false,true))return LogProducer.Admission.DUPLICATE;
        boolean cancelled=failure instanceof CancellationException;
        boolean failed=failure!=null || status>=400;
        var code=cancelled?EventCode.OPERATION_CANCELLED:failed?EventCode.OPERATION_FAILED:EventCode.OPERATION_SUCCEEDED;
        var outcome=cancelled?LifecycleEvent.Outcome.CANCELLED:failed?LifecycleEvent.Outcome.FAILED:LifecycleEvent.Outcome.SUCCEEDED;
        var captured=failure==null?null:LogExceptionCapture.capture(failure,catalog);
        String error=cancelled?"CANCELLED":failed?error(status):null;
        if(error!=null && !catalog.errorCodes().contains(error))error=null;
        var diagnostics=new LogDiagnostics(status,null,error,null,null,captured==null?null:captured.failure(),
            cancelled?new LogDiagnostics.Attributes(null,null,true):null,captured==null?LogDiagnostics.Counts.ZERO:captured.counts());
        return emit(token,code,failed?status>=500?Severity.ERROR:Severity.WARN:Severity.DEBUG,outcome,diagnostics);
    }
    private LogProducer.Admission emit(Token token,EventCode code,Severity severity,LifecycleEvent.Outcome outcome,LogDiagnostics diagnostics) {
        var metadata=new EventMetadata(UUID.randomUUID(),execution,Instant.now(),severity,code,token.correlation,token.trace);
        return producer.emit(new LifecycleEvent(metadata,"REQUEST",outcome,
            Math.max(0,(System.nanoTime()-token.openedAt)/1000000),0,1,false,diagnostics));
    }
    private static String error(int status) {
        return switch(status) {
            case 400,422 -> "INVALID_INPUT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "CAPACITY";
            case 408,504 -> "DEADLINE_EXCEEDED";
            default -> "OPERATION_FAILED";
        };
    }
    private static UUID correlation(String value) {
        if(value!=null && value.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            var parsed=UUID.fromString(value);if(parsed.getMostSignificantBits()!=0 || parsed.getLeastSignificantBits()!=0)return parsed;
        }
        return UUID.randomUUID();
    }
    private static TraceContext trace(String value) {
        if(value!=null && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-(?:00|01)")) {
            try {return new TraceContext(value.substring(3,35),value.substring(36,52));}
            catch(IllegalArgumentException invalid) { /* Untrusted context cannot fail an HTTP request. */ }
        }
        return new TraceContext(null,null);
    }
}
