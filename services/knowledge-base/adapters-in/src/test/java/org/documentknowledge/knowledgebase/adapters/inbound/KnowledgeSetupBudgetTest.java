package org.documentknowledge.knowledgebase.adapters.inbound;

import java.time.Duration;
import java.util.concurrent.*;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSetupBudgetTest {
    @Test void nestedCurrentAuthorityCallsShareMeterAndAlwaysRestoreThreadContext(){
        try(var timer=Executors.newSingleThreadScheduledExecutor();var budget=new KnowledgeSetupBudget(timer,Deadline.request())){
            budget.identityComplete();assertNull(KnowledgeSetupBudget.current());
            assertEquals("value",budget.call(()->{assertSame(budget,KnowledgeSetupBudget.current());return budget.call(()->"value");}));
            assertNull(KnowledgeSetupBudget.current());
            assertThrows(IllegalStateException.class,()->budget.call(()->{throw new IllegalStateException();}));
            assertNull(KnowledgeSetupBudget.current());budget.run(()->{});
        }
    }
    @Test void authenticationHasActiveParentCappedTimerAndLateCallsCannotReviveOwner()throws Exception{
        try(var timer=Executors.newSingleThreadScheduledExecutor();var budget=new KnowledgeSetupBudget(timer,Deadline.request().child(Duration.ofMillis(50)))){
            budget.expiration().get(1,TimeUnit.SECONDS);
            assertThrows(KbFailure.class,budget::identityComplete);assertThrows(KbFailure.class,()->budget.call(()->true));
        }
    }
    @Test void closedBudgetCannotAdmitWorkAndCancelledAuthenticationAlarmDoesNotFireDuringBody()throws Exception{
        try(var timer=Executors.newSingleThreadScheduledExecutor()){
            var deadline=Deadline.request();var budget=new KnowledgeSetupBudget(timer,deadline);budget.identityComplete();
            assertFalse(budget.expiration().isDone());budget.close();budget.close();
            assertThrows(KbFailure.class,()->budget.call(()->true));deadline.check();
        }
    }
    @Test void oneBudgetCannotBeUsedConcurrentlyByTwoAuthorityWorkers()throws Exception{
        try(var timer=Executors.newSingleThreadScheduledExecutor();var executor=Executors.newSingleThreadExecutor();var budget=new KnowledgeSetupBudget(timer,Deadline.request())){
            budget.identityComplete();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var first=executor.submit(()->budget.call(()->{entered.countDown();try{release.await();}catch(InterruptedException failure){throw new AssertionError(failure);}return true;}));
            assertTrue(entered.await(1,TimeUnit.SECONDS));try{assertThrows(KbFailure.class,()->budget.call(()->false));}finally{release.countDown();}assertTrue(first.get(1,TimeUnit.SECONDS));
        }
    }
    @Test void activeAuthorityTimeoutInterruptsWorkerAndInvalidatesWholeDeadline()throws Exception{
        try(var timer=Executors.newSingleThreadScheduledExecutor();var executor=Executors.newSingleThreadExecutor()){
            var whole=Deadline.request().child(Duration.ofMillis(100));try(var budget=new KnowledgeSetupBudget(timer,whole)){
                budget.identityComplete();var entered=new CountDownLatch(1);
                var first=executor.submit(()->budget.call(()->{entered.countDown();try{new CountDownLatch(1).await();throw new AssertionError();}catch(InterruptedException expected){return true;}}));
                assertTrue(entered.await(1,TimeUnit.SECONDS));budget.expiration().get(1,TimeUnit.SECONDS);
                assertInstanceOf(KbFailure.class,assertThrows(ExecutionException.class,()->first.get(1,TimeUnit.SECONDS)).getCause());assertThrows(KbFailure.class,whole::check);
            }
        }
    }
}
