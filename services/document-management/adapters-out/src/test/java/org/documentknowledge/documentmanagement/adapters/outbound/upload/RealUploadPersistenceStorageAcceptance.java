package org.documentknowledge.documentmanagement.adapters.outbound.upload;

import java.io.*;
import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.net.ssl.*;
import org.documentknowledge.documentmanagement.adapters.outbound.persistence.JdbcDocumentStore;
import org.documentknowledge.documentmanagement.adapters.outbound.storage.S3UploadStorageAdapter;
import org.documentknowledge.documentmanagement.adapters.outbound.storage.S3CleanupStorageAdapter;
import org.documentknowledge.documentmanagement.adapters.outbound.storage.RealPutResponseBarrier;
import org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.FileVerificationWorkspace;
import org.documentknowledge.documentmanagement.adapters.outbound.verification.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.domain.Principal;
import org.documentknowledge.documentmanagement.domain.upload.*;
import org.documentknowledge.identity.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit required-profile gate. Missing real providers FAIL; no mocks, assumptions or disabled cases.
 * Only timing/response loss and crash points are injected by test decorators. Actual SQL/S3,
 * signature scanning, structural inspection, application coordination and hold policy run unchanged.
 */
public final class RealUploadPersistenceStorageAcceptance {
    private RealUploadPersistenceStorageAcceptance() { }
    private static final Principal ACTOR = new Principal("https://public-synthetic-issuer.example", "issue-144");
    private static final String PROFILE = "b2700939fc4b890650e053f3cd20b8407bbdf992d1de2082d4b8a6f29f51f5af";
    @FunctionalInterface interface Case { void run(Fixture fixture) throws Exception; }
    public static void main(String[] args) throws Exception {
        assertEquals(0,args.length,"Configuration is explicit required environment, never credential arguments");
        assertEquals("Linux",System.getProperty("os.name"));
        try (Fixture fixture = new Fixture()) {
            run(fixture,"COPY-01",RealUploadPersistenceStorageAcceptance::copyCrash);
            run(fixture,"COPY-02",RealUploadPersistenceStorageAcceptance::copyRecovery);
            run(fixture,"COPY-03",RealUploadPersistenceStorageAcceptance::concurrentAccounting);
            run(fixture,"FENCE-01",RealUploadPersistenceStorageAcceptance::holdFirst);
            run(fixture,"FENCE-02",RealUploadPersistenceStorageAcceptance::delayedDeletion);
            run(fixture,"FENCE-03",RealUploadPersistenceStorageAcceptance::crashBoundaries);
            run(fixture,"FENCE-04",RealUploadPersistenceStorageAcceptance::durableSettlement);
            run(fixture,"FENCE-05",RealUploadPersistenceStorageAcceptance::bindingAndConsumption);
        }
        System.out.println("{\"issue\":144,\"required\":8,\"passed\":8,\"skipped\":0}");
    }
    private static void run(Fixture fixture,String requirement,Case work)throws Exception {
        fixture.reset();work.run(fixture);
        System.out.println("{\"requirement\":\""+requirement+"\",\"result\":\"PASS\"}");
    }

