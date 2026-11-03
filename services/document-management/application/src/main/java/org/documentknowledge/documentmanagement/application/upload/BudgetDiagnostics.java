package org.documentknowledge.documentmanagement.application.upload;

import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;
import static org.documentknowledge.documentmanagement.application.upload.UploadDiagnosticsPort.*;

/** Reports actual local owner decisions; it never grants cancellation or resource settlement authority. */
final class BudgetDiagnostics {
    static final BudgetDiagnostics NONE=new BudgetDiagnostics();
    private final UploadObservations observations;
    private final AtomicBoolean expired=new AtomicBoolean();
    private BudgetDiagnostics(){observations=null;}
    private BudgetDiagnostics(UploadObservations observations){this.observations=observations;}
    BudgetDiagnostics(UploadDiagnosticsPort diagnostics,DiagnosticContext context){observations=new UploadObservations(diagnostics,context);}
    BudgetDiagnostics child(){return observations==null?NONE:new BudgetDiagnostics(observations);}
    void expired(){
        if(observations!=null && expired.compareAndSet(false,true))observations.decision(Operation.DEADLINE,Outcome.FAILED,
            new Facts(null,0L,0,1,false,null,UploadDiagnosticsPort.Error.DEADLINE_EXCEEDED));
    }
    void cancel(){
        if(observations!=null)observations.decision(Operation.CANCELLATION,Outcome.CANCELLED,
            new Facts(null,null,0,1,false,true,UploadDiagnosticsPort.Error.CANCELLED));
    }
    void hook(Runnable hook){
        if(observations==null){hook.run();return;}
        observations.call(Operation.CANCELLATION,()->new Facts(null,null,0,1,false,true,null),observer->{hook.run();return null;});
    }
}
