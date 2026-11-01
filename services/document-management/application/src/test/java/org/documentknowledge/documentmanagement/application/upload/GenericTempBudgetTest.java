package org.documentknowledge.documentmanagement.application.upload;

import java.io.InputStream;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericTempBudgetTest {
 static class TestClock extends Clock{Instant value=Instant.parse("2026-11-01T00:00:00Z");void advance(long seconds){value=value.plusSeconds(seconds);}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}}
 @Test void exactOriginalTransferAndPreappendBoundariesNeverExtendOnProgress(){
  for(var role:GenericTempPort.Role.values()){
   var clock=new TestClock();var budget=new GenericTempBudget(clock,role);long limit=role==GenericTempPort.Role.TRANSFER?300:60;
   clock.advance(limit-1);budget.check();assertTrue(budget.remaining().compareTo(Duration.ofSeconds(1))<=0);clock.advance(1);assertEquals(503,assertThrows(DmFailure.class,budget::check).status());clock.advance(1);assertThrows(DmFailure.class,budget::check);
  }
 }
 @Test void transferIdleIsCumulativeSinceActualProgressAndDoesNotApplyAsANewPreappendLimit(){
  var clock=new TestClock();var transfer=new GenericTempBudget(clock,GenericTempPort.Role.TRANSFER);
  try(var reading=transfer.reading(InputStream.nullInputStream())){clock.advance(29);transfer.check();reading.progress();clock.advance(29);transfer.check();clock.advance(1);assertThrows(DmFailure.class,transfer::check);}
  var preClock=new TestClock();var pre=new GenericTempBudget(preClock,GenericTempPort.Role.PREAPPEND);try(var reading=pre.reading(InputStream.nullInputStream())){preClock.advance(59);pre.check();preClock.advance(1);assertThrows(DmFailure.class,pre::check);}
 }
 @Test void coordinatorTimeoutRevokesProgressButNeverCallsTheWorkerSettled(){
  var budget=new GenericTempBudget(Clock.systemUTC(),System::nanoTime,Duration.ofMillis(500));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var settled=new CountDownLatch(1);var aborted=new CountDownLatch(1);var success=new AtomicBoolean();
  try{
   assertEquals(503,assertThrows(DmFailure.class,()->budget.execute(()->{try(var cancel=budget.onCancel(aborted::countDown)){entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}budget.check();success.set(true);return true;}finally{settled.countDown();}})).status());
   assertEquals(0,entered.getCount());assertTrue(aborted.await(2,TimeUnit.SECONDS));assertEquals(1,settled.getCount());assertFalse(success.get());assertThrows(DmFailure.class,budget::check);
  }catch(InterruptedException failure){throw new AssertionError(failure);}finally{release.countDown();try{assertTrue(settled.await(2,TimeUnit.SECONDS));}catch(InterruptedException failure){throw new AssertionError(failure);}}
 }
 @Test void recoveryHasFixedSixtySecondsAndCannotExtendOrOutliveItsParent(){
  var clock=new TestClock();var transfer=new GenericTempBudget(clock,GenericTempPort.Role.TRANSFER);clock.advance(250);var remaining=transfer.recovery();assertTrue(remaining.remaining().compareTo(Duration.ofSeconds(50))<=0);clock.advance(49);remaining.check();clock.advance(1);assertThrows(DmFailure.class,remaining::check);
  var otherClock=new TestClock();var other=new GenericTempBudget(otherClock,GenericTempPort.Role.TRANSFER);var child=other.recovery();otherClock.advance(59);child.check();otherClock.advance(1);assertThrows(DmFailure.class,child::check);other.check();
  var live=new GenericTempBudget(new TestClock(),GenericTempPort.Role.TRANSFER);var canceled=live.recovery();live.cancel();assertThrows(DmFailure.class,canceled::check);
 }
 @Test void removedHooksNeverAbortAnotherInvocationAndFailuresKeepTheirOriginalDomain(){
  var budget=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER);var calls=new AtomicInteger();var removed=budget.onCancel(calls::incrementAndGet);removed.close();budget.cancel();budget.cancel();assertEquals(0,calls.get());
  var domain=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.PREAPPEND);assertEquals(404,assertThrows(DmFailure.class,()->domain.execute(()->{throw new DmFailure(404,"NOT_FOUND");})).status());
  var error=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.PREAPPEND);assertThrows(AssertionError.class,()->error.execute(()->{throw new AssertionError("test-only worker fault");}));
 }
}
