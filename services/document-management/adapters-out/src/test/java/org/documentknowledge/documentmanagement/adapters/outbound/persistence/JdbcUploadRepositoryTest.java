package org.documentknowledge.documentmanagement.adapters.outbound.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.upload.UploadRepository;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.domain.upload.*;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcUploadRepositoryTest {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("dm").withUsername("postgres").withPassword(UUID.randomUUID().toString())
        .withCommand("postgres","-c","fsync=on","-c","synchronous_commit=on","-c","full_page_writes=on");
    static final String PASSWORD=UUID.randomUUID().toString();
    static final Instant NOW=Instant.parse("2026-10-31T12:00:00Z");
    static final Principal ACTOR=new Principal("https://issuer.example","upload-staff");
    static PGSimpleDataSource admin,runtime,migrator;
    static JdbcDocumentStore store;
    static Flyway flyway;
    private final Map<UUID,String> tenants=new HashMap<>();
    static PGSimpleDataSource source(String user,String password) {
        var source=new PGSimpleDataSource();source.setURL(POSTGRES.getJdbcUrl());source.setUser(user);source.setPassword(password);return source;
    }
    @BeforeAll static void setup()throws Exception {
        POSTGRES.start();admin=source(POSTGRES.getUsername(),POSTGRES.getPassword());
        try(Connection connection=admin.getConnection();Statement sql=connection.createStatement()) {
            sql.execute("CREATE ROLE dkp_dm_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+PASSWORD+"'");
            sql.execute("CREATE ROLE dkp_dm_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+PASSWORD+"'");
            sql.execute("REVOKE ALL ON DATABASE dm FROM PUBLIC");sql.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            sql.execute("GRANT CONNECT,CREATE ON DATABASE dm TO dkp_dm_migrator");sql.execute("GRANT CONNECT ON DATABASE dm TO dkp_dm_runtime");
            sql.execute("ALTER ROLE dkp_dm_runtime SET search_path=dm");
        }
        migrator=source("dkp_dm_migrator",PASSWORD);runtime=source("dkp_dm_runtime",PASSWORD);
        flyway=Flyway.configure().dataSource(migrator).defaultSchema("dm").schemas("dm").locations("classpath:db/migration").load();
        assertEquals(2,flyway.migrate().migrationsExecuted);store=new JdbcDocumentStore(runtime);
    }
    @AfterAll static void stop(){POSTGRES.stop();}
    @BeforeEach void reset()throws Exception {
        try(Connection connection=admin.getConnection();Statement sql=connection.createStatement()) {
            sql.execute("TRUNCATE dm.documents CASCADE");sql.execute("TRUNCATE dm.upload_resource_accounts CASCADE");
            sql.execute("UPDATE dm.upload_global_resource_account SET admitted_count=0,reserved_transient_bytes=0,promised_original_bytes=0,retained_original_bytes=0,revision=0");
        }
    }
    DocumentAggregate document(String tenant) {
        var document=new DocumentAggregate(UUID.randomUUID(),tenant,ACTOR,NOW,0,new Metadata("Synthetic upload fixture",""),Set.of(),List.of(),List.of());
        tenants.put(document.documentId(),tenant);
        store.execute(repository->{repository.insert(document);return null;});return document;
    }
    ObjectKey key(String bucket,UUID document){try{return new ObjectKey(bucket,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tenants.get(document).getBytes(java.nio.charset.StandardCharsets.UTF_8)))+"/"+document+"/"+UUID.randomUUID());}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    UploadSlot initialize(DocumentAggregate document) {
        var slot=UploadSlot.initialize(UUID.randomUUID(),document.tenant(),document.documentId(),ACTOR,NOW,document.revision(),UploadFormat.PDF,100);
        store.execute(repository->{var uploads=repository.uploads();var accounts=uploads.lockAccounts(slot.tenant());repository.find(slot.tenant(),slot.documentId());uploads.saveAccounts(slot.tenant(),accounts,new Accounts(accounts.global().change(1,200,100,0),accounts.tenant().change(1,200,100,0)));uploads.insertSlot(slot);return null;});return slot;
    }
    record Verified(UploadSlot slot,UploadReceipt receipt,VerificationEvidence evidence) { }
    Verified verified(DocumentAggregate document) {
        UploadSlot initial=initialize(document);ObjectIdentity source=new ObjectIdentity(key("dkp-quarantine",document.documentId()),"source-v1");ObjectIdentity original=new ObjectIdentity(key("dkp-originals",document.documentId()),"original-v1");
        return store.execute(repository->{var uploads=repository.uploads();uploads.lockAccounts(initial.tenant());repository.find(initial.tenant(),initial.documentId());
            UploadSlot slot=uploads.slot(initial.tenant(),initial.uploadId()).orElseThrow();
            var next=slot.receiving(UUID.randomUUID(),NOW.plusSeconds(300),"body-key");uploads.updateSlot(next,slot.revision());slot=next;
            next=slot.quarantined(source,"b".repeat(64));uploads.updateSlot(next,slot.revision());slot=next;
            next=slot.verifying(UUID.randomUUID(),NOW.plusSeconds(300));uploads.updateSlot(next,slot.revision());slot=next;
            next=slot.possibleCopy(original.objectKey());uploads.updateSlot(next,slot.revision());slot=next;
            next=slot.copied(original);uploads.updateSlot(next,slot.revision());slot=next;
            var evidence=new VerificationEvidence(UUID.randomUUID(),slot.tenant(),slot.uploadId(),source,new ScanEvidence("b".repeat(64),100,"1.5.4","standard-database",NOW.minusSeconds(3600),"c".repeat(64),NOW),new InspectionEvidence(UploadFormat.PDF,UploadFormat.PDF.mime(),100,"b".repeat(64),1,"1","d".repeat(64),"sha256:"+"e".repeat(64),NOW),NOW);
            uploads.insertEvidence(evidence);
            var receipt=new UploadReceipt(UUID.randomUUID(),slot.uploadId(),slot.tenant(),slot.documentId(),ACTOR,"b".repeat(64),100,UploadFormat.PDF,original,evidence.evidenceId(),NOW.plusSeconds(1),slot.expiresAt());uploads.insertReceipt(receipt);
            next=slot.verified();uploads.updateSlot(next,slot.revision());return new Verified(next,receipt,evidence);
        });
    }
    CommandKey command(UploadSlot slot,String key){return new CommandKey(new Actor(ACTOR.issuer(),ACTOR.subject()),slot.tenant(),"upload.initialize",slot.documentId().toString(),key);}
    UploadRepository.UploadAudit audit(UploadSlot slot){return new UploadRepository.UploadAudit(UUID.randomUUID(),slot.tenant(),slot.documentId(),slot.uploadId(),ACTOR,"upload.initialize",slot.revision(),NOW,"OK");}
    DeletionFence fence(UploadSlot slot,ObjectIdentity object){return new DeletionFence(UUID.randomUUID(),slot.tenant(),slot.documentId(),slot.uploadId(),object,UUID.randomUUID(),NOW,FencePhase.AUTHORIZED);}
    void enqueue(UploadSlot slot,ObjectIdentity object) {store.execute(r->{r.uploads().enqueueCleanup(new CleanupWork(UUID.randomUUID(),slot.tenant(),slot.uploadId(),slot.documentId(),object.objectKey(),object,CleanupReason.EXPIRED,NOW,false));return null;});}
    long count(String table)throws Exception {try(Connection c=admin.getConnection();Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT count(*) FROM dm."+table)){rows.next();return rows.getLong(1);}}

    @Test void realMigrationsReenterAndImmutableRuntimeFactsAreProtected()throws Exception {
        assertEquals(0,flyway.migrate().migrationsExecuted);flyway.validate();
        try(Connection connection=runtime.getConnection();Statement sql=connection.createStatement()) {
            for(String table:List.of("upload_receipts","upload_verification_facts","upload_receipt_consumptions","upload_command_outcomes","upload_audit_facts","upload_deletion_settlements")) {
                assertThrows(SQLException.class,()->sql.execute("DELETE FROM dm."+table));assertThrows(SQLException.class,()->sql.execute("UPDATE dm."+table+" SET tenant_id='changed'"));
            }
            for(String forbidden:List.of("CREATE TABLE dm.bad(id int)","DROP TABLE dm.upload_slots","DELETE FROM dm.flyway_schema_history","CREATE TABLE public.bad(id int)","SET ROLE dkp_dm_migrator"))assertThrows(SQLException.class,()->sql.execute(forbidden));
        }
    }
    @Test void allVerifiedFactsRoundTripAndWrongTenantCannotResolveThem() {
        var verified=verified(document("tenant-a"));
        store.execute(r->{var uploads=r.uploads();assertEquals(verified.slot(),uploads.slot("tenant-a",verified.slot().uploadId()).orElseThrow());assertEquals(verified.receipt(),uploads.receipt("tenant-a",verified.receipt().receiptId()).orElseThrow());assertEquals(verified.receipt(),uploads.receiptForUpload("tenant-a",verified.slot().uploadId()).orElseThrow());assertEquals(verified.evidence(),uploads.evidence("tenant-a",verified.evidence().evidenceId()).orElseThrow());assertTrue(uploads.slot("tenant-b",verified.slot().uploadId()).isEmpty());assertTrue(uploads.receipt("tenant-b",verified.receipt().receiptId()).isEmpty());assertTrue(uploads.receiptForUpload("tenant-b",verified.slot().uploadId()).isEmpty());assertTrue(uploads.evidence("tenant-b",verified.evidence().evidenceId()).isEmpty());assertTrue(uploads.consumption("tenant-a",verified.receipt().receiptId()).isEmpty());return null;});
    }
    @Test void exactEvidenceOriginalVersionAndTenantKeyBindingsCannotBeChangedByRuntimeSql()throws Exception {
        var fixture=verified(document("exact-binding"));
        var staleAtFinal=new VerificationEvidence(UUID.randomUUID(),fixture.slot().tenant(),fixture.slot().uploadId(),fixture.evidence().source(),new ScanEvidence("b".repeat(64),100,"1.5.4","standard-database",NOW.minusSeconds(86460),"c".repeat(64),NOW.minusSeconds(120)),fixture.evidence().inspection(),NOW);
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().insertEvidence(staleAtFinal);return null;}));assertEquals(1,count("upload_verification_facts"));
        try(Connection c=runtime.getConnection();Statement sql=c.createStatement()) {
            SQLException wrongOriginal=assertThrows(SQLException.class,()->sql.execute("INSERT INTO dm.upload_receipts SELECT tenant_id,gen_random_uuid(),upload_id,document_id,actor_issuer,actor_subject,size_bytes,sha256,detected_format,detected_mime,original_bucket,original_key,'different-version',verification_evidence_id,created_at,expires_at FROM dm.upload_receipts"));
            assertEquals("23514",wrongOriginal.getSQLState());
            SQLException changedBody=assertThrows(SQLException.class,()->sql.execute("UPDATE dm.upload_slots SET body_sha256=repeat('c',64),state_revision=state_revision+1"));assertEquals("23514",changedBody.getSQLState());
            SQLException wrongTenantKey=assertThrows(SQLException.class,()->sql.execute("UPDATE dm.upload_slots SET possible_original_key=repeat('0',64)||substring(possible_original_key FROM 65),state_revision=state_revision+1"));assertEquals("23514",wrongTenantKey.getSQLState());
            assertEquals("23514",assertThrows(SQLException.class,()->sql.execute("UPDATE dm.upload_slots SET possible_original_version_id='replacement',state_revision=state_revision+1")).getSQLState());
            SQLException overReserved=assertThrows(SQLException.class,()->sql.execute("UPDATE dm.upload_slots SET transient_reservation=201,state_revision=state_revision+1"));assertEquals("23514",overReserved.getSQLState());
        }
        assertEquals(fixture.slot(),store.execute(r->r.uploads().slot(fixture.slot().tenant(),fixture.slot().uploadId()).orElseThrow()));assertEquals(1,count("upload_receipts"));
        assertThrows(IllegalArgumentException.class,()->store.execute(r->{r.uploads().insertSlot(fixture.slot());return null;}));
    }
    @Test void admissionAccountsCasAndTransactionFailureNeverPartiallyCharge() {
        var document=document("accounts");var slot=initialize(document);
        var before=store.execute(r->r.uploads().lockAccounts(slot.tenant()));assertEquals(1,before.tenant().admittedCount());assertEquals(200,before.global().transientBytes());
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().saveAccounts(slot.tenant(),before,before);return null;}));
        assertThrows(IllegalStateException.class,()->store.execute(r->{var uploads=r.uploads();var current=uploads.lockAccounts(slot.tenant());uploads.saveAccounts(slot.tenant(),current,new Accounts(current.global().change(1,200,100,0),current.tenant().change(1,200,100,0)));throw new IllegalStateException("rollback");}));
        assertEquals(before,store.execute(r->r.uploads().lockAccounts(slot.tenant())));
        var changed=new Accounts(before.global().change(0,0,0,0),before.tenant().change(0,0,0,0));store.execute(r->{r.uploads().saveAccounts(slot.tenant(),before,changed);return null;});
        assertEquals(409,assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().saveAccounts(slot.tenant(),before,changed);return null;})).status());
    }
    @Test void concurrentAdmissionsCannotExceedFiveAndGlobalChargesRemainExact()throws Exception {
        var document=document("race");
        try(var executor=Executors.newFixedThreadPool(6)) {
            List<Callable<Boolean>> jobs=new ArrayList<>();for(int i=0;i<6;i++)jobs.add(()->{try{initialize(document);return true;}catch(DmFailure failure){return false;}});
            var results=executor.invokeAll(jobs);int successes=0;for(var result:results)if(result.get())successes++;assertEquals(5,successes);
        }
        var accounts=store.execute(r->r.uploads().lockAccounts(document.tenant()));assertEquals(5,accounts.tenant().admittedCount());assertEquals(1000,accounts.global().transientBytes());assertEquals(5,count("upload_slots"));
    }
    @Test void globalCapacityIsSharedAcrossTenantsAndFailedAdmissionRetainsExactBalances() {
        var first=document("global-a");var second=document("global-b");var denied=document("global-c");
        for(int i=0;i<5;i++){initialize(first);initialize(second);}
        assertThrows(DmFailure.class,()->initialize(denied));
        var accounts=store.execute(r->r.uploads().lockAccounts(denied.tenant()));assertEquals(10,accounts.global().admittedCount());assertEquals(2000,accounts.global().transientBytes());assertEquals(1000,accounts.global().promisedBytes());assertEquals(0,accounts.tenant().admittedCount());
    }
    @Test void retainedOriginalCapacityCannotBeSilentlyReusedForNewPromises() {
        var exhausted=document("retained-capacity");
        store.execute(r->{var u=r.uploads();var before=u.lockAccounts(exhausted.tenant());u.saveAccounts(exhausted.tenant(),before,new Accounts(before.global().change(0,0,0,5000000000L),before.tenant().change(0,0,0,5000000000L)));return null;});
        var before=store.execute(r->r.uploads().lockAccounts(exhausted.tenant()));
        assertThrows(DmFailure.class,()->initialize(exhausted));
        assertEquals(before,store.execute(r->r.uploads().lockAccounts(exhausted.tenant())));
    }
    @Test void slotStateCasAndInvalidSourceMutationAreRejectedAtomically()throws Exception {
        var slot=initialize(document("slot"));var receiving=slot.receiving(UUID.randomUUID(),NOW.plusSeconds(300),"body");store.execute(r->{r.uploads().updateSlot(receiving,0);return null;});
        assertEquals(409,assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().updateSlot(receiving,0);return null;})).status());
        assertEquals(409,assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().updateSlot(receiving,5);return null;})).status());
        try(Connection c=runtime.getConnection();Statement s=c.createStatement()) {
            assertThrows(SQLException.class,()->s.execute("UPDATE dm.upload_slots SET declared_size=99,state_revision=state_revision+1"));
            assertThrows(SQLException.class,()->s.execute("UPDATE dm.upload_slots SET state='CONSUMED',lease_id=NULL,lease_until=NULL,state_revision=state_revision+1"));
        }
        assertEquals(receiving,store.execute(r->r.uploads().slot(slot.tenant(),slot.uploadId()).orElseThrow()));
    }
    @Test void uploadCommandOutcomeAuditAndRollbackShareTheDocumentTransaction()throws Exception {
        var slot=initialize(document("commands"));var key=command(slot,"key");var outcome=new UploadRepository.UploadOutcome(key,"a".repeat(64),201,slot.uploadId(),null);
        assertTrue(store.execute(r->r.uploads().command(key)).isEmpty());
        assertThrows(IllegalStateException.class,()->store.execute(r->{r.uploads().record(outcome,audit(slot));throw new IllegalStateException("after audit");}));assertEquals(0,count("upload_audit_facts"));
        store.execute(r->{r.uploads().record(outcome,audit(slot));return null;});assertEquals(outcome,store.execute(r->r.uploads().command(key).orElseThrow()));assertEquals(1,count("upload_audit_facts"));
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().record(outcome,audit(slot));return null;}));assertEquals(1,count("upload_audit_facts"));
    }
    @Test void versionConsumptionAndAccountReclassificationAreOneAtomicCommit()throws Exception {
        var doc=document("consume");var fixture=verified(doc);var receipt=fixture.receipt();UUID version=UUID.randomUUID();var consumption=new ReceiptConsumption(doc.tenant(),receipt.receiptId(),doc.documentId(),version,ACTOR,"append-key","a".repeat(64),NOW.plusSeconds(2));
        assertThrows(IllegalStateException.class,()->store.execute(r->{append(r,doc,fixture,consumption);throw new IllegalStateException("before commit");}));
        assertEquals(0,count("upload_receipt_consumptions"));assertEquals(0,count("versions"));assertEquals(0,store.execute(r->r.find(doc.tenant(),doc.documentId()).orElseThrow()).revision());
        store.execute(r->{append(r,doc,fixture,consumption);return null;});assertEquals(consumption,store.execute(r->r.uploads().consumption(doc.tenant(),receipt.receiptId()).orElseThrow()));assertEquals(1,count("versions"));
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().consume(consumption);return null;}));
        var accounts=store.execute(r->r.uploads().lockAccounts(doc.tenant()));assertEquals(0,accounts.tenant().admittedCount());assertEquals(100,accounts.tenant().retainedBytes());assertEquals(100,accounts.tenant().transientBytes());
    }
    void append(DocumentRepository r,DocumentAggregate doc,Verified fixture,ReceiptConsumption consumption) {
        append(r,doc,fixture,consumption,fixture.receipt().receiptId().toString());
    }
    void append(DocumentRepository r,DocumentAggregate doc,Verified fixture,ReceiptConsumption consumption,String artifactReference) {
        var uploads=r.uploads();var accounts=uploads.lockAccounts(doc.tenant());r.find(doc.tenant(),doc.documentId());uploads.slot(doc.tenant(),fixture.slot().uploadId());uploads.receipt(doc.tenant(),fixture.receipt().receiptId());
        var version=new DocumentVersion(consumption.versionId(),doc.documentId(),1,fixture.receipt().sha256(),fixture.receipt().format().mime(),artifactReference,"rustfs",ACTOR,NOW.plusSeconds(2));
        r.update(doc.next(doc.metadata(),doc.rules(),doc.holds(),List.of(version)),0);uploads.consume(consumption);uploads.updateSlot(fixture.slot().consumed(),fixture.slot().revision());
        uploads.saveAccounts(doc.tenant(),accounts,new Accounts(accounts.global().change(-1,-100,-100,100),accounts.tenant().change(-1,-100,-100,100)));
        var key=new CommandKey(new Actor(ACTOR.issuer(),ACTOR.subject()),doc.tenant(),"document.version.append",doc.documentId().toString(),consumption.commandKey());
        r.record(new StoredCommand(key,consumption.fingerprint(),201,new CommandReceipt(doc.documentId(),1)),new AuditFact(UUID.randomUUID(),doc.documentId(),doc.tenant(),key.actor(),key.operation(),1,consumption.consumedAt()));
    }
    @Test void wrongReceiptArtifactReferenceRollsBackVersionConsumptionAndAllBookkeeping()throws Exception {
        var doc=document("wrong-receipt-reference");var fixture=verified(doc);var receipt=fixture.receipt();
        var consumption=new ReceiptConsumption(doc.tenant(),receipt.receiptId(),doc.documentId(),UUID.randomUUID(),ACTOR,"append-key","a".repeat(64),NOW.plusSeconds(2));
        var before=store.execute(r->r.uploads().lockAccounts(doc.tenant()));
        var counts=new HashMap<String,Long>();
        for(String table:List.of("versions","upload_receipt_consumptions","command_outcomes","audit_facts","upload_receipts","upload_verification_facts","upload_audit_facts"))counts.put(table,count(table));
        String wrongReference=UUID.randomUUID().toString();assertNotEquals(receipt.receiptId().toString(),wrongReference);
        assertEquals(503,assertThrows(DmFailure.class,()->store.execute(r->{append(r,doc,fixture,consumption,wrongReference);return null;})).status());
        for(var entry:counts.entrySet())assertEquals(entry.getValue().longValue(),count(entry.getKey()),entry.getKey());
        assertEquals(doc,store.execute(r->r.find(doc.tenant(),doc.documentId()).orElseThrow()));
        assertEquals(fixture.slot(),store.execute(r->r.uploads().slot(doc.tenant(),fixture.slot().uploadId()).orElseThrow()));
        assertEquals(before,store.execute(r->r.uploads().lockAccounts(doc.tenant())));
        assertTrue(store.execute(r->r.uploads().consumption(doc.tenant(),receipt.receiptId())).isEmpty());
        store.execute(r->{append(r,doc,fixture,consumption);return null;});
        assertEquals(receipt.receiptId().toString(),store.execute(r->r.find(doc.tenant(),doc.documentId()).orElseThrow()).versions().getFirst().originalArtifactRef());
        assertEquals(consumption,store.execute(r->r.uploads().consumption(doc.tenant(),receipt.receiptId()).orElseThrow()));
    }
    @Test void cleanupQueueRetainsUnknownIdentityAndCompletesKnownWorkExactlyOnce() {
        var slot=initialize(document("cleanup"));var key=key("dkp-originals",slot.documentId());var unknown=new CleanupWork(UUID.randomUUID(),slot.tenant(),slot.uploadId(),slot.documentId(),key,null,CleanupReason.FAILED_VERIFIED_COPY,NOW,false);
        store.execute(r->{r.uploads().enqueueCleanup(unknown);r.uploads().enqueueCleanup(unknown);return null;});assertEquals(unknown,store.execute(r->r.uploads().cleanup(slot.tenant(),unknown.workId()).orElseThrow()));assertTrue(store.execute(r->r.uploads().cleanup("other",unknown.workId())).isEmpty());
        assertEquals(List.of(unknown),store.execute(r->r.uploads().pendingCleanup(slot.tenant(),slot.uploadId())));
        store.execute(r->{r.uploads().completeCleanup(unknown);return null;});assertTrue(store.execute(r->r.uploads().cleanup(slot.tenant(),unknown.workId()).orElseThrow()).complete());assertTrue(store.execute(r->r.uploads().pendingCleanup(slot.tenant(),slot.uploadId())).isEmpty());
        assertEquals(409,assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().completeCleanup(unknown);return null;})).status());
    }
    @Test void holdFirstBlocksDeletionAndFenceFirstBlocksNewHoldWithoutPartialRevision() {
        var doc=document("fence");var slot=initialize(doc);var object=new ObjectIdentity(key("dkp-quarantine",doc.documentId()),"delete-v1");var fence=fence(slot,object);
        store.execute(r->{var uploads=r.uploads();uploads.lockAccounts(doc.tenant());r.find(doc.tenant(),doc.documentId());uploads.insertFence(fence);return null;});assertEquals(fence,store.execute(r->r.uploads().unresolvedFence(doc.tenant(),doc.documentId()).orElseThrow()));
        assertThrows(DmFailure.class,()->store.execute(r->{r.update(doc.next(doc.metadata(),doc.rules(),List.of(new LegalHold(UUID.randomUUID(),"must not claim success",ACTOR,NOW)),doc.versions()),0);return null;}));
        assertEquals(0,store.execute(r->r.find(doc.tenant(),doc.documentId()).orElseThrow()).revision());
        var other=document("hold-first");var otherSlot=initialize(other);store.execute(r->{r.update(other.next(other.metadata(),other.rules(),List.of(new LegalHold(UUID.randomUUID(),"preserve",ACTOR,NOW)),other.versions()),0);return null;});
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().insertFence(fence(otherSlot,object));return null;}));
    }
    @Test void racingHoldAndDeletionAuthorizationSerializeOnDocumentWithoutPartialSuccess()throws Exception {
        var doc=document("hold-race");var slot=initialize(doc);var attempt=fence(slot,new ObjectIdentity(key("dkp-quarantine",doc.documentId()),"race-version"));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var deletion=executor.submit(()->{start.await();try{store.execute(r->{r.uploads().lockAccounts(doc.tenant());r.find(doc.tenant(),doc.documentId());r.uploads().slot(doc.tenant(),slot.uploadId());r.uploads().insertFence(attempt);return null;});return 200;}catch(DmFailure denied){return denied.status();}});
            var hold=executor.submit(()->{start.await();try{store.execute(r->{r.uploads().lockAccounts(doc.tenant());var current=r.find(doc.tenant(),doc.documentId()).orElseThrow();r.update(current.next(current.metadata(),current.rules(),List.of(new LegalHold(UUID.randomUUID(),"racing preservation",ACTOR,NOW)),current.versions()),0);return null;});return 201;}catch(DmFailure denied){return denied.status();}});
            start.countDown();int deleted=deletion.get(),held=hold.get();assertTrue(deleted==200&&held==503||deleted==503&&held==201);
            var actual=store.execute(r->r.find(doc.tenant(),doc.documentId()).orElseThrow());
            assertEquals(held==201?1:0,actual.holds().size());assertEquals(held==201?1:0,actual.revision());assertEquals(deleted==200,store.execute(r->r.uploads().unresolvedFence(doc.tenant(),doc.documentId()).isPresent()));
        }
    }
    @Test void exactFenceDispatchSettlementAndReplayCannotTransferExecution() {
        var doc=document("settle");var slot=initialize(doc);var fence=fence(slot,new ObjectIdentity(key("dkp-quarantine",doc.documentId()),"v1"));
        store.execute(r->{r.uploads().insertFence(fence);return null;});var dispatched=fence.phase(FencePhase.DISPATCHED);
        store.execute(r->{r.uploads().dispatchFence(fence,dispatched);return null;});assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().dispatchFence(fence,dispatched);return null;}));
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().dispatchFence(dispatched,dispatched);return null;}));
        var terminal=new DeletionFence(fence.attemptId(),fence.tenant(),fence.documentId(),fence.uploadId(),fence.object(),fence.executorId(),fence.createdAt(),FencePhase.SETTLED_SUCCESS);
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().settleFence(fence,terminal,"request",NOW);return null;}));assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().settleFence(dispatched,terminal,"",NOW);return null;}));
        store.execute(r->{r.uploads().settleFence(dispatched,terminal,"bound-request",NOW);return null;});assertTrue(store.execute(r->r.uploads().unresolvedFence(doc.tenant(),doc.documentId())).isEmpty());
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().settleFence(dispatched,terminal,"second-request",NOW);return null;}));
        store.execute(r->{r.update(doc.next(doc.metadata(),doc.rules(),List.of(new LegalHold(UUID.randomUUID(),"now protected",ACTOR,NOW)),doc.versions()),0);return null;});
    }
    @Test void concurrentDistinctAppendCommandsHaveOneConsumptionAndOneVersion()throws Exception {
        var doc=document("consume-race");var fixture=verified(doc);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> work=()->{try{store.execute(r->{append(r,doc,fixture,new ReceiptConsumption(doc.tenant(),fixture.receipt().receiptId(),doc.documentId(),UUID.randomUUID(),ACTOR,UUID.randomUUID().toString(),"a".repeat(64),NOW.plusSeconds(2)));return null;});return 201;}catch(DmFailure denied){return denied.status();}};
            var results=executor.invokeAll(List.of(work,work));assertEquals(Set.of(201,409),Set.of(results.get(0).get(),results.get(1).get()));
        }
        assertEquals(1,count("upload_receipt_consumptions"));assertEquals(1,count("versions"));assertEquals(1,count("command_outcomes"));assertEquals(1,count("audit_facts"));
    }
    @Test void failedTerminalAttemptPreservesObjectChargesAndAllowsFreshAuthorizedAttempt() {
        var doc=document("failed-delete");var slot=initialize(doc);var fence=fence(slot,new ObjectIdentity(key("dkp-quarantine",doc.documentId()),"v1"));
        store.execute(r->{r.uploads().insertFence(fence);return null;});var dispatched=fence.phase(FencePhase.DISPATCHED);store.execute(r->{r.uploads().dispatchFence(fence,dispatched);return null;});
        var terminal=new DeletionFence(fence.attemptId(),fence.tenant(),fence.documentId(),fence.uploadId(),fence.object(),fence.executorId(),fence.createdAt(),FencePhase.SETTLED_FAILURE);
        store.execute(r->{r.uploads().settleFence(dispatched,terminal,"known-terminal-failure",NOW);return null;});
        assertEquals(200,store.execute(r->r.uploads().lockAccounts(doc.tenant())).tenant().transientBytes());
        store.execute(r->{r.uploads().insertFence(fence(slot,fence.object()));return null;});
    }
    @Test void internalSweepDiscoveryIsBoundedPersistentAndSkipsFutureOrCompletedWork() {
        var doc=document("sweep");var first=initialize(doc);var second=initialize(doc);
        assertTrue(store.execute(r->r.uploads().expirable(NOW.plusSeconds(3599),50)).isEmpty());
        assertEquals(1,store.execute(r->r.uploads().expirable(NOW.plusSeconds(3600),1)).size());assertEquals(2,store.execute(r->r.uploads().expirable(NOW.plusSeconds(3600),50)).size());
        for(int invalid:List.of(0,51)) {
            assertThrows(IllegalArgumentException.class,()->store.execute(r->r.uploads().expirable(NOW,invalid)));
            assertThrows(IllegalArgumentException.class,()->store.execute(r->r.uploads().dueCleanup(NOW,invalid)));
        }
        var past=new CleanupWork(UUID.randomUUID(),first.tenant(),first.uploadId(),first.documentId(),key("dkp-quarantine",doc.documentId()),null,CleanupReason.EXPIRED,NOW.minusSeconds(1),false);
        var future=new CleanupWork(UUID.randomUUID(),second.tenant(),second.uploadId(),second.documentId(),key("dkp-quarantine",doc.documentId()),null,CleanupReason.EXPIRED,Instant.parse("2200-01-01T00:00:00Z"),false);
        store.execute(r->{r.uploads().enqueueCleanup(past);r.uploads().enqueueCleanup(future);return null;});assertEquals(List.of(past),store.execute(r->r.uploads().dueCleanup(NOW,1)));
        store.execute(r->{r.uploads().deferCleanup(past,NOW.plusSeconds(60));return null;});assertTrue(store.execute(r->r.uploads().dueCleanup(NOW,50)).isEmpty());assertEquals(List.of(past),store.execute(r->r.uploads().dueCleanup(NOW.plusSeconds(60),50)));
        store.execute(r->{r.uploads().completeCleanup(past);var slot=r.uploads().slot(first.tenant(),first.uploadId()).orElseThrow();r.uploads().updateSlot(slot.terminal(UploadState.EXPIRED,"UPLOAD_EXPIRED"),slot.revision());return null;});
        assertThrows(DmFailure.class,()->store.execute(r->{r.uploads().deferCleanup(past,NOW.plusSeconds(60));return null;}));
        assertTrue(store.execute(r->r.uploads().dueCleanup(NOW,50)).isEmpty());assertEquals(List.of(second),store.execute(r->r.uploads().expirable(NOW.plusSeconds(3600),50)));
    }
    @Test void forgedSettlementClassificationAndExecutorReplacementDoNotResolveFence()throws Exception {
        var doc=document("forged-settlement");var slot=initialize(doc);var attempt=fence(slot,new ObjectIdentity(key("dkp-quarantine",doc.documentId()),"version"));
        store.execute(r->{r.uploads().insertFence(attempt);r.uploads().dispatchFence(attempt,attempt.phase(FencePhase.DISPATCHED));return null;});
        try(Connection c=runtime.getConnection();Statement sql=c.createStatement()) {
            assertEquals("23514",assertThrows(SQLException.class,()->sql.execute("UPDATE dm.upload_deletion_fences SET executor_instance_id=gen_random_uuid(),phase='SETTLED_SUCCESS'")).getSQLState());
            sql.execute("INSERT INTO dm.upload_deletion_settlements SELECT tenant_id,attempt_id,'wrong-proof','SETTLED_FAILURE',created_at,true,true FROM dm.upload_deletion_fences");
            assertEquals("23514",assertThrows(SQLException.class,()->sql.execute("UPDATE dm.upload_deletion_fences SET phase='SETTLED_SUCCESS'")).getSQLState());
        }
        assertEquals(FencePhase.DISPATCHED,store.execute(r->r.uploads().unresolvedFence(doc.tenant(),doc.documentId()).orElseThrow()).phase());assertEquals(200,store.execute(r->r.uploads().lockAccounts(doc.tenant())).tenant().transientBytes());
    }
    @Test void applicationAppendUsesDurableReceiptAtomicConsumptionAndCurrentReplayAuthority()throws Exception {
        var seed=document("application-append");
        var rules=EnumSet.allOf(Action.class).stream().map(action->new AclRule(ACTOR,action,AclRule.Effect.ALLOW)).collect(java.util.stream.Collectors.toSet());
        var doc=seed.next(seed.metadata(),rules,seed.holds(),seed.versions());
        store.execute(r->{r.update(doc,0);return null;});
        var fixture=verified(doc);var caller=new DocumentCaller(new Actor(ACTOR.issuer(),ACTOR.subject()),doc.tenant(),()->AuthorityDecision.ALLOW);
        var authority=new java.util.concurrent.atomic.AtomicBoolean(true);
        var delegation=new DelegationPolicyPort(){
            public AuthorityDecision targetGrant(Actor actor,String tenant,Action action){return AuthorityDecision.ALLOW;}
            public AuthorityDecision ceiling(Actor actor,String tenant,Action action){return AuthorityDecision.ALLOW;}
        };
        var cursors=new DocumentCursorPort(){
            public PagePosition decode(String cursor,Actor actor,String tenant){throw new UnsupportedOperationException();}
            public String encode(PagePosition page,Actor actor,String tenant){throw new UnsupportedOperationException();}
        };
        VerifiedArtifactAuthorityPort artifact=(actor,tenant,id,receipt)->{
            assertEquals(caller.actor(),actor);assertEquals(doc.tenant(),tenant);assertEquals(doc.documentId(),id);assertEquals(fixture.receipt().receiptId().toString(),receipt);
            return new VerifiedArtifactAuthorityPort.VerifiedArtifact(fixture.receipt().sha256(),fixture.receipt().format().mime(),receipt,"rustfs");
        };
        java.util.function.Function<Instant,DocumentService> service=at->new DocumentService(store,(actor,tenant,action,resource)->authority.get()?AuthorityDecision.ALLOW:AuthorityDecision.DENY,delegation,artifact,cursors,java.time.Clock.fixed(at,java.time.ZoneOffset.UTC));
        var receipt=service.apply(NOW.plusSeconds(2)).appendVersion(caller,doc.documentId(),1,"real-append",fixture.receipt().receiptId().toString());
        assertEquals(2,receipt.revision());assertEquals(1,count("upload_receipt_consumptions"));assertEquals(1,count("versions"));assertEquals(1,count("command_outcomes"));assertEquals(1,count("audit_facts"));
        assertEquals(receipt,service.apply(NOW.plusSeconds(7200)).appendVersion(caller,doc.documentId(),1,"real-append",fixture.receipt().receiptId().toString()));
        assertEquals(409,assertThrows(DmFailure.class,()->service.apply(NOW.plusSeconds(2)).appendVersion(caller,doc.documentId(),2,"reuse",fixture.receipt().receiptId().toString())).status());
        authority.set(false);assertEquals(403,assertThrows(DmFailure.class,()->service.apply(NOW.plusSeconds(3)).appendVersion(caller,doc.documentId(),1,"real-append",fixture.receipt().receiptId().toString())).status());authority.set(true);
        store.execute(r->{var current=r.find(doc.tenant(),doc.documentId()).orElseThrow();r.update(current.next(current.metadata(),Set.of(),current.holds(),current.versions()),2);return null;});
        assertEquals(404,assertThrows(DmFailure.class,()->service.apply(NOW.plusSeconds(3)).appendVersion(caller,doc.documentId(),1,"real-append",fixture.receipt().receiptId().toString())).status());
        assertEquals(1,count("upload_receipt_consumptions"));assertEquals(1,count("versions"));assertEquals(1,count("command_outcomes"));
    }
    Map<String,String> snapshot()throws Exception {
        Map<String,String> result=new TreeMap<>();try(Connection c=admin.getConnection();Statement s=c.createStatement()) {
            for(String table:List.of("documents","versions","command_outcomes","audit_facts","upload_slots","upload_receipts","upload_verification_facts","upload_receipt_consumptions","upload_command_outcomes","upload_resource_accounts","upload_global_resource_account","upload_cleanup_work","upload_deletion_fences","upload_deletion_settlements","upload_audit_facts")) {
                try(ResultSet rows=s.executeQuery("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]')) FROM dm."+table+" t")){rows.next();result.put(table,rows.getString(1));}
            }
        }return result;
    }
    @Test void realDatabaseRestartPreservesUnknownFenceReceiptsReservationsAndEveryOwnedFact()throws Exception {
        var doc=document("restart");var fixture=verified(doc);var fence=fence(fixture.slot(),fixture.slot().quarantine());enqueue(fixture.slot(),fence.object());
        store.execute(r->{r.uploads().insertFence(fence);r.uploads().dispatchFence(fence,fence.phase(FencePhase.DISPATCHED));return null;});var before=snapshot();
        String container=POSTGRES.getContainerId();POSTGRES.getDockerClient().stopContainerCmd(container).withTimeout(10).exec();POSTGRES.getDockerClient().startContainerCmd(container).exec();
        var binding=POSTGRES.getDockerClient().inspectContainerCmd(container).exec().getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0];String url="jdbc:postgresql://"+POSTGRES.getHost()+":"+binding.getHostPortSpec()+"/dm";
        for(var source:List.of(admin,runtime,migrator))source.setURL(url);
        SQLException last=null;for(int attempt=0;attempt<100;attempt++){try(Connection ignored=admin.getConnection()){last=null;break;}catch(SQLException notReady){last=notReady;Thread.sleep(100);}}if(last!=null)throw last;
        assertEquals(before,snapshot());assertEquals(FencePhase.DISPATCHED,store.execute(r->r.uploads().unresolvedFence(doc.tenant(),doc.documentId()).orElseThrow()).phase());assertEquals(fixture.receipt(),store.execute(r->r.uploads().receipt(doc.tenant(),fixture.receipt().receiptId()).orElseThrow()));
        assertEquals(200,store.execute(r->r.uploads().lockAccounts(doc.tenant())).tenant().transientBytes());
        assertThrows(DmFailure.class,()->store.execute(r->{r.update(doc.next(doc.metadata(),doc.rules(),List.of(new LegalHold(UUID.randomUUID(),"cannot bypass unknown deletion",ACTOR,NOW)),doc.versions()),0);return null;}));assertEquals(before,snapshot());assertEquals(0,flyway.migrate().migrationsExecuted);
    }
}
