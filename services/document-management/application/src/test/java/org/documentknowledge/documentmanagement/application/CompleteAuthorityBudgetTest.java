package org.documentknowledge.documentmanagement.application;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import org.documentknowledge.documentmanagement.domain.*;
import java.util.function.Function;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual application owner joined; SQL/S3/scanner/Clock remain explicit unit fixtures. */
class CompleteAuthorityBudgetTest {
 @TempDir Path temporary;UploadLifecycleTest f;
 @BeforeEach void setup(){f=new UploadLifecycleTest();f.temporary=temporary;f.create();}
 @AfterEach void close(){f.closeContent();}
 @Test void expiredStoragePreparationGrantCannotDispatchNewSql(){expiredInitialGrant(60);}
 @Test void expiredWholeCompleteGrantCannotDispatchNewSql(){expiredInitialGrant(240);}
 @Test void expiredInitialDocumentGuardCannotDispatchPreparationSql(){expiredGrant(2,60);}
 @Test void expiredFinalInitialGuardCannotDispatchPreparationSql(){expiredGrant(3,60);}
 @Test void expiredPreparationGuardCannotDispatchFurtherSql(){expiredGrant(4,60);}
 private void expiredInitialGrant(int seconds){expiredGrant(1,seconds);}
 private void expiredGrant(int position,int seconds){
  UUID upload=f.admitted(f.uploads(),UploadFormat.PDF);f.uploads().transfer(f.caller,upload,"body",new ByteArrayInputStream(f.bytes()));
  var checks=new AtomicInteger();var expired=new AtomicBoolean();var lateSql=new AtomicInteger();var owner=new AtomicReference<Thread>();var opens=new AtomicInteger();f.storage.onOpen=opens::incrementAndGet;
  DocumentUnitOfWork tracked=new DocumentUnitOfWork(){public <T>T execute(Function<DocumentRepository,T> work){if(expired.get())lateSql.incrementAndGet();return f.store.execute(work);}};
  f.grantHook=()->{owner.set(Thread.currentThread());if(checks.incrementAndGet()==position){expired.set(true);f.clock.advance(seconds);}};
  var app=new UploadService(tracked,f.grants,f.storage,f.scanner,f.inspector,f.clock,temporary,VerificationWorkspacePort.unavailable(),f.generic());
  f.failure(503,"UNAVAILABLE",()->app.complete(f.caller,upload,"expired-current-grant"));join(owner);assertEquals(0,lateSql.get());assertEquals(0,opens.get());assertTrue(f.store.uploads.receipts.isEmpty());
 }
 @Test void delayedTransactionAdmissionCannotQueryExpiredOwner(){
  var base=f.uploads();UUID upload=f.admitted(base,UploadFormat.PDF);base.transfer(f.caller,upload,"body",new ByteArrayInputStream(f.bytes()));var callbacks=new AtomicInteger();var owner=new AtomicReference<Thread>();
  DocumentUnitOfWork delayed=new DocumentUnitOfWork(){public <T>T execute(Function<DocumentRepository,T> work){return f.store.execute(repository->{owner.set(Thread.currentThread());f.clock.advance(60);return work.apply((DocumentRepository)java.lang.reflect.Proxy.newProxyInstance(DocumentRepository.class.getClassLoader(),new Class<?>[]{DocumentRepository.class},(proxy,method,args)->{callbacks.incrementAndGet();try{return method.invoke(repository,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}}));});}};
  var app=new UploadService(delayed,f.grants,f.storage,f.scanner,f.inspector,f.clock,temporary,f.testWorkspace,f.generic());f.failure(503,"UNAVAILABLE",()->app.complete(f.caller,upload,"delayed-admission"));join(owner);assertEquals(0,callbacks.get());assertTrue(f.store.uploads.receipts.isEmpty());
 }
 @Test void lateMembershipCannotContinueToGrantOrSql(){
  var base=f.uploads();UUID upload=f.admitted(base,UploadFormat.PDF);base.transfer(f.caller,upload,"body",new ByteArrayInputStream(f.bytes()));var grants=new AtomicInteger();var sql=new AtomicInteger();var owner=new AtomicReference<Thread>();
  var caller=new DocumentCaller(f.actor,f.caller.tenant(),()->{owner.set(Thread.currentThread());f.clock.advance(60);return org.documentknowledge.identity.AuthorityDecision.ALLOW;});
  DocumentUnitOfWork tracked=new DocumentUnitOfWork(){public <T>T execute(Function<DocumentRepository,T> work){sql.incrementAndGet();return f.store.execute(work);}};
  var app=new UploadService(tracked,(a,t,x,r)->{grants.incrementAndGet();return org.documentknowledge.identity.AuthorityDecision.ALLOW;},f.storage,f.scanner,f.inspector,f.clock,temporary,f.testWorkspace,f.generic());f.failure(503,"UNAVAILABLE",()->app.complete(caller,upload,"late-membership"));join(owner);assertEquals(0,grants.get());assertEquals(0,sql.get());
 }
 @Test void revokedDocumentAclIsHiddenBeforeBusyRetainedVerifier()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var returned=new CountDownLatch(1);var worker=new AtomicReference<Thread>();var calls=new AtomicInteger();var failure=new AtomicReference<Throwable>();var scan=f.scanner;
  f.scanner=source->{calls.incrementAndGet();worker.set(Thread.currentThread());entered.countDown();boolean interrupted=false;while(true){try{release.await();break;}catch(InterruptedException expected){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();return scan.scan(source);};
  var app=f.uploads();UUID first=f.admitted(app,UploadFormat.PDF),second=f.admitted(app,UploadFormat.PDF);app.transfer(f.caller,first,"first-body",new ByteArrayInputStream(f.bytes()));app.transfer(f.caller,second,"second-body",new ByteArrayInputStream(f.bytes()));
  var caller=Thread.ofVirtual().start(()->{try{app.complete(f.caller,first,"retained-worker");failure.set(new AssertionError("Cancelled caller cannot succeed"));}catch(DmFailure expected){if(expected.status()!=503)failure.set(expected);}catch(Throwable unexpected){failure.set(unexpected);}finally{returned.countDown();}});
  try{
   assertTrue(entered.await(2,TimeUnit.SECONDS));caller.interrupt();assertTrue(returned.await(2,TimeUnit.SECONDS));assertNull(failure.get());assertTrue(worker.get().isAlive());
   f.failure(429,"QUOTA_EXCEEDED",()->app.complete(f.caller,second,"busy-allowed"));
   f.failure(404,"NOT_FOUND",()->app.complete(f.caller,UUID.randomUUID(),"busy-unknown"));
   var foreign=new DocumentCaller(new org.documentknowledge.identity.Actor("https://issuer.example","other-actor"),f.caller.tenant(),()->org.documentknowledge.identity.AuthorityDecision.ALLOW);
   f.failure(404,"NOT_FOUND",()->app.complete(foreign,second,"busy-foreign"));
   f.override=org.documentknowledge.identity.AuthorityDecision.DENY;f.failure(403,"OPERATION_DENIED",()->app.complete(f.caller,second,"busy-no-grant"));
   f.override=org.documentknowledge.identity.AuthorityDecision.UNAVAILABLE;f.failure(503,"UNAVAILABLE",()->app.complete(f.caller,second,"busy-authority-outage"));f.override=null;
   long revision=f.store.docs.get(f.document).revision();f.documents().changeAcl(f.caller,f.document,revision,"revoke-append",new AclChange(AclChange.Mode.RESTRICT,List.of(new AclChange.Entry(AclChange.Operation.REMOVE_ALLOW,f.caller.principal(),Action.VERSION_APPEND))));
   f.failure(404,"NOT_FOUND",()->app.complete(f.caller,second,"busy-revoked"));assertEquals(1,calls.get());assertTrue(f.store.uploads.receipts.isEmpty());
  }finally{release.countDown();join(worker);caller.join(2000);}
 }
 private static void join(AtomicReference<Thread> owner){Thread actual=owner.get();assertNotNull(actual);try{actual.join(2000);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}assertFalse(actual.isAlive(),"Join actual service worker before asserting no new SQL");}
}
