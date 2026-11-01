package org.documentknowledge.documentmanagement.application;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Explicit SQL/S3/ownership UNIT fixtures; public role limits use injected Clock at exact boundaries. */
class GenericTempApplicationBudgetTest {
 @TempDir Path temporary;UploadLifecycleTest f;
 @BeforeEach void setup(){f=new UploadLifecycleTest();f.temporary=temporary;f.create();}
 @AfterEach void close(){f.closeContent();}
 @Test void transferIncludesInitialCurrentAuthorityAndCannotReadBodyOrClaimAfterThreeHundredSeconds(){
  var app=f.uploads();UUID upload=f.admitted(app,UploadFormat.PDF);var reads=new AtomicInteger();f.grantHook=()->f.clock.advance(300);
  f.failure(503,"UNAVAILABLE",()->app.transfer(f.caller,upload,"expired-setup",new InputStream(){public int read(){reads.incrementAndGet();return -1;}}));assertEquals(0,reads.get());assertTrue(f.storage.objects.isEmpty());assertEquals(org.documentknowledge.documentmanagement.domain.upload.UploadState.INITIALIZED,f.store.uploads.slot(f.caller.tenant(),upload).orElseThrow().state());
 }
 @Test void preappendIncludesReceiptLookupBeforeAdmissionOrOriginalOpen(){
  var receipt=f.verified(f.uploads(),UploadFormat.PDF);var admitted=new AtomicInteger();var opened=new AtomicInteger();f.storage.onOpen=opened::incrementAndGet;
  DocumentUnitOfWork delayed=new DocumentUnitOfWork(){public <T>T execute(Function<DocumentRepository,T> work){T value=f.store.execute(work);f.clock.advance(60);return value;}};
  GenericTempPort unavailable=new GenericTempPort(){public Workspace reserve(Role role,Identity identity,GenericTempBudget budget){admitted.incrementAndGet();throw new AssertionError("No expired admission");}public boolean ready(){return false;}public void close(){}};
  var authority=new ReceiptArtifactAuthority(delayed,f.storage,f.clock,unavailable);f.failure(503,"UNAVAILABLE",()->authority.verify(f.actor,f.caller.tenant(),f.document,receipt.receiptId().toString()));assertEquals(0,admitted.get());assertEquals(0,opened.get());assertTrue(f.store.uploads.consumed.isEmpty());assertTrue(f.store.docs.get(f.document).versions().isEmpty());
 }
 @Test void preappendCleanupAndReleaseAreInsideTheSameOriginalSixtySeconds(){
  var receipt=f.verified(f.uploads(),UploadFormat.PDF);var delegate=f.generic();f.storage.onOpen=()->f.clock.advance(59);
  GenericTempPort delayed=new GenericTempPort(){public Workspace reserve(Role role,Identity identity,GenericTempBudget budget){var owned=delegate.reserve(role,identity,budget);return new Workspace(){public ArtifactFile write(InputStream input,GenericTempBudget b){return owned.write(input,b);}public Use retain(GenericTempBudget b){return owned.retain(b);}public void finish(GenericTempBudget b){owned.finish(b);f.clock.advance(1);}public void close(){owned.close();}};}public boolean ready(){return delegate.ready();}public void close(){}};
  var authority=new ReceiptArtifactAuthority(f.store,f.storage,f.clock,delayed);f.failure(503,"UNAVAILABLE",()->authority.verify(f.actor,f.caller.tenant(),f.document,receipt.receiptId().toString()));assertTrue(f.store.uploads.consumed.isEmpty());assertTrue(f.store.docs.get(f.document).versions().isEmpty());
 }
 @Test void legacyPathConstructorsFailClosedOnlyAfterTheOriginalTrustedLookup(){
  var app=f.uploads();UUID slot=f.admitted(app,UploadFormat.PDF);var reads=new AtomicInteger();var old=new UploadService(f.store,f.grants,f.storage,f.scanner,f.inspector,f.clock,temporary);
  f.failure(404,"NOT_FOUND",()->old.transfer(f.caller,UUID.randomUUID(),"unknown",new ByteArrayInputStream(f.bytes())));f.failure(503,"UNAVAILABLE",()->old.transfer(f.caller,slot,"no-path-fallback",new InputStream(){public int read(){reads.incrementAndGet();return -1;}}));assertEquals(0,reads.get());
  var receipt=f.verified(app,UploadFormat.PDF);var opened=new AtomicInteger();f.storage.onOpen=opened::incrementAndGet;var authority=new ReceiptArtifactAuthority(f.store,f.storage,f.clock,temporary);
  f.failure(404,"NOT_FOUND",()->authority.verify(f.actor,f.caller.tenant(),f.document,UUID.randomUUID().toString()));f.failure(503,"UNAVAILABLE",()->authority.verify(f.actor,f.caller.tenant(),f.document,receipt.receiptId().toString()));assertEquals(0,opened.get());
 }
 @Test void immediatelyBeforeTransferBoundaryCanSucceedAndNeverChangesTheOriginalExpiry(){
  var app=f.uploads();UUID upload=f.admitted(app,UploadFormat.PDF);var original=f.store.uploads.slot(f.caller.tenant(),upload).orElseThrow().expiresAt();f.storage.onPut=()->f.clock.advance(299);assertNotNull(app.transfer(f.caller,upload,"before",new ByteArrayInputStream(f.bytes())));assertEquals(original,f.store.uploads.slot(f.caller.tenant(),upload).orElseThrow().expiresAt());assertEquals(org.documentknowledge.documentmanagement.domain.upload.UploadState.QUARANTINED,f.store.uploads.slot(f.caller.tenant(),upload).orElseThrow().state());
 }
 @Test void everyInitialAuthorityBoundaryStopsExpiredOwnersBeforeAnyFurtherSql(){
  for(int phase=1;phase<=5;phase++){
   var local=new UploadLifecycleTest();local.temporary=temporary.resolve("authority-"+phase);var expired=new AtomicBoolean();var calls=new AtomicInteger();var late=new AtomicInteger();var owner=new AtomicReference<Thread>();
   try{local.create();var original=local.uploads();UUID upload=local.admitted(original,UploadFormat.PDF);int at=phase;local.grantHook=()->{owner.set(Thread.currentThread());if(calls.incrementAndGet()==at){expired.set(true);local.clock.advance(300);}};
    var tracked=tracking(local,expired,late,null,null);var app=new UploadService(tracked,local.grants,local.storage,local.scanner,local.inspector,local.clock,local.temporary,VerificationWorkspacePort.unavailable(),local.generic());
    local.failure(503,"UNAVAILABLE",()->app.transfer(local.caller,upload,"expired-boundary",new ByteArrayInputStream(local.bytes())));
    awaitOwner(owner);assertEquals(0,late.get(),"No expired-owner SQL dispatch after authority phase "+phase);assertTrue(local.storage.objects.isEmpty());assertEquals(org.documentknowledge.documentmanagement.domain.upload.UploadState.INITIALIZED,local.store.uploads.slot(local.caller.tenant(),upload).orElseThrow().state());
   }finally{local.closeContent();}
  }
 }
 @Test void lateMembershipCannotInvokeTheGrantOrOpenATransaction(){
  var app=f.uploads();UUID upload=f.admitted(app,UploadFormat.PDF);int before=f.store.executions;var calls=new AtomicInteger();var owner=new AtomicReference<Thread>();f.grantHook=calls::incrementAndGet;
  var caller=new DocumentCaller(f.actor,f.caller.tenant(),()->{owner.set(Thread.currentThread());f.clock.advance(300);return org.documentknowledge.identity.AuthorityDecision.ALLOW;});
  f.failure(503,"UNAVAILABLE",()->app.transfer(caller,upload,"late-membership",InputStream.nullInputStream()));awaitOwner(owner);assertEquals(0,calls.get());assertEquals(before,f.store.executions);
 }
 @Test void delayedTransactionCallbackCannotStartQueriesAndLateReturnCannotStartNextTransaction(){
  for(boolean beforeCallback:new boolean[]{true,false}){
   var expired=new AtomicBoolean();var late=new AtomicInteger();var entered=new AtomicInteger();var owner=new AtomicReference<Thread>();DocumentUnitOfWork tracked=new DocumentUnitOfWork(){public synchronized <T>T execute(Function<DocumentRepository,T> work){entered.incrementAndGet();if(beforeCallback){expired.set(true);f.clock.advance(300);}T value=f.store.execute(r->work.apply(proxy(r,DocumentRepository.class,expired,late,null,null,f)));if(!beforeCallback){expired.set(true);f.clock.advance(300);}return value;}};
   UUID upload=f.admitted(f.uploads(),UploadFormat.PDF);f.grantHook=()->owner.set(Thread.currentThread());var app=new UploadService(tracked,f.grants,f.storage,f.scanner,f.inspector,f.clock,temporary,VerificationWorkspacePort.unavailable(),f.generic());f.failure(503,"UNAVAILABLE",()->app.transfer(f.caller,upload,"callback-"+beforeCallback,InputStream.nullInputStream()));awaitOwner(owner);assertEquals(1,entered.get());assertEquals(0,late.get());
  }
 }
 @Test void lateDocumentAndSlotQueriesCannotDispatchFollowingQueries(){
  for(String delayed:List.of("find","slot")){
   var local=new UploadLifecycleTest();local.temporary=temporary.resolve("query-"+delayed);var expired=new AtomicBoolean();var late=new AtomicInteger();var owner=new AtomicReference<Thread>();
   try{local.create();local.grantHook=()->owner.set(Thread.currentThread());UUID upload=local.admitted(local.uploads(),UploadFormat.PDF);var tracked=tracking(local,expired,late,delayed,new AtomicBoolean());var app=new UploadService(tracked,local.grants,local.storage,local.scanner,local.inspector,local.clock,local.temporary,VerificationWorkspacePort.unavailable(),local.generic());local.failure(503,"UNAVAILABLE",()->app.transfer(local.caller,upload,"late-query",InputStream.nullInputStream()));awaitOwner(owner);assertEquals(0,late.get());assertTrue(expired.get());assertTrue(local.storage.objects.isEmpty());}
   finally{local.closeContent();}
  }
 }
 @Test void knownLatePreparationReturnAllowsOnlySettlementAndNeverBodyOrSuccess(){
  UUID upload=f.admitted(f.uploads(),UploadFormat.PDF);var entered=new AtomicInteger();var owner=new AtomicReference<Thread>();f.grantHook=()->owner.set(Thread.currentThread());var reads=new AtomicInteger();
  DocumentUnitOfWork latePrepare=new DocumentUnitOfWork(){public <T>T execute(Function<DocumentRepository,T> work){int at=entered.incrementAndGet();T value=f.store.execute(work);if(at==4)f.clock.advance(300);return value;}};
  var app=new UploadService(latePrepare,f.grants,f.storage,f.scanner,f.inspector,f.clock,temporary,VerificationWorkspacePort.unavailable(),f.generic());
  f.failure(503,"UNAVAILABLE",()->app.transfer(f.caller,upload,"late-preparation",new InputStream(){public int read(){reads.incrementAndGet();return -1;}}));awaitOwner(owner);
  assertEquals(5,entered.get(),"Four admitted reads/claim plus one settlement-only reset");assertEquals(0,reads.get());assertTrue(f.storage.objects.isEmpty());assertEquals(org.documentknowledge.documentmanagement.domain.upload.UploadState.INITIALIZED,f.store.uploads.slot(f.caller.tenant(),upload).orElseThrow().state());assertTrue(f.store.uploads.outcomes.values().stream().noneMatch(o->o.key().operation().equals("upload.transfer")));
 }
 @Test void budgetAwareDocumentPolicyStillHidesForeignAndAclDeniedAggregates(){
  var original=f.store.docs.get(f.document);var policy=new UploadPolicy(f.grants);
  for(boolean foreign:new boolean[]{true,false}){
   var rejected=new org.documentknowledge.documentmanagement.domain.DocumentAggregate(original.documentId(),foreign?"other-tenant":original.tenant(),original.creator(),original.createdAt(),original.revision(),original.metadata(),original.activeVersionId(),foreign?original.rules():Set.of(),original.holds(),original.versions());
   DocumentRepository repository=(DocumentRepository)java.lang.reflect.Proxy.newProxyInstance(DocumentRepository.class.getClassLoader(),new Class<?>[]{DocumentRepository.class},(p,m,a)->{if(m.getName().equals("find"))return Optional.of(rejected);throw new AssertionError("No unrelated repository access");});
   var budget=new GenericTempBudget(f.clock,GenericTempPort.Role.TRANSFER);f.failure(404,"NOT_FOUND",()->policy.document(repository,f.caller,f.document,org.documentknowledge.documentmanagement.domain.Action.VERSION_APPEND,false,budget::check));
  }
 }
 private static DocumentUnitOfWork tracking(UploadLifecycleTest local,AtomicBoolean expired,AtomicInteger late,String delayed,AtomicBoolean triggered){return new DocumentUnitOfWork(){public synchronized <T>T execute(Function<DocumentRepository,T> work){if(expired.get())late.incrementAndGet();return local.store.execute(r->work.apply(proxy(r,DocumentRepository.class,expired,late,delayed,triggered,local)));}};}
 @SuppressWarnings("unchecked") private static <T>T proxy(T delegate,Class<T> type,AtomicBoolean expired,AtomicInteger late,String delayed,AtomicBoolean triggered,UploadLifecycleTest local){return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
  if(m.getName().equals("uploads"))return proxy(local.store.uploads,UploadRepository.class,expired,late,delayed,triggered,local);
  if(expired.get())late.incrementAndGet();Object value;try{value=m.invoke(delegate,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
  if(m.getName().equals(delayed)&&triggered.compareAndSet(false,true)){expired.set(true);local.clock.advance(300);}return value;
 });}
 private static void awaitOwner(AtomicReference<Thread> owner){Thread actual=owner.get();assertNotNull(actual);try{actual.join(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}assertFalse(actual.isAlive(),"Actual service worker must settle before absence assertions");}

}
