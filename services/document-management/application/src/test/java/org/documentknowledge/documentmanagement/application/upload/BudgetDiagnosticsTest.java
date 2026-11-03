package org.documentknowledge.documentmanagement.application.upload;

import java.time.Duration;
import java.util.concurrent.*;
import org.documentknowledge.documentmanagement.application.*;
import org.junit.jupiter.api.Test;
import static org.documentknowledge.documentmanagement.application.upload.UploadDiagnosticsPort.*;
import static org.junit.jupiter.api.Assertions.*;

class BudgetDiagnosticsTest {
    @Test void observationCannotExpireTheBudgetAndActualExpiryIsReportedOncePerOwner() {
        var time=new CompleteBudgetTest.Time();var context=DiagnosticContext.fresh();var recorder=new RecordingUploadDiagnostics();
        var budget=new CompleteBudget(time,context,recorder);
        try(var phase=budget.phase(CompleteBudget.Kind.STORAGE)) {
            assertTrue(phase.diagnosticRemainingMs()>0);time.advance(Duration.ofSeconds(60));
            assertEquals(0,phase.diagnosticRemainingMs());assertTrue(recorder.entries.isEmpty());
            assertThrows(DmFailure.class,phase::check);assertThrows(DmFailure.class,phase::check);
            assertEquals(1,recorder.entries.size());var entry=recorder.entries.getFirst();
            assertSame(context,entry.context);assertEquals(Operation.DEADLINE,entry.operation);assertEquals(Outcome.FAILED,entry.outcome);
            assertEquals(UploadDiagnosticsPort.Error.DEADLINE_EXCEEDED,entry.facts.error());assertEquals(0,entry.facts.remainingDeadlineMs());assertNull(entry.facts.cancelRequested());
        }
        assertEquals(1,recorder.entries.size());
    }
    @Test void genericRecoveryRetainsContextAndEachActualOwnerExpiryRatherThanInventingCancellation() {
        var time=new GenericTempBudgetTest.TestClock();var context=DiagnosticContext.fresh();var recorder=new RecordingUploadDiagnostics();
        var parent=new GenericTempBudget(time,GenericTempPort.Role.TRANSFER,context,recorder);var child=parent.recovery();
        assertTrue(parent.diagnosticRemainingMs()>60000);assertTrue(child.diagnosticRemainingMs()<=60000);
        time.advance(60);assertEquals(0,child.diagnosticRemainingMs());assertTrue(recorder.entries.isEmpty());
        assertThrows(DmFailure.class,child::check);parent.check();assertEquals(1,recorder.entries.size());
        time.advance(240);assertThrows(DmFailure.class,parent::check);assertEquals(2,recorder.entries.size());
        for(var entry:recorder.entries){assertSame(context,entry.context);assertEquals(Operation.DEADLINE,entry.operation);assertNull(entry.facts.cancelRequested());}
    }
    @Test void cancellationRecordsTheActualHookAndKeepsItsOriginalInvocationScope()throws Exception {
        var context=DiagnosticContext.fresh();var recorder=new RecordingUploadDiagnostics();var time=new CompleteBudgetTest.Time();
        var budget=new CompleteBudget(time,context,recorder);var ran=new CountDownLatch(1);
        try(var phase=budget.phase(CompleteBudget.Kind.STORAGE);var hook=phase.onCancel(ran::countDown)) {
            budget.cancel();budget.cancel();assertTrue(ran.await(2,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(recorder.entries.stream().filter(e->e.outcome!=null).count()<2 && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(2,recorder.entries.size());
            assertEquals(1,recorder.entries.stream().filter(e->e.outcome==Outcome.CANCELLED).count());
            assertEquals(1,recorder.entries.stream().filter(e->e.outcome==Outcome.SUCCEEDED).count());
            for(var entry:recorder.entries){assertSame(context,entry.context);assertEquals(Operation.CANCELLATION,entry.operation);assertEquals(true,entry.facts.cancelRequested());assertFalse(entry.facts.cleanupSettled());}
        }
        var generic=new GenericTempBudget(time,GenericTempPort.Role.PREAPPEND,context,recorder);var failed=new CountDownLatch(1);
        try(var hook=generic.onCancel(()->{failed.countDown();throw new IllegalStateException("owned hook failed");})) {
            generic.cancel();assertTrue(failed.await(2,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(recorder.entries.stream().noneMatch(e->e.outcome==Outcome.FAILED) && System.nanoTime()<deadline)Thread.sleep(5);
            assertTrue(recorder.entries.stream().anyMatch(e->e.outcome==Outcome.FAILED));
        }
        assertThrows(DmFailure.class,generic::check);
    }
}
