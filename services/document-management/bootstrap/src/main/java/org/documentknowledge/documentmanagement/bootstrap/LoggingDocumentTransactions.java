package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.BeanProvider;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.documentknowledge.documentmanagement.adapters.outbound.persistence.JdbcDocumentStore;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.loggingclient.ApplicationLogRuntime;
import org.documentknowledge.loggingclient.LogOperationDiagnostics;
import org.documentknowledge.observability.TraceContext;

/** Bootstrap-only composition: original diagnostic producer around the existing runtime transaction. */
final class LoggingDocumentTransactions implements DocumentUnitOfWork {
    private final BeanProvider<DataSource> runtime;
    private final LogOperationDiagnostics operations;
    LoggingDocumentTransactions(BeanProvider<DataSource> runtime,ApplicationLogRuntime logging) {
        this.runtime=Objects.requireNonNull(runtime);operations=logging==null?null:logging.operations();
    }
    @Override public <T> T execute(Function<DocumentRepository,T> work) {return execute(DiagnosticContext.fresh(),work);}
    @Override public <T> T execute(DiagnosticContext context,Function<DocumentRepository,T> work) {
        Objects.requireNonNull(context);Objects.requireNonNull(work);
        var token=start(context);
        Consumer<SQLException> observed=failure->{if(token!=null)try{operations.recordFailure(token,failure);}catch(RuntimeException diagnostic){operations.reportRejected();}};
        try {
            if(!runtime.isPresent())throw new DmFailure(503,"UNAVAILABLE");
            T result=new JdbcDocumentStore(runtime.get(),observed).execute(work);
            complete(token,null);return result;
        } catch(DmFailure failure) {complete(token,failure);throw failure;}
        catch(RuntimeException failure) {complete(token,failure);throw new DmFailure(503,"UNAVAILABLE");}
    }
    private LogOperationDiagnostics.Token start(DiagnosticContext context) {
        if(operations==null)return null;
        try {return operations.started(context.correlationId(),new TraceContext(context.traceId(),context.spanId()),"DATABASE");}
        catch(RuntimeException diagnostic) {operations.reportRejected();return null;}
    }
    private void complete(LogOperationDiagnostics.Token token,Throwable failure) {
        if(token!=null)try{operations.completed(token,failure);}catch(RuntimeException diagnostic){operations.reportRejected();}
    }
}
