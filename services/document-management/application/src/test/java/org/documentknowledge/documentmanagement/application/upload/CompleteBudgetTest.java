package org.documentknowledge.documentmanagement.application.upload;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.documentknowledge.documentmanagement.application.DmFailure;

class CompleteBudgetTest {
 static final class Time extends Clock {
  Instant value=Instant.parse("2026-11-01T00:00:00Z");
  public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public synchronized Instant instant(){return value;}
  synchronized void advance(Duration d){value=value.plus(d);}
 }
 @Test void wholeDeadlineRejectsEqualityAndClockRollbackCannotExtendMonotonicBudget(){
  var clock=new Time();var nano=new AtomicLong();var b=new CompleteBudget(clock,nano::get,Duration.ofSeconds(240),Duration.ofSeconds(60));
  clock.advance(Duration.ofSeconds(240).minusNanos(1));b.check();clock.advance(Duration.ofNanos(1));assertThrows(DmFailure.class,b::check);
  var other=new CompleteBudget(new Time(),nano::get,Duration.ofSeconds(240),Duration.ofSeconds(60));nano.set(Duration.ofSeconds(240).toNanos());assertThrows(DmFailure.class,other::check);
 }
 @Test void storageHasOneCumulativeBudgetAcrossAllPhasesAndScanDoesNotResetIt(){
  var t=new Time();var b=new CompleteBudget(t);
  try(var p=b.phase(CompleteBudget.Kind.STORAGE)){t.advance(Duration.ofSeconds(20));p.check();}
  try(var p=b.phase(CompleteBudget.Kind.SCAN)){t.advance(Duration.ofSeconds(100));p.check();}
  try(var p=b.phase(CompleteBudget.Kind.STORAGE)){t.advance(Duration.ofSeconds(39).plusNanos(999999999));p.check();t.advance(Duration.ofNanos(1));assertThrows(DmFailure.class,p::check);}
  assertThrows(DmFailure.class,()->b.phase(CompleteBudget.Kind.INSPECT));
 }
 @Test void scanAndInspectionEachHaveIndependentInclusiveRejectionBoundaries(){
  for(var kind:new CompleteBudget.Kind[]{CompleteBudget.Kind.SCAN,CompleteBudget.Kind.INSPECT}){
   var t=new Time();var b=new CompleteBudget(t);
   try(var p=b.phase(kind)){t.advance(Duration.ofSeconds(kind==CompleteBudget.Kind.SCAN?120:60));assertThrows(DmFailure.class,p::check);}
  }
 }
 @Test void timeoutClosesOnlyInvocationAndRetainsWorkerOwnershipUntilActualSettlement()throws Exception{
  var b=new CompleteBudget(Clock.systemUTC(),System::nanoTime,Duration.ofMillis(120),Duration.ofMillis(80));
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var settled=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var later=new AtomicBoolean();
  long start=System.nanoTime();
  assertThrows(DmFailure.class,()->b.execute(()->{
   try(var p=b.phase(CompleteBudget.Kind.STORAGE);var registration=p.onCancel(cancelled::countDown)){
    entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
    p.check();later.set(true);return "late";
   }finally{settled.countDown();}
  }));
  assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(cancelled.await(1,TimeUnit.SECONDS));assertFalse(later.get());assertEquals(1,settled.getCount());assertTrue(System.nanoTime()-start<Duration.ofSeconds(2).toNanos());
  release.countDown();assertTrue(settled.await(1,TimeUnit.SECONDS));assertFalse(later.get());
 }
 @Test void successfulAndFailedWorkersSettleWithoutCancellingOtherInvocation(){
  var b=new CompleteBudget(Clock.systemUTC());var cancelled=new AtomicBoolean();
  assertEquals("ok",b.execute(()->{try(var p=b.phase(CompleteBudget.Kind.STORAGE);var r=p.onCancel(()->cancelled.set(true))){return "ok";}}));assertFalse(cancelled.get());
  var failure=new IllegalArgumentException("test");assertSame(failure,assertThrows(IllegalArgumentException.class,()->new CompleteBudget(Clock.systemUTC()).execute(()->{throw failure;})));
 }
 @Test void registrationsCannotCancelClosedOrSubsequentInvocationAndScopesCannotOverlap(){
  var b=new CompleteBudget(Clock.systemUTC());var closed=new AtomicBoolean();
  try(var p=b.phase(CompleteBudget.Kind.STORAGE)){
   assertThrows(IllegalStateException.class,()->b.phase(CompleteBudget.Kind.SCAN));var hook=p.onCancel(()->closed.set(true));hook.close();hook.close();
  }
  b.cancel();b.cancel();assertFalse(closed.get());assertThrows(DmFailure.class,()->b.phase(CompleteBudget.Kind.SCAN));
 }
 @Test void inactiveScopeAndAlreadyExecutedBudgetCannotBeReused(){
  var b=new CompleteBudget(Clock.systemUTC());var p=b.phase(CompleteBudget.Kind.STORAGE);assertTrue(p.remaining().compareTo(Duration.ZERO)>0);p.close();p.close();assertThrows(DmFailure.class,p::check);assertThrows(DmFailure.class,()->p.onCancel(()->{}));
  assertEquals(1,b.execute(()->1));assertThrows(IllegalStateException.class,()->b.execute(()->2));
 }
 @Test void checkedFailureAndErrorRemainFailClosed(){
  assertThrows(DmFailure.class,()->new CompleteBudget(Clock.systemUTC()).execute(()->{throw new java.io.IOException("private");}));
  var fatal=new AssertionError("fixture");assertSame(fatal,assertThrows(AssertionError.class,()->new CompleteBudget(Clock.systemUTC()).execute(()->{throw fatal;})));
 }
 @Test void interruptedCoordinatorCancelsOnlyOwnedPhaseAndPreservesInterruptStatus()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);var status=new AtomicBoolean();var failure=new AtomicReference<Throwable>();var b=new CompleteBudget(Clock.systemUTC());
  var caller=Thread.ofVirtual().start(()->{try{assertThrows(DmFailure.class,()->b.execute(()->{try(var p=b.phase(CompleteBudget.Kind.SCAN)){entered.countDown();release.await();p.check();return 1;}}));status.set(Thread.currentThread().isInterrupted());}catch(Throwable t){failure.set(t);}finally{done.countDown();}});
  assertTrue(entered.await(1,TimeUnit.SECONDS));caller.interrupt();assertTrue(done.await(1,TimeUnit.SECONDS));release.countDown();assertNull(failure.get());assertTrue(status.get());
 }

}
