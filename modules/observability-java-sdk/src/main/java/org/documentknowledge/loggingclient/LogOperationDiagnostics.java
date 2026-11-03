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
    private static final Set<String> PHASES=Set.of("OPERATION","DATABASE","PROVIDER","RETRY","DEADLINE","CANCELLATION","CLEANUP",
        "PROVIDER_STORAGE_PUT","PROVIDER_STORAGE_OPEN","PROVIDER_STORAGE_READ","PROVIDER_STORAGE_CLOSE",
        "PROVIDER_STORAGE_COPY","PROVIDER_STORAGE_VERSIONS","PROVIDER_STORAGE_DELETE","PROVIDER_SCAN","PROVIDER_INSPECT");
    /** Closed existing-v1 facts. A policy limit uses count; no arbitrary attributes are accepted. */
    public record Facts(Integer status,Long remainingDeadlineMs,LogDiagnosticCatalog.Dependency dependency,
                        long attempt,long count,boolean cleanupSettled,Boolean cancelRequested,String errorCode) {
        public static final Facts EMPTY=new Facts(null,null,null,0,1,false,null,null);
        public Facts {
            new LogDiagnostics(status,remainingDeadlineMs,errorCode,null,dependency,null,
                new LogDiagnostics.Attributes(null,null,cancelRequested),LogDiagnostics.Counts.ZERO);
            LogDiagnosticCatalog.require(attempt>=0 && attempt<=LifecycleEvent.MAX_INTEGER
                && count>=0 && count<=LifecycleEvent.MAX_INTEGER);
        }
    }
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
        return started(correlation,trace,phase,Facts.EMPTY);
    }
    public Token started(UUID correlation,TraceContext trace,String phase,Facts facts) {
        Objects.requireNonNull(correlation);Objects.requireNonNull(trace);
        if((correlation.getMostSignificantBits()==0 && correlation.getLeastSignificantBits()==0) || !PHASES.contains(phase))
            throw new IllegalArgumentException("INVALID_LOG_OPERATION");
        validate(facts);var token=new Token(this,correlation,trace,phase);
        emit(token,EventCode.OPERATION_STARTED,Severity.TRACE,LifecycleEvent.Outcome.STARTED,diagnostics(facts,null,null),facts);
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
        return completed(token,failure,failure instanceof CancellationException?LifecycleEvent.Outcome.CANCELLED:
            failure==null?LifecycleEvent.Outcome.SUCCEEDED:LifecycleEvent.Outcome.FAILED,Facts.EMPTY);
    }
    /** The caller supplies observed outcomes; UNKNOWN is incomplete consumption, never invented failure/cancellation. */
    public LogProducer.Admission completed(Token token,Throwable failure,LifecycleEvent.Outcome outcome,Facts facts) {
        owned(token);Observed observed;
        Objects.requireNonNull(outcome);validate(facts);
        LogDiagnosticCatalog.require(outcome!=LifecycleEvent.Outcome.STARTED
            && (outcome!=LifecycleEvent.Outcome.SUCCEEDED || failure==null));
        boolean failed=outcome==LifecycleEvent.Outcome.FAILED,cancelled=outcome==LifecycleEvent.Outcome.CANCELLED;
        synchronized(token) {
            if(token.terminal)return LogProducer.Admission.DUPLICATE;
            observed=outcome==LifecycleEvent.Outcome.SUCCEEDED?null:token.observed!=null?token.observed:failure==null?null:capture(failure);
            token.observed=null;token.terminal=true;
        }
        var code=cancelled?EventCode.OPERATION_CANCELLED:outcome==LifecycleEvent.Outcome.SUCCEEDED?
            EventCode.OPERATION_SUCCEEDED:EventCode.OPERATION_FAILED;
        String error=facts.errorCode()!=null?facts.errorCode():cancelled?"CANCELLED":failed?
            observed!=null && observed.sqlFailure()?"DB_UNAVAILABLE":"OPERATION_FAILED":null;
        if(error!=null && !catalog.errorCodes().contains(error))error=null;
        var terminalFacts=cancelled?new Facts(facts.status(),facts.remainingDeadlineMs(),facts.dependency(),
            facts.attempt(),facts.count(),facts.cleanupSettled(),true,facts.errorCode()):facts;
        return emit(token,code,failed||cancelled?Severity.ERROR:Severity.DEBUG,outcome,
            diagnostics(terminalFacts,observed,error),terminalFacts);
    }
    /** Exact original tuple only; missing remote/provider metadata stays unknown. */
    public LogDiagnosticCatalog.Dependency dependency(String name) {return catalog.dependencies().get(Objects.requireNonNull(name));}
    private void validate(Facts facts) {
        Objects.requireNonNull(facts);
        LogDiagnosticCatalog.require(facts.dependency()==null || facts.dependency().equals(catalog.dependencies().get(facts.dependency().name())));
        LogDiagnosticCatalog.require(facts.errorCode()==null || catalog.errorCodes().contains(facts.errorCode()));
    }
    private LogDiagnostics diagnostics(Facts facts,Observed observed,String error) {
        return new LogDiagnostics(facts.status(),facts.remainingDeadlineMs(),error,observed==null?null:observed.sqlState(),facts.dependency(),
            observed==null?null:observed.captured().failure(),new LogDiagnostics.Attributes(null,null,facts.cancelRequested()),
            observed==null?LogDiagnostics.Counts.ZERO:observed.captured().counts());
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
    private LogProducer.Admission emit(Token token,EventCode code,Severity severity,LifecycleEvent.Outcome outcome,LogDiagnostics diagnostics,Facts facts) {
        var metadata=new EventMetadata(UUID.randomUUID(),execution,Instant.now(),severity,code,token.correlation,token.trace);
        return producer.emit(new LifecycleEvent(metadata,token.phase,outcome,
            Math.max(0,(System.nanoTime()-token.openedAt)/1000000),facts.attempt(),facts.count(),facts.cleanupSettled(),diagnostics));
    }
}
