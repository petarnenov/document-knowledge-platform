package org.documentknowledge.documentmanagement.adapters.outbound.upload;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;

/** Explicit mandatory real144 provider profile. Real SQL/S3/standard Clam/isolated inspector;
 * artificial transport-result barriers consume actual elapsed time under PUBLIC production limits.
 * This proves the cumulative60s service bound, NOT full-service elapsed240s acceptance.
 */
public final class RealCompleteStorageBudgetAcceptance {
 private RealCompleteStorageBudgetAcceptance(){}
 public static void main(String[] args)throws Exception{
  assertEquals(0,args.length);assertEquals("required",System.getenv("DKP_COMPLETE_DURATION_ACCEPTANCE"));assertEquals("Linux",System.getProperty("os.name"));
  try(var f=new RealUploadPersistenceStorageAcceptance.Fixture()){
   f.reset();UUID upload=f.quarantined();var copyEntered=new CountDownLatch(1);var release=new CountDownLatch(1);var copySettled=new CountDownLatch(1);var sourceOpened=new AtomicBoolean();var remoteCopies=new AtomicInteger();
   UploadStoragePort delayed=new UploadStoragePort(){
    public ObjectIdentity put(ObjectKey key,java.nio.file.Path path,long size,String hash){return f.actual.put(key,path,size,hash);}
    public InputStream open(ObjectIdentity object){return f.actual.open(object);}
    public ObjectIdentity copy(ObjectIdentity source,ObjectKey target){throw new AssertionError("COMPLETE must propagate its deadline capability");}
    public List<ObjectIdentity> versions(ObjectKey key){return f.actual.versions(key);}
    public InputStream open(ObjectIdentity object,CompleteBudget.Phase phase){
     var input=f.actual.open(object,phase);
     try{if(sourceOpened.compareAndSet(false,true)){new CountDownLatch(1).await(30,TimeUnit.SECONDS);phase.check();}return input;}
     catch(InterruptedException failure){Thread.currentThread().interrupt();try{input.close();}catch(IOException ignored){}throw new DmFailure(503,"UNAVAILABLE");}
     catch(RuntimeException failure){try{input.close();}catch(IOException ignored){}throw failure;}
    }
    public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace){
     try{copyEntered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException cancellationCannotProveRemoteSettlement){}
     phase.check();remoteCopies.incrementAndGet();return f.actual.copy(source,target,phase,workspace);
     }finally{copySettled.countDown();}
    }
   };
   var app=new UploadService(f.store,f.grants,delayed,f.scanner,f.inspector,Clock.systemUTC(),f.temporary,f.workspaces);
   long start=System.nanoTime();
   try{
    assertEquals(503,assertThrows(DmFailure.class,()->app.complete(f.caller,upload,"cumulative-storage-deadline")).status());
    long elapsedMs=Duration.ofNanos(System.nanoTime()-start).toMillis();assertTrue(elapsedMs>=59000&&elapsedMs<90000,"Actual production shared60s storage limit includes initialread+copy wait; genuine scan/inspection elapsed is additional");
    assertTrue(sourceOpened.get());assertEquals(0,copyEntered.getCount());assertEquals(0,remoteCopies.get());assertEquals(0,f.count("upload_receipts"));assertTrue(f.doc().versions().isEmpty());assertNull(f.doc().activeVersionId());f.fullCharge(1);
    var slot=f.slot(upload);assertNotNull(slot.possibleOriginal());assertFalse(slot.writerSettled());assertTrue(f.actual.versions(slot.possibleOriginal()).isEmpty());
    assertEquals(429,assertThrows(DmFailure.class,()->app.complete(f.caller,upload,"still-executing")).status());
    release.countDown();assertTrue(copySettled.await(5,TimeUnit.SECONDS));assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(f.pending(upload).isEmpty())Thread.onSpinWait();});
    assertEquals(0,remoteCopies.get());assertEquals(0,f.count("upload_receipts"));assertFalse(f.slot(upload).writerSettled());f.fullCharge(1);
    System.out.println("{\"requirement\":\"COMPLETE-SHARED-STORAGE-60\",\"result\":\"PASS\",\"elapsedMs\":"+elapsedMs+",\"realProviders\":true,\"artificialResultDelays\":true,\"lateRemoteCopy\":false,\"retainedCharges\":true}");
   }finally{release.countDown();if(copyEntered.getCount()==0)assertTrue(copySettled.await(5,TimeUnit.SECONDS));}
  }
 }
}