    private static void copyCrash(Fixture f)throws Exception {
        UUID upload=f.quarantined();
        var crashed=new Forwarding(f.storage){@Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace){super.copy(source,target,phase,workspace);throw new ProcessCrash();}};
        assertThrows(ProcessCrash.class,()->f.upload(crashed).complete(f.caller,upload,"copy-crash"));
        UploadSlot slot=f.slot(upload);assertEquals(UploadState.VERIFYING,slot.state());assertNotNull(slot.possibleOriginal());assertNull(slot.original());assertFalse(slot.writerSettled());
        f.fullCharge(1);f.physicalCopies(slot,2);assertEquals(0,f.count("upload_receipts"));
        int copies=f.storage.copies.get();f.clock.advance(301);f.restart();
        f.denied(503,()->f.upload(f.storage).complete(f.caller,upload,"after-restart"));
        assertEquals(copies,f.storage.copies.get());assertFalse(f.slot(upload).writerSettled());f.fullCharge(1);
        // A real absent HEAD/list is not settlement. Remove only the test-owned exact version,
        // then prove absence still cannot make the unknown writer safe or permit a new copy.
        ObjectIdentity exact=f.storage.versions(slot.possibleOriginal()).getFirst();
        assertTrue(f.storage.deleteOnce(f.attempt(exact)).successful());assertTrue(f.storage.versions(slot.possibleOriginal()).isEmpty());
        f.clock.advance(3600);f.cleanup(f.storage).expire(f.tenant,upload);
        for(CleanupWork work:f.pending(upload))f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID()));
        assertFalse(f.slot(upload).writerSettled());f.fullCharge(1);assertEquals(copies,f.storage.copies.get());
    }

    private static void copyRecovery(Fixture f)throws Exception {
        UUID upload=f.quarantined();
        // Real successful copy, followed by failed readback: exact writer outcome is durable,
        // but no receipt can exist until its previous possible copy is confirmed cleaned.
        var failReadback=new Forwarding(f.storage){@Override public InputStream open(ObjectIdentity object,CompleteBudget.Phase phase){if(object.objectKey().bucket().equals("dkp-originals"))throw new DmFailure(503,"UNAVAILABLE");return super.open(object,phase);}};
        f.denied(503,()->f.upload(failReadback).complete(f.caller,upload,"readback-failed"));
        UploadSlot slot=f.slot(upload);assertTrue(slot.writerSettled());assertNotNull(slot.original());assertFalse(slot.copyCleanupConfirmed());f.fullCharge(1);f.physicalCopies(slot,2);
        CleanupWork work=f.originalWork(upload);int copies=f.storage.copies.get();
        f.denied(503,()->f.upload(f.storage).complete(f.caller,upload,"blocked-next-copy"));assertEquals(copies,f.storage.copies.get());
        // Database/hold authority unavailable: real queue/charges survive, no remote dispatch.
        int deleted=f.storage.deletes.get();f.documentAuthority(false);
        try{f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID()));}finally{f.documentAuthority(true);}
        assertEquals(deleted,f.storage.deletes.get());f.fullCharge(1);
        f.restart();assertTrue(f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID()));assertEquals(deleted+1,f.storage.deletes.get());
        assertEquals(UploadState.QUARANTINED,f.slot(upload).state());assertTrue(f.slot(upload).copyCleanupConfirmed());assertTrue(f.storage.versions(slot.original().objectKey()).isEmpty());f.fullCharge(1);
        var complete=f.upload(f.storage).complete(f.caller,upload,"safe-new-copy");assertNotNull(complete.receiptId());assertEquals(copies+1,f.storage.copies.get());assertEquals(1,f.count("upload_receipts"));f.physicalCopies(f.slot(upload),2);f.fullCharge(1);
        // Unknown write returns while a real late copy executor remains executable.
        f.reset();UUID lateUpload=f.quarantined();
        try(var late=new LateCopy(f)){
            f.denied(503,()->f.upload(late).complete(f.caller,lateUpload,"unknown-copy"));await(late.entered);
            UploadSlot unresolved=f.slot(lateUpload);assertFalse(unresolved.writerSettled());assertNull(unresolved.original());f.fullCharge(1);
            assertFalse(f.workspaces.ready(),"Actual Workspace.Use must remain owned across the lost response");
            try(var payloads=Files.list(f.temporary)){long retained=0;for(Path path:payloads.filter(p->p.getFileName().toString().startsWith("dkp-verification-")).toList())retained+=Files.size(path);assertEquals(2L*f.bytes.length,retained);}
            f.clock.advance(301);f.restart();f.denied(503,()->f.upload(f.storage).complete(f.caller,lateUpload,"unknown-copy-restart"));
            for(CleanupWork pending:f.pending(lateUpload))f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,pending.workId(),UUID.randomUUID()));
            int before=f.storage.copies.get();assertEquals(1,late.barrier.dispatched());f.physicalCopies(f.slot(lateUpload),2);late.release.countDown();
            var lateFailure=assertThrows(ExecutionException.class,()->late.result.get(30,TimeUnit.SECONDS));assertEquals(503,assertInstanceOf(DmFailure.class,lateFailure.getCause()).status());assertEquals(before,f.storage.copies.get());assertEquals(1,late.barrier.dispatched());f.physicalCopies(f.slot(lateUpload),2);assertEquals(0,f.count("upload_receipts"));
            f.denied(503,()->f.upload(f.storage).complete(f.caller,lateUpload,"late-write-not-settlement"));f.fullCharge(1);
        }
    }

    private static void concurrentAccounting(Fixture f)throws Exception {
        UUID upload=f.failedKnownCopy();CleanupWork work=f.originalWork(upload);int deletes=f.storage.deletes.get();
        CountDownLatch start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(3)){
            var cleanup=workers.submit(()->{await(start);return f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID());});
            var admitted=workers.submit(()->{await(start);return f.upload(f.storage).initialize(f.caller,"parallel-admission",f.document,0,UploadFormat.PDF,f.bytes.length);});
            var retried=workers.submit(()->{await(start);try{return f.upload(f.storage).complete(f.caller,upload,"parallel-complete").receiptId();}catch(DmFailure failure){assertEquals(503,failure.status());return null;}});
            start.countDown();assertTrue(cleanup.get(30,TimeUnit.SECONDS));assertNotNull(admitted.get(30,TimeUnit.SECONDS));retried.get(120,TimeUnit.SECONDS);
        }
        assertEquals(deletes+1,f.storage.deletes.get());f.fullCharge(2);
        // Fill exactly five admissions with an unresolved possible copy still charged.
        for(int i=2;i<5;i++)f.upload(f.storage).initialize(f.caller,"fill-"+i,f.document,0,UploadFormat.PDF,f.bytes.length);
        f.denied(429,()->f.upload(f.storage).initialize(f.caller,"sixth",f.document,0,UploadFormat.PDF,f.bytes.length));f.fullCharge(5);
        assertTrue(f.accounts().global().transientBytes()>=0);assertEquals(0,f.doc().revision());assertNull(f.doc().activeVersionId());
        // Real concurrent init + append + cleanup with a committed document fence.
        // Append must fail atomically while DELETE remains executable, then consume once.
        f.reset();UUID failed=f.failedKnownCopy();CleanupWork cleanupWork=f.originalWork(failed);UUID verified=f.quarantined();
        UUID receipt=f.upload(f.storage).complete(f.caller,verified,"concurrent-verified").receiptId();
        try(var workers=Executors.newFixedThreadPool(3)){
            var paused=new PausedDelete(f.storage);var cleanup=workers.submit(()->f.cleanup(paused).cleanup(f.tenant,cleanupWork.workId(),UUID.randomUUID()));await(paused.entered);
            var append=workers.submit(()->{f.denied(503,()->f.documents(f.store).appendVersion(f.caller,f.document,0,"parallel-append",receipt.toString()));return true;});
            var admission=workers.submit(()->f.upload(f.storage).initialize(f.caller,"parallel-init",f.document,0,UploadFormat.PDF,f.bytes.length));
            assertTrue(append.get(30,TimeUnit.SECONDS));assertNotNull(admission.get(30,TimeUnit.SECONDS));assertEquals(0,f.count("upload_receipt_consumptions"));assertEquals(0,f.doc().revision());
            paused.release.countDown();assertTrue(cleanup.get(30,TimeUnit.SECONDS));
        }
        assertEquals(1,f.documents(f.store).appendVersion(f.caller,f.document,0,"parallel-append",receipt.toString()).revision());
        Accounts balanced=f.accounts();for(ResourceAccount a:List.of(balanced.global(),balanced.tenant())){assertEquals(2,a.admittedCount());assertEquals(5L*f.bytes.length,a.transientBytes());assertEquals(2L*f.bytes.length,a.promisedBytes());assertEquals(f.bytes.length,a.retainedBytes());}
        assertEquals(1,f.count("upload_receipt_consumptions"));assertEquals(1,f.doc().versions().size());assertNull(f.doc().activeVersionId());
    }

    private static void holdFirst(Fixture f)throws Exception {
        UUID upload=f.failedKnownCopy();CleanupWork work=f.originalWork(upload);var held=f.documents(f.store).placeHold(f.caller,f.document,0,"prior-hold","Synthetic preservation");
        int deletes=f.storage.deletes.get();f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID()));assertEquals(deletes,f.storage.deletes.get());
        assertTrue(f.fence().isEmpty());assertEquals(1,f.doc().holds().size());assertEquals(1,f.doc().revision());
        f.restart();assertEquals(held,f.documents(f.store).placeHold(f.caller,f.document,0,"prior-hold","Synthetic preservation"));assertEquals(1,f.count("command_outcomes"));f.fullCharge(1);f.physicalCopies(f.slot(upload),2);
        // BP03 invariant: runtime legal_holds is append-only (SELECT/INSERT only),
        // no release route exists, and both application and DB reject a fence after
        // an existing hold. Thus prior successful hold + unresolved fence is not a
        // reachable state without an unauthorized hold-release/rebind. Prove both
        // exclusion at real SQL and replay-before-fence-query at the real command
        // boundary; never manufacture the impossible state to claim acceptance.
        var rejectedFence=new DeletionFence(UUID.randomUUID(),f.tenant,f.document,upload,work.object(),UUID.randomUUID(),f.clock.instant(),FencePhase.AUTHORIZED);
        f.denied(503,()->f.store.execute(r->{r.uploads().lockAccounts(f.tenant);r.find(f.tenant,f.document);r.uploads().slot(f.tenant,upload);r.uploads().insertFence(rejectedFence);return null;}));
        assertTrue(f.fence().isEmpty());DocumentAggregate heldDocument=f.doc();
        assertEquals(held,f.documents(intercept(f.store,"unresolvedFence",false)).placeHold(f.caller,f.document,0,"prior-hold","Synthetic preservation"));
        assertEquals(heldDocument,f.doc());assertEquals(1,f.count("command_outcomes"));
        for(int i=1;i<5;i++)f.upload(f.storage).initialize(f.caller,"held-capacity-"+i,f.document,1,UploadFormat.PDF,f.bytes.length);
        f.denied(429,()->f.upload(f.storage).initialize(f.caller,"held-sixth",f.document,1,UploadFormat.PDF,f.bytes.length));f.fullCharge(5);
    }

    private static void delayedDeletion(Fixture f)throws Exception {
        UUID upload=f.failedKnownCopy();CleanupWork work=f.originalWork(upload);int deletes=f.storage.deletes.get();
        try(var workers=Executors.newSingleThreadExecutor()){
            var paused=new PausedDelete(f.storage);var future=workers.submit(()->f.cleanup(paused).cleanup(f.tenant,work.workId(),UUID.randomUUID()));
            await(paused.entered);assertEquals(FencePhase.DISPATCHED,f.fence().orElseThrow().phase());f.noNewHold();f.fullCharge(1);assertEquals(deletes,f.storage.deletes.get());
            f.clock.advance(301);f.restart();f.noNewHold();f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID()));assertEquals(deletes,f.storage.deletes.get());
            paused.release.countDown();assertTrue(future.get(30,TimeUnit.SECONDS));assertEquals(deletes+1,f.storage.deletes.get());assertTrue(f.fence().isEmpty());
            var held=f.documents(f.store).placeHold(f.caller,f.document,0,"after-terminal","Only after no further execution");assertEquals(1,held.revision());
            int settled=f.storage.deletes.get();assertTrue(f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID()));assertEquals(settled,f.storage.deletes.get());
        }
        // Timeout/lost response while test-owned late executor can still delete: no transfer.
        f.reset();UUID lateUpload=f.failedKnownCopy();CleanupWork lateWork=f.originalWork(lateUpload);
        try(var late=new LateDelete(f.storage)){
            CleanupWork pending=lateWork;f.denied(503,()->f.cleanup(late).cleanup(f.tenant,pending.workId(),UUID.randomUUID()));await(late.entered);f.noNewHold();f.fullCharge(1);
            f.clock.advance(3900);f.restart();f.noNewHold();f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,pending.workId(),UUID.randomUUID()));
            late.release.countDown();assertTrue(late.result.get(30,TimeUnit.SECONDS).successful());assertTrue(f.storage.versions(pending.key()).isEmpty());
            f.noNewHold();f.fullCharge(1);assertEquals(FencePhase.DISPATCHED,f.fence().orElseThrow().phase());
        }
        f.reset();UUID cancelledUpload=f.failedKnownCopy();CleanupWork cancelledWork=f.originalWork(cancelledUpload);UUID oldExecutor=UUID.randomUUID();
        try(var physical=new CancellableDelete(f.storage);var coordinator=Executors.newSingleThreadExecutor()){
            int before=f.storage.deletes.get();Future<Boolean> caller=coordinator.submit(()->f.cleanup(physical).cleanup(f.tenant,cancelledWork.workId(),oldExecutor));
            await(physical.entered);assertEquals(FencePhase.DISPATCHED,f.fence().orElseThrow().phase());f.noNewHold();f.fullCharge(1);
            assertTrue(caller.cancel(true),"The coordinator must actually be cancelled while DELETE remains independently executable");
            assertThrows(CancellationException.class,caller::get);await(physical.cancelled);assertEquals(before,f.storage.deletes.get());f.noNewHold();f.fullCharge(1);
            f.clock.advance(3900);f.restart();f.noNewHold();f.fullCharge(1);
            f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,cancelledWork.workId(),UUID.randomUUID()));assertEquals(before,f.storage.deletes.get());
            physical.release.countDown();assertTrue(physical.result.get(30,TimeUnit.SECONDS).successful());assertEquals(before+1,f.storage.deletes.get());assertTrue(f.storage.versions(cancelledWork.key()).isEmpty());
            f.noNewHold();f.fullCharge(1);assertEquals(FencePhase.DISPATCHED,f.fence().orElseThrow().phase());assertEquals(0,f.count("upload_deletion_settlements"));
            f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,cancelledWork.workId(),oldExecutor));assertEquals(before+1,f.storage.deletes.get());
        }
    }

    private static void crashBoundaries(Fixture f)throws Exception {
        for(String point:List.of("before-dispatch","after-dispatch","after-response")){
            f.reset();UUID upload=f.failedKnownCopy();CleanupWork work=f.originalWork(upload);int deletes=f.storage.deletes.get();
            DocumentUnitOfWork uow=point.equals("before-dispatch")?intercept(f.store,"dispatchFence",false):f.store;
            CleanupStoragePort port=point.equals("after-dispatch")?new Forwarding(f.storage){@Override public DeletionResult deleteOnce(DeletionFence attempt){throw new ProcessCrash();}}:f.storage;
            if(point.equals("after-response"))uow=intercept(f.store,"settleFence",false);
            var cleanup=new UploadCleanupService(uow,port,f.clock);assertThrows(ProcessCrash.class,()->cleanup.cleanup(f.tenant,work.workId(),UUID.randomUUID()));
            assertEquals(point.equals("after-response")?deletes+1:deletes,f.storage.deletes.get());
            assertEquals(point.equals("before-dispatch")?FencePhase.AUTHORIZED:FencePhase.DISPATCHED,f.fence().orElseThrow().phase());
            f.clock.advance(4000);f.restart();f.noNewHold();f.fullCharge(1);int now=f.storage.deletes.get();
            f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID()));assertEquals(now,f.storage.deletes.get());
            assertEquals(point.equals("after-response"),f.storage.versions(work.key()).isEmpty());
        }
    }

    private static void durableSettlement(Fixture f)throws Exception {
        // Genuine terminal403 with the exact same object/version and a distinct native IAM
        // read-only app principal: a failed request can settle, but releases no bytes.
        UUID rejectedUpload=f.failedKnownCopy();CleanupWork rejectedWork=f.originalWork(rejectedUpload);Accounts charged=f.accounts();
        assertFalse(f.cleanup(f.deniedStorage).cleanup(f.tenant,rejectedWork.workId(),UUID.randomUUID()));
        assertTrue(f.fence().isEmpty());assertEquals(1,f.count("upload_deletion_settlements"));assertEquals(charged,f.accounts());assertEquals(2,f.physicalCount(f.slot(rejectedUpload)));assertFalse(f.store.execute(r->r.uploads().cleanup(f.tenant,rejectedWork.workId()).orElseThrow()).complete());
        f.restart();assertTrue(f.cleanup(f.storage).cleanup(f.tenant,rejectedWork.workId(),UUID.randomUUID()));assertEquals(2,f.count("upload_deletion_settlements"));f.fullCharge(1);
        f.reset();UUID upload=f.failedKnownCopy();CleanupWork work=f.originalWork(upload);int before=f.storage.deletes.get();
        // Crash AFTER the settlement transaction committed, BEFORE the caller observed success.
        var cleanup=new UploadCleanupService(intercept(f.store,"settleFence",true),f.storage,f.clock);
        assertThrows(ProcessCrash.class,()->cleanup.cleanup(f.tenant,work.workId(),UUID.randomUUID()));
        assertEquals(before+1,f.storage.deletes.get());assertTrue(f.fence().isEmpty());assertTrue(f.store.execute(r->r.uploads().cleanup(f.tenant,work.workId()).orElseThrow()).complete());assertEquals(1,f.count("upload_deletion_settlements"));
        Accounts balances=f.accounts();f.restart();assertTrue(f.cleanup(f.storage).cleanup(f.tenant,work.workId(),UUID.randomUUID()));assertEquals(before+1,f.storage.deletes.get());assertEquals(balances,f.accounts());
        // Expired terminal slot cleanup frees the exact reservation once, durably.
        f.clock.advance(4000);f.cleanup(f.storage).expire(f.tenant,upload);
        for(CleanupWork pending:f.pending(upload))assertTrue(f.cleanup(f.storage).cleanup(f.tenant,pending.workId(),UUID.randomUUID()));
        Accounts released=f.accounts();assertEquals(0,released.tenant().admittedCount());assertEquals(0,released.tenant().transientBytes());assertEquals(0,released.tenant().promisedBytes());
        int deleted=f.storage.deletes.get();f.restart();f.cleanup(f.storage).sweep(UUID.randomUUID(),50);assertEquals(released,f.accounts());assertEquals(deleted,f.storage.deletes.get());
        assertEquals(1,f.documents(f.store).placeHold(f.caller,f.document,0,"settled-hold","No executable deletion remains").revision());
    }

    private static void bindingAndConsumption(Fixture f)throws Exception {
        for(boolean wrongObject:List.of(false,true)){
            f.reset();UUID upload=f.failedKnownCopy();CleanupWork work=f.originalWork(upload);
            var corrupt=new Forwarding(f.storage){@Override public DeletionResult deleteOnce(DeletionFence attempt){var real=super.deleteOnce(attempt);assertTrue(real.successful());return new DeletionResult(wrongObject?real.attemptId():UUID.randomUUID(),wrongObject?new ObjectIdentity(real.object().objectKey(),"wrong-version"):real.object(),real.requestId(),real.terminal(),real.noFurtherExecution(),real.absent(),real.successful());}};
            f.denied(503,()->f.cleanup(corrupt).cleanup(f.tenant,work.workId(),UUID.randomUUID()));assertEquals(0,f.count("upload_deletion_settlements"));f.noNewHold();f.fullCharge(1);assertFalse(f.store.execute(r->r.uploads().cleanup(f.tenant,work.workId()).orElseThrow()).complete());
        }
        f.reset();UUID upload=f.quarantined();var receipt=f.upload(f.storage).complete(f.caller,upload,"genuine-complete");
        var append=f.documents(f.store).appendVersion(f.caller,f.document,0,"consume",receipt.receiptId().toString());assertEquals(1,append.revision());UploadSlot consumed=f.slot(upload);assertEquals(UploadState.CONSUMED,consumed.state());
        CleanupWork forged=new CleanupWork(UUID.randomUUID(),f.tenant,upload,f.document,consumed.original().objectKey(),consumed.original(),CleanupReason.FAILED_VERIFIED_COPY,f.clock.instant(),false);
        f.store.execute(r->{r.uploads().lockAccounts(f.tenant);r.find(f.tenant,f.document);r.uploads().enqueueCleanup(forged);return null;});int deletes=f.storage.deletes.get();
        f.denied(503,()->f.cleanup(f.storage).cleanup(f.tenant,forged.workId(),UUID.randomUUID()));assertEquals(deletes,f.storage.deletes.get());assertArrayEquals(f.bytes,f.read(consumed.original()));
        assertEquals(1,f.count("upload_receipt_consumptions"));assertEquals(1,f.doc().versions().size());assertNull(f.doc().activeVersionId());assertTrue(f.fence().isEmpty());
    }

    private static final class ProcessCrash extends Error { }
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(30,TimeUnit.SECONDS),"Deterministic barrier deadline");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
    private static DocumentUnitOfWork intercept(DocumentUnitOfWork delegate,String operation,boolean afterCommit){
        return new DocumentUnitOfWork(){@Override public <T>T execute(Function<DocumentRepository,T> work){
            AtomicBoolean hit=new AtomicBoolean();T value=delegate.execute(repository->{
                DocumentRepository wrapped=(DocumentRepository)Proxy.newProxyInstance(DocumentRepository.class.getClassLoader(),new Class<?>[]{DocumentRepository.class},(proxy,method,args)->{
                    if(!method.getName().equals("uploads"))return invoke(method,repository,args);
                    UploadRepository upload=repository.uploads();return Proxy.newProxyInstance(UploadRepository.class.getClassLoader(),new Class<?>[]{UploadRepository.class},(p,m,a)->{if(m.getName().equals(operation)){hit.set(true);if(!afterCommit)throw new ProcessCrash();}return invoke(m,upload,a);});
                });return work.apply(wrapped);
            });if(hit.get()&&afterCommit)throw new ProcessCrash();return value;
        }};
    }
    private static Object invoke(Method method,Object target,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
    static class Forwarding implements UploadStoragePort, CleanupStoragePort {
        final UploadStoragePort delegate;final CleanupStoragePort cleanup;Forwarding(UploadStoragePort delegate){this(delegate,(CleanupStoragePort)delegate);}Forwarding(UploadStoragePort delegate,CleanupStoragePort cleanup){this.delegate=delegate;this.cleanup=cleanup;}
        public ObjectIdentity put(ObjectKey key,Path path,long bytes,String hash){return delegate.put(key,path,bytes,hash);}
        public InputStream open(ObjectIdentity object){return delegate.open(object);}
        public ObjectIdentity copy(ObjectIdentity source,ObjectKey target){return delegate.copy(source,target);}
        public InputStream open(ObjectIdentity object,CompleteBudget.Phase phase){return delegate.open(object,phase);}
        public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase){return delegate.copy(source,target,phase);}
        public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace){return delegate.copy(source,target,phase,workspace);}
        public List<ObjectIdentity> versions(ObjectKey key){return cleanup.versions(key);}
        public DeletionResult deleteOnce(DeletionFence attempt){return cleanup.deleteOnce(attempt);}
    }
    private static final class Observed extends Forwarding {
        final AtomicInteger copies=new AtomicInteger(),deletes=new AtomicInteger();
        Observed(UploadStoragePort delegate,CleanupStoragePort cleanup){super(delegate,cleanup);}
        @Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace){copies.incrementAndGet();return super.copy(source,target,phase,workspace);}
        @Override public DeletionResult deleteOnce(DeletionFence attempt){deletes.incrementAndGet();return super.deleteOnce(attempt);}
    }
    private static final class PausedDelete extends Forwarding {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        PausedDelete(UploadStoragePort delegate){super(delegate);}
        @Override public DeletionResult deleteOnce(DeletionFence attempt){entered.countDown();await(release);return super.deleteOnce(attempt);}
    }
    private static final class CancellableDelete extends Forwarding implements AutoCloseable {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        final CompletableFuture<DeletionResult> result=new CompletableFuture<>();final ExecutorService physical=Executors.newSingleThreadExecutor();
        CancellableDelete(UploadStoragePort delegate){super(delegate);}
        @Override public DeletionResult deleteOnce(DeletionFence attempt){
            physical.submit(()->{entered.countDown();await(release);try{result.complete(super.deleteOnce(attempt));}catch(Throwable failure){result.completeExceptionally(failure);}});
            try{return result.get(60,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();cancelled.countDown();throw new DmFailure(503,"UNAVAILABLE");}catch(ExecutionException|TimeoutException unavailable){throw new DmFailure(503,"UNAVAILABLE");}
        }
        public void close(){release.countDown();physical.shutdown();try{assertTrue(physical.awaitTermination(30,TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
    }
    private static final class LateCopy extends Forwarding implements AutoCloseable {
        final RealPutResponseBarrier barrier;
        final CountDownLatch entered,release;
        final CompletableFuture<ObjectIdentity> result=new CompletableFuture<>();final ExecutorService executor=Executors.newSingleThreadExecutor();
        LateCopy(Fixture f){super(f.storage);barrier=RealPutResponseBarrier.connect(f.configuration(),f.workspaces);entered=barrier.entered();release=barrier.release();}
        @Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase,VerificationWorkspacePort.Workspace workspace){
            executor.submit(()->{try{result.complete(barrier.storage().copy(source,target,phase,workspace));}catch(Throwable failure){result.completeExceptionally(failure);}});
            await(entered);assertFalse(result.isDone());assertEquals(1,barrier.dispatched());throw new DmFailure(503,"UNAVAILABLE");
        }
        public void close(){release.countDown();executor.shutdown();try{assertTrue(executor.awaitTermination(30,TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}finally{barrier.close();}}
    }
    private static final class LateDelete extends Forwarding implements AutoCloseable {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final CompletableFuture<DeletionResult> result=new CompletableFuture<>();final ExecutorService executor=Executors.newSingleThreadExecutor();
        LateDelete(UploadStoragePort delegate){super(delegate);}
        @Override public DeletionResult deleteOnce(DeletionFence attempt){executor.submit(()->{entered.countDown();await(release);try{result.complete(super.deleteOnce(attempt));}catch(Throwable failure){result.completeExceptionally(failure);}});throw new DmFailure(503,"UNAVAILABLE");}
        public void close(){release.countDown();executor.shutdown();try{assertTrue(executor.awaitTermination(30,TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
    }

    private static final class MutableClock extends Clock {
        private Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        void advance(long seconds){at=at.plusSeconds(seconds);}
        void reset(){at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){assertEquals(ZoneOffset.UTC,zone);return this;}public Instant instant(){return at;}
    }
    static final class Fixture implements AutoCloseable {
        final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("dm").withUsername("postgres").withPassword(UUID.randomUUID().toString())
                .withCommand("postgres","-c","fsync=on","-c","synchronous_commit=on","-c","full_page_writes=on");
        final String tenant=required("DKP_RUSTFS_COMPAT_TENANT"),password=UUID.randomUUID().toString();
        final UUID document=UUID.fromString(required("DKP_RUSTFS_COMPAT_PREFIX").split("/")[1]);
        final DocumentCaller caller=new DocumentCaller(new Actor(ACTOR.issuer(),ACTOR.subject()),tenant,()->AuthorityDecision.ALLOW);
        final ResourceAuthorizationPort grants=(actor,scope,action,resource)->actor.equals(caller.actor())&&scope.equals(tenant)&&Arrays.stream(Action.values()).anyMatch(a->a.wire().equals(action))?AuthorityDecision.ALLOW:AuthorityDecision.DENY;
        final MutableClock clock=new MutableClock();
        final Path authority=Path.of(required("DKP_RUSTFS_COMPAT_AUTHORITY")),temporary;
        final byte[] bytes=Files.readAllBytes(Path.of(required("DKP_144_PDF")));
        final S3UploadStorageAdapter actual;
        final FileVerificationWorkspace workspaces;
        final S3CleanupStorageAdapter actualCleanup,deniedStorage;
        final Observed storage;
        final ClamAvScanAdapter scanner;
        final IsolatedInspectionAdapter inspector;
        final PGSimpleDataSource admin,runtime;
        JdbcDocumentStore store;
        Fixture()throws Exception {
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tenant.getBytes(java.nio.charset.StandardCharsets.UTF_8)))+"/"+document+"/",required("DKP_RUSTFS_COMPAT_PREFIX"));
            assertTrue(bytes.length>32);assertTrue(new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-"));
            temporary=Files.createTempDirectory(authority,"fault-acceptance-",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            Path marker=Files.createFile(temporary.resolve(".spool-authority"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            Files.writeString(marker,"dkp-private-spool-v1\n",java.nio.charset.StandardCharsets.US_ASCII);
            workspaces=new FileVerificationWorkspace(temporary);
            actual=S3UploadStorageAdapter.connect(configuration(),workspaces);
            assertTrue(actual.ready(),"Real private TLS/scoped RustFS is mandatory");
            actualCleanup=S3CleanupStorageAdapter.connect(new S3UploadStorageAdapter.Configuration(URI.create(required("DKP_RUSTFS_COMPAT_ENDPOINT")),"us-east-1",authority.resolve("cleanup-access-key"),authority.resolve("cleanup-secret-key"),authority.resolve("ca.pem"),temporary));
            assertTrue(actualCleanup.ready());storage=new Observed(actual,actualCleanup);
            Path deniedAuthority=Path.of(required("DKP_144_DENIED_STORAGE_AUTHORITY"));
            deniedStorage=S3CleanupStorageAdapter.connect(new S3UploadStorageAdapter.Configuration(URI.create(required("DKP_RUSTFS_COMPAT_ENDPOINT")),"us-east-1",deniedAuthority.resolve("access-key"),deniedAuthority.resolve("secret-key"),authority.resolve("ca.pem"),temporary));
            assertTrue(deniedStorage.ready(),"Real scoped read-only credential must retain versioning readiness but deny exact-version DELETE");
            scanner=new ClamAvScanAdapter(new InetSocketAddress(required("DKP_144_SCANNER_HOST"),Integer.parseInt(required("DKP_144_SCANNER_PORT"))),new StandardDefinitions(Path.of(required("DKP_144_SCANNER_DEFINITIONS")),clock),clock);
            assertTrue(scanner.ready(),"Real fresh standard-definition ClamAV is mandatory");
            assertEquals(PROFILE,required("DKP_144_INSPECTOR_PROFILE"));
            byte[] key=Base64.getDecoder().decode(Files.readString(Path.of(required("DKP_144_INSPECTOR_KEY"))).strip());
            inspector=new IsolatedInspectionAdapter(URI.create(required("DKP_144_INSPECTOR_ENDPOINT")),tls(Path.of(required("DKP_144_INSPECTOR_CA"))),key,PROFILE,required("DKP_144_INSPECTOR_IMAGE"),clock);Arrays.fill(key,(byte)0);
            postgres.start();admin=source(postgres.getUsername(),postgres.getPassword());
            try(var c=admin.getConnection();var sql=c.createStatement()){
                sql.execute("CREATE ROLE dkp_dm_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+password+"'");
                sql.execute("CREATE ROLE dkp_dm_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+password+"'");
                sql.execute("REVOKE ALL ON DATABASE dm FROM PUBLIC");sql.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
                sql.execute("GRANT CONNECT,CREATE ON DATABASE dm TO dkp_dm_migrator");sql.execute("GRANT CONNECT ON DATABASE dm TO dkp_dm_runtime");sql.execute("ALTER ROLE dkp_dm_runtime SET search_path=dm");
            }
            assertEquals(2,Flyway.configure().dataSource(source("dkp_dm_migrator",password)).defaultSchema("dm").schemas("dm").locations("classpath:db/migration").load().migrate().migrationsExecuted);
            runtime=source("dkp_dm_runtime",password);reopen();
            try(var c=runtime.getConnection();var sql=c.createStatement()){assertThrows(SQLException.class,()->sql.execute("CREATE TABLE dm.forbidden_fixture(id int)"));}
        }
        PGSimpleDataSource source(String user,String secret){var data=new PGSimpleDataSource();data.setURL(postgres.getJdbcUrl());data.setUser(user);data.setPassword(secret);data.setConnectTimeout(3);data.setSocketTimeout(3);return data;}
        void reset()throws Exception {
            clock.reset();storage.copies.set(0);storage.deletes.set(0);
            try(var c=admin.getConnection();var sql=c.createStatement()){
                sql.execute("TRUNCATE dm.documents CASCADE");sql.execute("TRUNCATE dm.upload_resource_accounts CASCADE");
                sql.execute("UPDATE dm.upload_global_resource_account SET admitted_count=0,reserved_transient_bytes=0,promised_original_bytes=0,retained_original_bytes=0,revision=0");
            }
            reopen();Set<AclRule> rules=new HashSet<>();for(Action action:Action.values())rules.add(new AclRule(ACTOR,action,AclRule.Effect.ALLOW));
            store.execute(r->{r.insert(new DocumentAggregate(document,tenant,ACTOR,clock.instant(),0,new Metadata("Public issue-144 synthetic PDF",""),rules,List.of(),List.of()));return null;});
        }
        void reopen(){store=new JdbcDocumentStore(runtime);}
        void restart(){
            // Physical owned PostgreSQL restart, not repository recreation. All remote
            // executor barriers remain in independent threads outside SQL transactions.
            DocumentAggregate documentBefore=doc();Accounts accountsBefore=accounts();Optional<DeletionFence> fenceBefore=fence();
            Instant previous=postmaster();String id=postgres.getContainerId();var docker=DockerClientFactory.instance().client();
            var before=docker.inspectContainerCmd(id).exec();assertEquals("true",before.getConfig().getLabels().get("org.testcontainers"));
            assertEquals(id,before.getId());String dataMountHash=mountHash(before);int previousPort=mappedPostgresPort(before);
            assertTrue(Arrays.equals(new int[]{previousPort},admin.getPortNumbers()),"Admin source must bind the observed owned port");assertTrue(Arrays.equals(new int[]{previousPort},runtime.getPortNumbers()),"Runtime source must bind the observed owned port");
            docker.restartContainerCmd(id).withTimeout(5).exec();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Instant current=null;String sqlStateCategory="NONE";
            var after=docker.inspectContainerCmd(id).exec();
            boolean sameContainer=id.equals(after.getId())&&id.equals(postgres.getContainerId());
            boolean sameDataMountHash=dataMountHash.equals(mountHash(after));
            assertTrue(sameContainer,"Restart must preserve the exact owned container");assertTrue(sameDataMountHash,"Restart must preserve every data mount");
            assertEquals("true",after.getConfig().getLabels().get("org.testcontainers"));assertEquals(before.getImageId(),after.getImageId());
            int actualPort=mappedPostgresPort(after);boolean mappedPortChanged=actualPort!=previousPort;
            // Docker Desktop can reassign an ephemeral published port on physical
            // restart. Refresh only that observed binding on the existing sources;
            // host/database/roles/secrets and the owned container/data stay fixed.
            if(mappedPortChanged){admin.setPortNumbers(new int[]{actualPort});runtime.setPortNumbers(new int[]{actualPort});}
            while(System.nanoTime()<until){try{current=postmaster();if(current.isAfter(previous))break;}catch(AssertionError unavailable){sqlStateCategory=sqlStateCategory(unavailable);}
                try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
            System.out.println("{\"diagnostic\":\"owned-postgres-restart\",\"issue\":165,\"sameContainer\":"+sameContainer+",\"sameDataMountHash\":"+sameDataMountHash+",\"mappedPortChanged\":"+mappedPortChanged+",\"sqlStateCategory\":\""+sqlStateCategory+"\"}");
            assertNotNull(current,"Owned SQL readiness must recover within 30 seconds");assertTrue(current.isAfter(previous),"Postmaster must genuinely restart");assertEquals(id,postgres.getContainerId());
            reopen();assertEquals(documentBefore,doc());assertEquals(accountsBefore,accounts());assertEquals(fenceBefore,fence());
        }
        int mappedPostgresPort(com.github.dockerjava.api.command.InspectContainerResponse metadata){
            var bindings=metadata.getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432));
            assertNotNull(bindings,"Owned PostgreSQL must retain its published port");assertTrue(bindings.length>0&&bindings.length<=4,"Owned PostgreSQL bindings must be bounded");
            Set<Integer> ports=new HashSet<>();
            for(var binding:bindings){
                assertNotNull(binding);assertTrue(Set.of("0.0.0.0","::","127.0.0.1","::1").contains(binding.getHostIp()),"Only explicit wildcard or loopback published bindings are permitted");
                String specification=binding.getHostPortSpec();assertNotNull(specification);assertTrue(specification.matches("[0-9]{1,5}"),"Published PostgreSQL port must be finite");
                int port=Integer.parseInt(specification);assertTrue(port>0&&port<=65535);ports.add(port);
            }
            assertEquals(1,ports.size(),"IPv4/IPv6 bindings must resolve to one unambiguous owned PostgreSQL port");return ports.iterator().next();
        }
        String mountHash(com.github.dockerjava.api.command.InspectContainerResponse metadata){
            var entries=metadata.getMounts().stream().map(m->String.join("\u0000",String.valueOf(m.getDriver()),String.valueOf(m.getMode()),String.valueOf(m.getName()),
                String.valueOf(m.getSource()),String.valueOf(m.getDestination()),String.valueOf(m.getRW()))).sorted().toList();
            assertFalse(entries.isEmpty(),"Owned PostgreSQL data mount must exist");
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",entries).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
            catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
        String sqlStateCategory(Throwable failure){
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql){String state=sql.getSQLState();return state!=null&&state.matches("[A-Z0-9]{5}")?state.substring(0,2):"UNKNOWN";}
            return "OTHER";
        }
        Instant postmaster(){try(var connection=admin.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT pg_postmaster_start_time()")){assertTrue(rows.next());return rows.getTimestamp(1).toInstant();}catch(SQLException unavailable){throw new AssertionError("Owned database is not yet ready",unavailable);}}
        S3UploadStorageAdapter.Configuration configuration(){return new S3UploadStorageAdapter.Configuration(URI.create(required("DKP_RUSTFS_COMPAT_ENDPOINT")),"us-east-1",authority.resolve("access-key"),authority.resolve("secret-key"),authority.resolve("ca.pem"),temporary);}
        UploadService upload(UploadStoragePort storage){return new UploadService(store,grants,storage,scanner,inspector,clock,temporary,workspaces);}
        UploadCleanupService cleanup(CleanupStoragePort storage){return new UploadCleanupService(store,storage,clock);}
        DocumentService documents(DocumentUnitOfWork uow){
            var delegation=new DelegationPolicyPort(){public AuthorityDecision targetGrant(Actor target,String tenant,Action action){throw new AssertionError("Unused delegation path");}public AuthorityDecision ceiling(Actor actor,String tenant,Action action){throw new AssertionError("Unused delegation path");}};
            var cursor=new DocumentCursorPort(){public PagePosition decode(String value,Actor actor,String tenant){throw new AssertionError("Unused cursor path");}public String encode(PagePosition value,Actor actor,String tenant){throw new AssertionError("Unused cursor path");}};
            return new DocumentService(uow,grants,delegation,new ReceiptArtifactAuthority(uow,storage,clock,temporary),cursor,clock);
        }
        UUID quarantined(){UUID id=upload(storage).initialize(caller,UUID.randomUUID().toString(),document,doc().revision(),UploadFormat.PDF,bytes.length).uploadId();upload(storage).transfer(caller,id,"body",new ByteArrayInputStream(bytes));assertEquals(UploadState.QUARANTINED,slot(id).state());return id;}
        UUID failedKnownCopy(){UUID id=quarantined();var failed=new Forwarding(storage){@Override public InputStream open(ObjectIdentity object,CompleteBudget.Phase phase){if(object.objectKey().bucket().equals("dkp-originals"))throw new DmFailure(503,"UNAVAILABLE");return super.open(object,phase);}};denied(503,()->upload(failed).complete(caller,id,"failed-copy"));assertTrue(slot(id).writerSettled());assertNotNull(slot(id).original());return id;}
        UploadSlot slot(UUID id){return store.execute(r->r.uploads().slot(tenant,id).orElseThrow());}
        DocumentAggregate doc(){return store.execute(r->r.find(tenant,document).orElseThrow());}
        Accounts accounts(){return store.execute(r->r.uploads().lockAccounts(tenant));}
        Optional<DeletionFence> fence(){return store.execute(r->r.uploads().unresolvedFence(tenant,document));}
        List<CleanupWork> pending(UUID upload){return store.execute(r->r.uploads().pendingCleanup(tenant,upload));}
        CleanupWork originalWork(UUID upload){return pending(upload).stream().filter(w->w.key().bucket().equals("dkp-originals")).findFirst().orElseThrow();}
        void fullCharge(int count){Accounts a=accounts();for(ResourceAccount account:List.of(a.global(),a.tenant())){assertEquals(count,account.admittedCount());assertEquals(count*2L*bytes.length,account.transientBytes());assertEquals(count*(long)bytes.length,account.promisedBytes());assertEquals(0,account.retainedBytes());}}
        int physicalCount(UploadSlot slot){int count=0;if(slot.quarantine()!=null)count+=storage.versions(slot.quarantine().objectKey()).size();if(slot.possibleOriginal()!=null)count+=storage.versions(slot.possibleOriginal()).size();return count;}
        void physicalCopies(UploadSlot slot,int expected){int count=physicalCount(slot);assertEquals(expected,count);assertTrue(count*(long)bytes.length<=slot.transientReservation());}
        void denied(int status,Runnable action){assertEquals(status,assertThrows(DmFailure.class,action::run).status());}
        void noNewHold(){DocumentAggregate before=doc();long commands=count("command_outcomes"),audits=count("audit_facts");denied(503,()->documents(store).placeHold(caller,document,before.revision(),UUID.randomUUID().toString(),"Blocked unresolved deletion"));assertEquals(before,doc());assertEquals(commands,count("command_outcomes"));assertEquals(audits,count("audit_facts"));}
        void documentAuthority(boolean available)throws Exception{try(var c=admin.getConnection();var sql=c.createStatement()){sql.execute(available?"GRANT SELECT ON dm.documents TO dkp_dm_runtime":"REVOKE SELECT ON dm.documents FROM dkp_dm_runtime");}}
        long count(String table){if(!Set.of("upload_receipts","command_outcomes","upload_deletion_settlements","audit_facts","upload_receipt_consumptions").contains(table))throw new IllegalArgumentException();try(var c=runtime.getConnection();var sql=c.createStatement();var result=sql.executeQuery("SELECT count(*) FROM dm."+table)){result.next();return result.getLong(1);}catch(SQLException failure){throw new AssertionError("Public fixture count unavailable",failure);}}
        DeletionFence attempt(ObjectIdentity object){return new DeletionFence(UUID.randomUUID(),tenant,document,UUID.randomUUID(),object,UUID.randomUUID(),clock.instant(),FencePhase.DISPATCHED);}
        byte[] read(ObjectIdentity object)throws Exception{try(var input=storage.open(object)){return input.readAllBytes();}}
        public void close()throws Exception{inspector.close();actual.close();workspaces.close();actualCleanup.close();deniedStorage.close();postgres.stop();try(var files=Files.walk(temporary)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Required real-provider profile missing: "+name);return value;}
    private static SSLContext tls(Path ca)throws Exception {
        assertFalse(Files.isSymbolicLink(ca));KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);int i=0;
        try(var in=Files.newInputStream(ca)){for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(in)){var x509=(X509Certificate)certificate;assertTrue(x509.getBasicConstraints()>=0);x509.checkValidity();trust.setCertificateEntry("public-ca-"+i++,x509);}}
        assertTrue(i>0);var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,managers.getTrustManagers(),null);return tls;
    }
}
