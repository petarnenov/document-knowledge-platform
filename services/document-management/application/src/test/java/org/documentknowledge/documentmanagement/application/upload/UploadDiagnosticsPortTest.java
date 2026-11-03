package org.documentknowledge.documentmanagement.application.upload;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadDiagnosticsPortTest {
    @Test void faultyObserversAndLossCallbacksDoNotChangeOriginalCallOutcome() {
        var losses=new AtomicInteger();
        var port=UploadDiagnosticsPort.safe((context,operation,facts)->{throw new IllegalStateException("observer");},losses::incrementAndGet);
        var call=port.started(DiagnosticContext.fresh(),UploadDiagnosticsPort.Operation.PROVIDER_SCAN,UploadDiagnosticsPort.Facts.EMPTY);
        call.failure(new IllegalArgumentException("original"));call.completed(UploadDiagnosticsPort.Outcome.FAILED,new IllegalArgumentException(),UploadDiagnosticsPort.Facts.EMPTY);
        assertEquals(1,losses.get());
        var unavailable=UploadDiagnosticsPort.safe((context,operation,facts)->null,()->{throw new IllegalStateException("loss counter unavailable");});
        assertDoesNotThrow(()->unavailable.started(DiagnosticContext.fresh(),UploadDiagnosticsPort.Operation.CLEANUP,UploadDiagnosticsPort.Facts.EMPTY));
    }
    @Test void callbackFailuresAreCountedAndOnlyOneConcurrentCoordinatorCompletes() throws Exception {
        var notes=new AtomicInteger();var terminals=new AtomicInteger();var losses=new AtomicInteger();
        var context=DiagnosticContext.fresh();
        var port=UploadDiagnosticsPort.safe((actual,operation,facts)->{
            assertSame(context,actual);
            return new UploadDiagnosticsPort.Observation() {
                public void failure(Throwable failure) {notes.incrementAndGet();throw new IllegalStateException("capture refused");}
                public void completed(UploadDiagnosticsPort.Outcome outcome,Throwable failure,UploadDiagnosticsPort.Facts facts) {
                    terminals.incrementAndGet();throw new IllegalStateException("queue refused");
                }
            };
        },losses::incrementAndGet);
        var observation=port.started(context,UploadDiagnosticsPort.Operation.PROVIDER_SCAN,UploadDiagnosticsPort.Facts.EMPTY);
        observation.failure(new java.net.ConnectException());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<?>>();
            for(int index=0;index<16;index++)tasks.add(executor.submit(()->observation.completed(UploadDiagnosticsPort.Outcome.SUCCEEDED,null,UploadDiagnosticsPort.Facts.EMPTY)));
            for(var task:tasks)task.get();
        }
        observation.failure(new IllegalStateException("late"));
        assertEquals(1,notes.get());assertEquals(1,terminals.get());assertEquals(2,losses.get());
        var noOp=UploadDiagnosticsPort.NOOP.started(context,UploadDiagnosticsPort.Operation.CLEANUP,UploadDiagnosticsPort.Facts.EMPTY);
        assertDoesNotThrow(()->{noOp.failure(new IllegalStateException());noOp.completed(UploadDiagnosticsPort.Outcome.UNKNOWN,null,UploadDiagnosticsPort.Facts.EMPTY);});
    }
    @Test void factsUseOnlyExistingBoundedWireFields() {
        assertNotNull(new UploadDiagnosticsPort.Facts(100,0L,0,0,false,false,null));
        assertNotNull(new UploadDiagnosticsPort.Facts(599,9_007_199_254_740_991L,9_007_199_254_740_991L,9_007_199_254_740_991L,true,true,UploadDiagnosticsPort.Error.CLEANUP_FAILED));
        for(Integer status:new Integer[]{99,600})assertThrows(IllegalArgumentException.class,()->new UploadDiagnosticsPort.Facts(status,null,0,1,false,null,null));
        for(Long deadline:new Long[]{-1L,9_007_199_254_740_992L})assertThrows(IllegalArgumentException.class,()->new UploadDiagnosticsPort.Facts(null,deadline,0,1,false,null,null));
        for(long number:new long[]{-1,9_007_199_254_740_992L}) {
            assertThrows(IllegalArgumentException.class,()->new UploadDiagnosticsPort.Facts(null,null,number,1,false,null,null));
            assertThrows(IllegalArgumentException.class,()->new UploadDiagnosticsPort.Facts(null,null,0,number,false,null,null));
        }
        assertThrows(NullPointerException.class,()->UploadDiagnosticsPort.safe(null,()->{}));
        assertThrows(NullPointerException.class,()->UploadDiagnosticsPort.safe(UploadDiagnosticsPort.NOOP,null));
    }
    @Test void technicalStatusAndCancelCallbacksAreBoundedNonThrowingAndStopAtTerminal() {
        var recorder=new RecordingUploadDiagnostics();var context=DiagnosticContext.fresh();
        var port=UploadDiagnosticsPort.safe(recorder,recorder::rejected);
        var observation=port.started(context,UploadDiagnosticsPort.Operation.PROVIDER_STORAGE_PUT,UploadDiagnosticsPort.Facts.EMPTY);
        UploadDiagnosticsPort.status(observation,0);assertNull(recorder.entries.getFirst().status);
        UploadDiagnosticsPort.status(observation,429);assertEquals(429,recorder.entries.getFirst().status);
        UploadDiagnosticsPort.cancel(observation);assertTrue(recorder.entries.getFirst().cancelRequested);
        var failure=new IllegalStateException();UploadDiagnosticsPort.note(observation,failure);assertSame(failure,recorder.entries.getFirst().failure);
        observation.completed(UploadDiagnosticsPort.Outcome.UNKNOWN,null,UploadDiagnosticsPort.Facts.EMPTY);
        observation.status(500);observation.cancelRequested();assertEquals(429,recorder.entries.getFirst().status);
        var faulty=new UploadDiagnosticsPort.FailureObserver(){public void failure(Throwable failure){throw new IllegalStateException();}public void status(int status){throw new IllegalStateException();}public void cancelRequested(){throw new IllegalStateException();}};
        assertDoesNotThrow(()->{UploadDiagnosticsPort.note(faulty,failure);UploadDiagnosticsPort.status(faulty,500);UploadDiagnosticsPort.cancel(faulty);});
        var throwing=UploadDiagnosticsPort.safe((c,o,f)->new UploadDiagnosticsPort.Observation(){public void failure(Throwable failure){}public void status(int status){throw new IllegalStateException();}public void cancelRequested(){throw new IllegalStateException();}public void completed(UploadDiagnosticsPort.Outcome outcome,Throwable failure,UploadDiagnosticsPort.Facts facts){}},recorder::rejected);
        var active=throwing.started(context,UploadDiagnosticsPort.Operation.CLEANUP,UploadDiagnosticsPort.Facts.EMPTY);active.status(500);active.cancelRequested();port.rejected();assertEquals(3,recorder.rejected.get());
    }
}
