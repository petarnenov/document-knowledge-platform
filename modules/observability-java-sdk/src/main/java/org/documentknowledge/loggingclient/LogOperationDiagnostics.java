package org.documentknowledge.loggingclient;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.documentknowledge.observability.*;

/** Bounded technical observations on the original producer; no raw failure is retained by a token. */
public final class LogOperationDiagnostics {
    private static final Set<String> PHASES=Set.of("OPERATION","DATABASE","PROVIDER","RETRY","DEADLINE","CANCELLATION","CLEANUP");
    private record Observed(LogExceptionCapture.Captured captured,String sqlState,boolean sqlFailure) { }
    public static final class Token {
        private final LogOperationDiagnostics owner;
        private final UUID correlation;
        private final TraceContext trace;
        private final String phase;
        private final long openedAt=System.nanoTime();
        private boolean terminal;
        private Observed observed;
        private Token(LogOperationDiagnostics owner,UUID correlation,TraceContext trace,String phase) {
            this.owner=owner;this.correlation=correlation;this.trace=trace;this.phase=phase;
        }
    }
    private final ExecutionIdentity execution;
    private final LogProducer producer;
    private final LogDiagnosticCatalog catalog;
    LogOperationDiagnostics(ExecutionIdentity execution,LogProducer producer,LogDiagnosticCatalog catalog) {
        this.execution=Objects.requireNonNull(execution);this.producer=Objects.requireNonNull(producer);
        this.catalog=Objects.requireNonNull(catalog);
    }
    /** An adapter failed to produce a diagnostic; retain visible loss in the existing producer status. */
    public LogProducer.Admission reportRejected() {return producer.reject();}
    public Token started(UUID correlation,TraceContext trace,String phase) {
        Objects.requireNonNull(correlation);Objects.requireNonNull(trace);
        if((correlation.getMostSignificantBits()==0 && correlation.getLeastSignificantBits()==0) || !PHASES.contains(phase))
            throw new IllegalArgumentException("INVALID_LOG_OPERATION");
        var token=new Token(this,correlation,trace,phase);
        emit(token,EventCode.OPERATION_STARTED,Severity.TRACE,LifecycleEvent.Outcome.STARTED,null);
        return token;
    }
    /** Call before converting a technical failure; only its bounded sanitized snapshot survives this call. */
    public boolean recordFailure(Token token,Throwable failure) {
        owned(token);Objects.requireNonNull(failure);
        synchronized(token) {
            if(token.terminal || token.observed!=null)return false;
            token.observed=capture(failure);return true;
        }
    }
    public LogProducer.Admission completed(Token token,Throwable failure) {
        owned(token);Observed observed;
        synchronized(token) {
            if(token.terminal)return LogProducer.Admission.DUPLICATE;
            observed=failure==null?null:token.observed==null?capture(failure):token.observed;
            token.observed=null;token.terminal=true;
        }
        boolean cancelled=failure instanceof CancellationException,failed=failure!=null;
        var code=cancelled?EventCode.OPERATION_CANCELLED:failed?EventCode.OPERATION_FAILED:EventCode.OPERATION_SUCCEEDED;
        var outcome=cancelled?LifecycleEvent.Outcome.CANCELLED:failed?LifecycleEvent.Outcome.FAILED:LifecycleEvent.Outcome.SUCCEEDED;
        String error=cancelled?"CANCELLED":failed?observed.sqlFailure()?"DB_UNAVAILABLE":"OPERATION_FAILED":null;
        if(error!=null && !catalog.errorCodes().contains(error))error=null;
        var diagnostics=new LogDiagnostics(null,null,error,observed==null?null:observed.sqlState(),null,
            observed==null?null:observed.captured().failure(),cancelled?new LogDiagnostics.Attributes(null,null,true):null,
            observed==null?LogDiagnostics.Counts.ZERO:observed.captured().counts());
        return emit(token,code,failed?Severity.ERROR:Severity.DEBUG,outcome,diagnostics);
    }
    private Observed capture(Throwable failure) {
        var captured=LogExceptionCapture.capture(failure,catalog);String state=null;
        if(failure instanceof SQLException sql) {
            String value=sql.getSQLState();
            if(value!=null && value.matches("[A-Z0-9]{5}"))state=value;
            else if(value!=null) {
                var counts=captured.counts();captured=new LogExceptionCapture.Captured(captured.failure(),
                    new LogDiagnostics.Counts(counts.redactedFields()+1,counts.truncatedFields(),counts.unregisteredSymbols()));
            }
        }
        return new Observed(captured,state,failure instanceof SQLException);
    }
    private void owned(Token token) {
        if(token==null || token.owner!=this)throw new IllegalArgumentException("INVALID_LOG_OPERATION");
    }
    private LogProducer.Admission emit(Token token,EventCode code,Severity severity,LifecycleEvent.Outcome outcome,LogDiagnostics diagnostics) {
        var metadata=new EventMetadata(UUID.randomUUID(),execution,Instant.now(),severity,code,token.correlation,token.trace);
        return producer.emit(new LifecycleEvent(metadata,token.phase,outcome,
            Math.max(0,(System.nanoTime()-token.openedAt)/1000000),0,1,false,diagnostics));
    }
}
