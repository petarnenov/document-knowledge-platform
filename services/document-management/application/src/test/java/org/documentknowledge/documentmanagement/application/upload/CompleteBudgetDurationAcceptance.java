package org.documentknowledge.documentmanagement.application.upload;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.documentmanagement.application.DmFailure;

/** Explicit four-minute mechanism gate, not a substitute for real PG/S3/scanner service acceptance.
 * Uses the PUBLIC production240s budget, actual monotonic elapsed time and an owned blocked TCP peer.
 * Missing explicit profile FAILS. No shortened runtime limits, injected clocks, sleeps or disabled cases.
 */
public final class CompleteBudgetDurationAcceptance {
 private CompleteBudgetDurationAcceptance(){}
 public static void main(String[] args)throws Exception{
  assertEquals(0,args.length);assertEquals("required",System.getenv("DKP_COMPLETE_DURATION_ACCEPTANCE"));
  var budget=new CompleteBudget(Clock.systemUTC());var entered=new CountDownLatch(1);var settled=new CountDownLatch(1);var later=new AtomicBoolean();
  try(var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var peers=Executors.newVirtualThreadPerTaskExecutor()){
   var peer=peers.submit(()->{try(var socket=listener.accept()){socket.setSoTimeout(245000);entered.countDown();return socket.getInputStream().read();}});
   long start=System.nanoTime();
   var failure=assertThrows(DmFailure.class,()->budget.execute(()->{
    try(var socket=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort())){
     assertTrue(entered.await(2,TimeUnit.SECONDS));socket.getInputStream().read();budget.check();later.set(true);return "late";
    }finally{settled.countDown();}
   }));
   long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();
   assertEquals(503,failure.status());assertEquals("UNAVAILABLE",failure.code());assertTrue(elapsed>=239000&&elapsed<245000,"Production240s whole budget must bound actual exchange");
   assertTrue(settled.await(2,TimeUnit.SECONDS),"Owned virtual worker and socket must actually settle");assertEquals(-1,peer.get(2,TimeUnit.SECONDS));assertFalse(later.get());
   System.out.println("{\"requirement\":\"COMPLETE-TOTAL-240\",\"result\":\"PASS\",\"elapsedMs\":"+elapsed+",\"workerSettled\":true,\"peerObservedEof\":true,\"lateCallback\":false}");
  }
 }
}
