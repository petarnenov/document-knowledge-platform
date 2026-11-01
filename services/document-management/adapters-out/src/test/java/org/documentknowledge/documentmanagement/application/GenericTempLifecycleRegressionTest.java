package org.documentknowledge.documentmanagement.application;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.adapters.outbound.generictemp.RealGenericTempFixture;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.documentknowledge.documentmanagement.domain.upload.UploadState;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.documentknowledge.identity.*;
import org.junit.jupiter.api.*;

/** Real POSIX files + production services/manager; SQL/S3/clock/scanners are explicit application-unit fixtures. */
class GenericTempLifecycleRegressionTest {
 Path root;UploadLifecycleTest f;
 @BeforeEach void setup()throws Exception{
  Path parent=Path.of("build/generic-service-private").toAbsolutePath();Files.createDirectories(parent);Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));root=Files.createTempDirectory(parent,"owned-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  Files.writeString(root.resolve(".spool-authority"),"dkp-private-spool-v1\n");Files.setPosixFilePermissions(root.resolve(".spool-authority"),PosixFilePermissions.fromString("rw-------"));f=new UploadLifecycleTest();f.temporary=root;f.genericTemp=RealGenericTempFixture.open(root);f.create();
 }
 @AfterEach void restore()throws Exception{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));f.closeContent();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
 void deny()throws IOException{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("r-x------"));}
 void allow()throws IOException{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));}
 long bytes()throws IOException{try(var paths=Files.list(root)){return paths.filter(p->p.getFileName().toString().startsWith("dkp-transfer-")||p.getFileName().toString().startsWith("dkp-preappend-")).mapToLong(p->{try{return Files.size(p);}catch(IOException error){throw new UncheckedIOException(error);}}).sum();}}
 InputStream denyAtEof(){return new ByteArrayInputStream(f.bytes()){@Override public int read(byte[] b,int off,int len){int n=super.read(b,off,len);if(n<0)try{deny();}catch(IOException failure){throw new UncheckedIOException(failure);}return n;}};}
 @Test void partialTransferFailureRemainsOwnedAfterRemoteExpiryAndIsDeletedBeforeAnotherRead()throws Exception{
  int partial=f.bytes().length/2;
  for(int generation=0;generation<3;generation++){
   var app=f.uploads();UUID upload=f.admitted(app,UploadFormat.PDF);var read=new AtomicBoolean();
   InputStream broken=new InputStream(){boolean first=true;public int read(){throw new AssertionError("bulk only");}public int read(byte[] b,int off,int len)throws IOException{if(first){first=false;assertEquals(0,bytes(),"Previous retained payload must be gone BEFORE new body read");read.set(true);System.arraycopy(f.bytes(),0,b,off,partial);return partial;}deny();throw new IOException("test-only input failure");}};
   try{f.failure(503,"UNAVAILABLE",()->app.transfer(f.caller,upload,"partial",broken));}finally{allow();}
   assertTrue(read.get());assertEquals(partial,bytes());assertEquals(UploadState.INITIALIZED,f.store.uploads.slot(f.caller.tenant(),upload).orElseThrow().state());assertTrue(f.storage.objects.isEmpty());
   f.clock.advance(3601);f.cleanup().expire(f.caller.tenant(),upload);assertEquals(0,f.store.uploads.global.admittedCount());assertEquals(0,f.store.uploads.global.transientBytes());assertEquals(partial,bytes());
  }
  f.genericTemp.close();f.genericTemp=RealGenericTempFixture.open(root);assertTrue(f.genericTemp.ready());assertEquals(0,bytes());
 }
 @Test void actualTransferCallerInterruptCannotReleaseTheStillExecutableLocalReader()throws Exception{
  var app=f.uploads();UUID upload=f.admitted(app,UploadFormat.PDF);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var closeRequested=new java.util.concurrent.CountDownLatch(1);var result=new java.util.concurrent.atomic.AtomicReference<Throwable>();var reads=new java.util.concurrent.atomic.AtomicInteger();
  InputStream input=new InputStream(){boolean first=true;public int read(){throw new AssertionError();}public int read(byte[] b,int off,int len){reads.incrementAndGet();if(first){first=false;System.arraycopy(f.bytes(),0,b,off,3);return 3;}entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return -1;}public void close(){closeRequested.countDown();}};
  Thread caller=Thread.ofVirtual().start(()->{try{app.transfer(f.caller,upload,"caller-abort",input);}catch(Throwable failure){result.set(failure);}});
  try{assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(3,bytes());caller.interrupt();caller.join(2000);assertFalse(caller.isAlive());assertEquals(503,assertInstanceOf(DmFailure.class,result.get()).status());assertTrue(closeRequested.await(2,java.util.concurrent.TimeUnit.SECONDS));assertFalse(f.genericTemp.ready());f.genericTemp.close();assertEquals(503,assertThrows(DmFailure.class,()->RealGenericTempFixture.open(root)).status());assertEquals(3,bytes());}
  finally{release.countDown();caller.join(3000);}
  final java.util.concurrent.atomic.AtomicReference<GenericTempPort> replacement=new java.util.concurrent.atomic.AtomicReference<>();assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->{while(replacement.get()==null)try{replacement.set(RealGenericTempFixture.open(root));}catch(DmFailure stillActive){Thread.onSpinWait();}});f.genericTemp=replacement.get();assertTrue(f.genericTemp.ready());assertEquals(0,bytes());assertEquals(2,reads.get());assertTrue(f.storage.objects.isEmpty());assertTrue(f.store.uploads.outcomes.values().stream().noneMatch(outcome->outcome.key().operation().equals("upload.transfer")));
 }
 @Test void knownRemotePutAndFailedLocalCleanupKeepIndependentReservationsAndNoNewSuccess()throws Exception{
  var app=f.uploads();UUID upload=f.admitted(app,UploadFormat.PDF);f.storage.onPut=()->{try{deny();}catch(IOException failure){throw new UncheckedIOException(failure);}};
  try{f.failure(503,"UNAVAILABLE",()->app.transfer(f.caller,upload,"local-delete-failed",new ByteArrayInputStream(f.bytes())));}finally{allow();}
  var slot=f.store.uploads.slot(f.caller.tenant(),upload).orElseThrow();assertTrue(slot.writerSettled());var pending=f.store.uploads.pendingCleanup(f.caller.tenant(),upload);assertEquals(1,pending.size());assertNotNull(pending.getFirst().object());assertEquals(f.storage.objects.keySet(),Set.of(pending.getFirst().object()));assertEquals(f.bytes().length,bytes());assertEquals(1,f.storage.objects.size());assertTrue(f.store.uploads.outcomes.values().stream().noneMatch(o->o.key().operation().equals("upload.transfer")));
  f.clock.advance(3601);f.cleanup().expire(f.caller.tenant(),upload);for(var work:List.copyOf(f.store.uploads.pendingCleanup(f.caller.tenant(),upload)))assertTrue(f.cleanup().cleanup(f.caller.tenant(),work.workId(),UUID.randomUUID()));assertEquals(0,f.store.uploads.global.admittedCount());assertEquals(0,f.store.uploads.global.transientBytes());assertTrue(f.storage.objects.isEmpty());assertEquals(f.bytes().length,bytes());
  f.genericTemp.close();f.genericTemp=RealGenericTempFixture.open(root);assertTrue(f.genericTemp.ready());assertEquals(0,bytes());assertTrue(f.storage.objects.isEmpty());
 }
 @Test void failedSameCommandReplayNeverRewritesOriginalSuccessOrAccumulatesGenerations()throws Exception{
  var app=f.uploads();UUID upload=f.admitted(app,UploadFormat.PDF);app.transfer(f.caller,upload,"body",new ByteArrayInputStream(f.bytes()));var original=Map.copyOf(f.store.uploads.outcomes);assertEquals(1,f.storage.objects.size());
  for(int attempt=0;attempt<3;attempt++){try{f.failure(503,"UNAVAILABLE",()->app.transfer(f.caller,upload,"body",denyAtEof()));}finally{allow();}assertEquals(f.bytes().length,bytes());assertEquals(original,f.store.uploads.outcomes);assertEquals(1,f.storage.objects.size());}
  app.transfer(f.caller,upload,"body",new ByteArrayInputStream(f.bytes()));assertEquals(0,bytes());assertEquals(original,f.store.uploads.outcomes);assertEquals(1,f.storage.objects.size());
 }
 @Test void oneReceiptHasTwoActualPreflightLanesAndCurrentAuthorityPrecedesTheThirdBusyResponse()throws Exception{
  var complete=f.verified(f.uploads(),UploadFormat.PDF);var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);var opened=new java.util.concurrent.atomic.AtomicInteger();
  UploadStoragePort storage=new UploadStoragePort(){public InputStream open(ObjectIdentity object){throw new AssertionError("No legacy open");}public InputStream open(ObjectIdentity object,GenericTempBudget budget){budget.check();opened.incrementAndGet();return new ByteArrayInputStream(f.bytes()){@Override public int read(byte[] b,int off,int len){int n=super.read(b,off,len);if(n<0){entered.countDown();try{assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}return n;}};}public ObjectIdentity put(ObjectKey k,Path p,long n,String h){throw new AssertionError();}public ObjectIdentity copy(ObjectIdentity a,ObjectKey b){throw new AssertionError();}public List<ObjectIdentity> versions(ObjectKey k){throw new AssertionError();}};
  var authority=new ReceiptArtifactAuthority(f.store,storage,f.clock,f.genericTemp);
  try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var first=workers.submit(()->authority.verify(f.actor,f.caller.tenant(),f.document,complete.receiptId().toString()));assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->{while(entered.getCount()!=1)Thread.onSpinWait();});var second=workers.submit(()->authority.verify(f.actor,f.caller.tenant(),f.document,complete.receiptId().toString()));
   try{
    assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(2*f.bytes().length,bytes());assertEquals(1,f.store.uploads.global.admittedCount());
    f.failure(429,"QUOTA_EXCEEDED",()->f.documents().appendVersion(f.caller,f.document,0,"busy",complete.receiptId().toString()));assertEquals(2,opened.get());
    f.allowed.remove(org.documentknowledge.documentmanagement.domain.Action.VERSION_APPEND);f.failure(403,"OPERATION_DENIED",()->f.documents().appendVersion(f.caller,f.document,0,"denied",complete.receiptId().toString()));f.allowed.add(org.documentknowledge.documentmanagement.domain.Action.VERSION_APPEND);
    var unavailable=new DocumentCaller(f.actor,f.caller.tenant(),()->AuthorityDecision.UNAVAILABLE);f.failure(503,"UNAVAILABLE",()->f.documents().appendVersion(unavailable,f.document,0,"membership-outage",complete.receiptId().toString()));
    var foreign=new DocumentCaller(new Actor(f.actor.issuer(),"other-actor"),f.caller.tenant(),()->AuthorityDecision.ALLOW);f.failure(404,"NOT_FOUND",()->f.documents().appendVersion(foreign,f.document,0,"hidden",complete.receiptId().toString()));assertEquals(2,opened.get());assertTrue(f.store.uploads.consumed.isEmpty());assertTrue(f.store.docs.get(f.document).versions().isEmpty());
   }finally{release.countDown();}
   assertEquals(complete.receiptId().toString(),first.get(3,java.util.concurrent.TimeUnit.SECONDS).originalArtifactRef());assertEquals(complete.receiptId().toString(),second.get(3,java.util.concurrent.TimeUnit.SECONDS).originalArtifactRef());assertEquals(0,bytes());
  }
 }
 @Test void failedNewAppendPreflightRetainsItsLaneAndNoVersionConsumptionOrSuccessFact()throws Exception{
  var complete=f.verified(f.uploads(),UploadFormat.PDF);int commands=f.store.commands.size();
  UploadStoragePort storage=new UploadStoragePort(){public InputStream open(ObjectIdentity object){throw new AssertionError("No legacy open");}public InputStream open(ObjectIdentity object,GenericTempBudget budget){budget.check();return denyAtEof();}public ObjectIdentity put(ObjectKey k,Path p,long n,String h){throw new AssertionError("read only");}public ObjectIdentity copy(ObjectIdentity a,ObjectKey b){throw new AssertionError("read only");}public List<ObjectIdentity> versions(ObjectKey key){throw new AssertionError("no listing");}};
  var authority=new ReceiptArtifactAuthority(f.store,storage,f.clock,f.genericTemp);
  var documents=new DocumentService(f.store,f.grants,new DelegationPolicyPort(){public AuthorityDecision targetGrant(Actor a,String t,org.documentknowledge.documentmanagement.domain.Action x){return AuthorityDecision.ALLOW;}public AuthorityDecision ceiling(Actor a,String t,org.documentknowledge.documentmanagement.domain.Action x){return AuthorityDecision.ALLOW;}},authority,new DocumentCursorPort(){public PagePosition decode(String a,Actor b,String c){throw new AssertionError();}public String encode(PagePosition a,Actor b,String c){throw new AssertionError();}},f.clock);
  for(int attempt=0;attempt<3;attempt++){try{f.failure(503,"UNAVAILABLE",()->documents.appendVersion(f.caller,f.document,0,"append",complete.receiptId().toString()));}finally{allow();}assertEquals(f.bytes().length,bytes());assertTrue(f.store.uploads.consumed.isEmpty());assertTrue(f.store.docs.get(f.document).versions().isEmpty());assertEquals(commands,f.store.commands.size());}
  f.clock.advance(3601);f.cleanup().expire(f.caller.tenant(),complete.uploadId());for(var work:List.copyOf(f.store.uploads.pendingCleanup(f.caller.tenant(),complete.uploadId())))assertTrue(f.cleanup().cleanup(f.caller.tenant(),work.workId(),UUID.randomUUID()));assertEquals(0,f.store.uploads.global.transientBytes());assertEquals(f.bytes().length,bytes());
  f.genericTemp.close();f.genericTemp=RealGenericTempFixture.open(root);assertTrue(f.genericTemp.ready());assertEquals(0,bytes());
 }
}
