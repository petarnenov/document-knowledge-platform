package org.documentknowledge.documentmanagement.adapters.outbound.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.upload.UploadRepository;
import org.documentknowledge.documentmanagement.domain.Principal;
import org.documentknowledge.documentmanagement.domain.upload.*;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;

/** Transaction-bound upload adapter. It never opens another connection or performs network I/O. */
final class JdbcUploadRepository implements UploadRepository {
    private final Connection connection;
    JdbcUploadRepository(Connection connection) { this.connection=Objects.requireNonNull(connection); }
    private static DmFailure unavailable() { return new DmFailure(503,"UNAVAILABLE"); }
    private PreparedStatement prepare(String sql,Object... values) throws SQLException {
        PreparedStatement statement=connection.prepareStatement(sql);
        try {
            for(int i=0;i<values.length;i++) {
                Object value=values[i];
                if(value instanceof Instant instant)statement.setTimestamp(i+1,Timestamp.from(instant));
                else statement.setObject(i+1,value);
            }
            return statement;
        } catch(SQLException failure) {statement.close();throw failure;}
    }
    private int change(String sql,Object... values) {
        try(PreparedStatement statement=prepare(sql,values)){return statement.executeUpdate();}
        catch(SQLException failure){throw unavailable();}
    }
    private interface Reader<T> {T read(ResultSet rows)throws SQLException;}
    private <T> Optional<T> one(String sql,Reader<T> read,Object... values) {
        try(PreparedStatement statement=prepare(sql,values);ResultSet rows=statement.executeQuery()) {
            return rows.next()?Optional.of(read.read(rows)):Optional.empty();
        }catch(SQLException failure){throw unavailable();}
    }
    private static UUID uuid(ResultSet rows,String name)throws SQLException{return rows.getObject(name,UUID.class);}
    private static Instant time(ResultSet rows,String name)throws SQLException{Timestamp timestamp=rows.getTimestamp(name);return timestamp==null?null:timestamp.toInstant();}
    private static ResourceAccount account(ResultSet rows)throws SQLException{return new ResourceAccount(rows.getLong("admitted_count"),rows.getLong("reserved_transient_bytes"),rows.getLong("promised_original_bytes"),rows.getLong("retained_original_bytes"),rows.getLong("revision"));}
    @Override public Accounts lockAccounts(String tenant) {
        ResourceAccount global=one("SELECT * FROM dm.upload_global_resource_account WHERE account_key=1 FOR UPDATE",JdbcUploadRepository::account).orElseThrow(JdbcUploadRepository::unavailable);
        change("INSERT INTO dm.upload_resource_accounts VALUES (?,0,0,0,0,0) ON CONFLICT DO NOTHING",tenant);
        ResourceAccount local=one("SELECT * FROM dm.upload_resource_accounts WHERE tenant_id=? FOR UPDATE",JdbcUploadRepository::account,tenant).orElseThrow(JdbcUploadRepository::unavailable);
        return new Accounts(global,local);
    }
    @Override public void saveAccounts(String tenant,Accounts before,Accounts after) {
        saveAccount("dm.upload_global_resource_account","account_key",1,before.global(),after.global());
        saveAccount("dm.upload_resource_accounts","tenant_id",tenant,before.tenant(),after.tenant());
    }
    private void saveAccount(String table,String key,Object id,ResourceAccount before,ResourceAccount after) {
        if(after.revision()!=before.revision()+1)throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
        if(change("UPDATE "+table+" SET admitted_count=?,reserved_transient_bytes=?,promised_original_bytes=?,retained_original_bytes=?,revision=? WHERE "+key+"=? AND revision=?",
                after.admittedCount(),after.transientBytes(),after.promisedBytes(),after.retainedBytes(),after.revision(),id,before.revision())!=1)throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
    }
    @Override public Optional<UploadSlot> slot(String tenant,UUID id){return one("SELECT * FROM dm.upload_slots WHERE tenant_id=? AND upload_id=? FOR UPDATE",this::readSlot,tenant,id);}
    private static String bucket(ObjectIdentity object){return object==null?null:object.objectKey().bucket();}
    private static String objectKey(ObjectIdentity object){return object==null?null:object.objectKey().key();}
    private static String version(ObjectIdentity object){return object==null?null:object.versionId();}
    private static ObjectIdentity identity(ResultSet rows,String bucket,String key,String version)throws SQLException {
        String v=rows.getString(version);return v==null?null:new ObjectIdentity(new ObjectKey(rows.getString(bucket),rows.getString(key)),v);
    }
    private UploadSlot readSlot(ResultSet rows)throws SQLException {
        String possible=rows.getString("possible_original_key");String originalVersion=rows.getString("possible_original_version_id");
        ObjectKey key=possible==null?null:new ObjectKey("dkp-originals",possible);
        return new UploadSlot(uuid(rows,"upload_id"),rows.getString("tenant_id"),uuid(rows,"document_id"),new Principal(rows.getString("actor_issuer"),rows.getString("actor_subject")),time(rows,"created_at"),time(rows,"expires_at"),rows.getLong("expected_document_revision"),UploadFormat.valueOf(rows.getString("declared_format")),rows.getLong("declared_size"),UploadState.valueOf(rows.getString("state")),rows.getLong("state_revision"),identity(rows,"quarantine_bucket","quarantine_key","quarantine_version_id"),rows.getString("body_sha256"),rows.getString("body_idempotency_key"),rows.getInt("transfer_attempt_count"),rows.getInt("attempt_count"),uuid(rows,"lease_id"),time(rows,"lease_until"),key,originalVersion==null?null:new ObjectIdentity(key,originalVersion),rows.getBoolean("copy_write_settled"),rows.getBoolean("copy_cleanup_confirmed"),rows.getString("failure_code"),rows.getBoolean("quota_held"),rows.getBoolean("quarantine_cleanup_confirmed"),rows.getLong("transient_reservation"));
    }
    @Override public void insertSlot(UploadSlot slot) {
        if (!slot.equals(UploadSlot.initialize(slot.uploadId(),slot.tenant(),slot.documentId(),slot.actor(),slot.createdAt(),slot.expectedRevision(),slot.format(),slot.declaredSize())))
            throw new IllegalArgumentException("Only initialization inserts a slot");
        change("INSERT INTO dm.upload_slots (tenant_id,upload_id,document_id,actor_issuer,actor_subject,created_at,expires_at,expected_document_revision,declared_format,declared_size,state,state_revision,attempt_count,transfer_attempt_count,copy_write_settled,copy_cleanup_confirmed,cleanup_pending,quota_held,transient_reservation) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,false,?,?)",
            slot.tenant(),slot.uploadId(),slot.documentId(),slot.actor().issuer(),slot.actor().subject(),slot.createdAt(),slot.expiresAt(),slot.expectedRevision(),slot.format().name(),slot.declaredSize(),slot.state().name(),slot.revision(),slot.verificationAttempts(),slot.transferAttempts(),slot.writerSettled(),slot.copyCleanupConfirmed(),slot.quotaHeld(),slot.transientReservation());
    }
    @Override public void updateSlot(UploadSlot next,long expected) {
        if(next.revision()!=expected+1)throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
        if(change("UPDATE dm.upload_slots SET state=?,state_revision=?,body_idempotency_key=?,body_sha256=?,actual_size=?,quarantine_bucket=?,quarantine_key=?,quarantine_version_id=?,attempt_count=?,transfer_attempt_count=?,lease_id=?,lease_until=?,possible_original_key=?,possible_original_version_id=?,copy_write_settled=?,copy_cleanup_confirmed=?,failure_code=?,quota_held=?,quarantine_cleanup_confirmed=?,transient_reservation=? WHERE tenant_id=? AND upload_id=? AND state_revision=?",
            next.state().name(),next.revision(),next.transferKey(),next.bodySha256(),next.quarantine()==null?null:next.declaredSize(),bucket(next.quarantine()),objectKey(next.quarantine()),version(next.quarantine()),next.verificationAttempts(),next.transferAttempts(),next.leaseId(),next.leaseUntil(),next.possibleOriginal()==null?null:next.possibleOriginal().key(),version(next.original()),next.writerSettled(),next.copyCleanupConfirmed(),next.failureCode(),next.quotaHeld(),next.quarantineCleanupConfirmed(),next.transientReservation(),next.tenant(),next.uploadId(),expected)!=1)throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
    }
    private static final String COMMAND_WHERE="tenant_id=? AND actor_issuer=? AND actor_subject=? AND operation=? AND resource_scope=? AND idempotency_key=?";
    private static Object[] scope(CommandKey key){return new Object[]{key.tenant(),key.actor().issuer(),key.actor().subject(),key.operation(),key.resource(),key.idempotencyKey()};}
    @Override public Optional<UploadOutcome> command(CommandKey key) {
        return one("SELECT * FROM dm.upload_command_outcomes WHERE "+COMMAND_WHERE,rows->new UploadOutcome(key,rows.getString("canonical_body_hash"),rows.getInt("status"),uuid(rows,"upload_id"),uuid(rows,"receipt_id")),scope(key));
    }
    @Override public void record(UploadOutcome outcome,UploadAudit audit) {
        CommandKey key=outcome.key();
        change("INSERT INTO dm.upload_command_outcomes VALUES (?,?,?,?,?,?,?,?,?,?,?)",key.tenant(),key.actor().issuer(),key.actor().subject(),key.operation(),key.resource(),key.idempotencyKey(),outcome.fingerprint(),outcome.status(),outcome.uploadId(),outcome.receiptId(),audit.at());
        audit(audit);
    }
    @Override public void audit(UploadAudit audit){change("INSERT INTO dm.upload_audit_facts VALUES (?,?,?,?,?,?,?,?,?,?)",audit.eventId(),audit.tenant(),audit.documentId(),audit.uploadId(),audit.actor().issuer(),audit.actor().subject(),audit.operation(),audit.stateRevision(),audit.at(),audit.code());}
    @Override public void insertEvidence(VerificationEvidence evidence) {
        ScanEvidence scan=evidence.scan();InspectionEvidence inspection=evidence.inspection();
        if(change("INSERT INTO dm.upload_verification_facts (tenant_id,evidence_id,document_id,upload_id,source_sha256,source_size,source_bucket,source_key,source_version_id,detected_format,detected_mime,inspector_plugin,inspector_version,inspector_image_digest,profile_digest,inspected_at,page_count,scanner_engine_version,standard_database_version,standard_database_updated_at,standard_database_digest,scanned_at,verified_at,result) SELECT ?,?,document_id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? FROM dm.upload_slots WHERE tenant_id=? AND upload_id=?",
                evidence.tenant(),evidence.evidenceId(),evidence.uploadId(),scan.sourceSha256(),scan.sizeBytes(),bucket(evidence.source()),objectKey(evidence.source()),version(evidence.source()),inspection.format().name(),inspection.mimeType(),"dkp-structure-inspector",inspection.inspectorVersion(),inspection.imageDigest(),inspection.profileDigest(),inspection.inspectedAt(),inspection.pageCount(),scan.engineVersion(),scan.databaseVersion(),scan.databaseUpdatedAt(),scan.databaseDigest(),scan.scannedAt(),evidence.verifiedAt(),"VERIFIED",evidence.tenant(),evidence.uploadId())!=1)throw unavailable();
    }
    @Override public Optional<VerificationEvidence> evidence(String tenant,UUID id) {
        return one("SELECT * FROM dm.upload_verification_facts WHERE tenant_id=? AND evidence_id=?",rows->{
            ScanEvidence scan=new ScanEvidence(rows.getString("source_sha256"),rows.getLong("source_size"),rows.getString("scanner_engine_version"),rows.getString("standard_database_version"),time(rows,"standard_database_updated_at"),rows.getString("standard_database_digest"),time(rows,"scanned_at"));
            InspectionEvidence inspection=new InspectionEvidence(UploadFormat.valueOf(rows.getString("detected_format")),rows.getString("detected_mime"),rows.getLong("source_size"),rows.getString("source_sha256"),rows.getObject("page_count",Integer.class),rows.getString("inspector_version"),rows.getString("profile_digest"),rows.getString("inspector_image_digest"),time(rows,"inspected_at"));
            return new VerificationEvidence(uuid(rows,"evidence_id"),rows.getString("tenant_id"),uuid(rows,"upload_id"),identity(rows,"source_bucket","source_key","source_version_id"),scan,inspection,time(rows,"verified_at"));
        },tenant,id);
    }
    @Override public void insertReceipt(UploadReceipt receipt) {
        change("INSERT INTO dm.upload_receipts VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",receipt.tenant(),receipt.receiptId(),receipt.uploadId(),receipt.documentId(),receipt.actor().issuer(),receipt.actor().subject(),receipt.sizeBytes(),receipt.sha256(),receipt.format().name(),receipt.format().mime(),bucket(receipt.original()),objectKey(receipt.original()),version(receipt.original()),receipt.evidenceId(),receipt.createdAt(),receipt.expiresAt());
    }
    private UploadReceipt readReceipt(ResultSet rows)throws SQLException{return new UploadReceipt(uuid(rows,"receipt_id"),uuid(rows,"upload_id"),rows.getString("tenant_id"),uuid(rows,"document_id"),new Principal(rows.getString("actor_issuer"),rows.getString("actor_subject")),rows.getString("sha256"),rows.getLong("size_bytes"),UploadFormat.valueOf(rows.getString("detected_format")),identity(rows,"original_bucket","original_key","original_version_id"),uuid(rows,"verification_evidence_id"),time(rows,"created_at"),time(rows,"expires_at"));}
    // Receipts are immutable. Document/slot serialization plus unique consumption protects reuse;
    // SELECT FOR UPDATE would require granting forbidden UPDATE privilege on these facts.
    @Override public Optional<UploadReceipt> receipt(String tenant,UUID id){return one("SELECT * FROM dm.upload_receipts WHERE tenant_id=? AND receipt_id=?",this::readReceipt,tenant,id);}
    @Override public Optional<UploadReceipt> receiptForUpload(String tenant,UUID id){return one("SELECT * FROM dm.upload_receipts WHERE tenant_id=? AND upload_id=?",this::readReceipt,tenant,id);}
    @Override public Optional<ReceiptConsumption> consumption(String tenant,UUID id) {
        return one("SELECT * FROM dm.upload_receipt_consumptions WHERE tenant_id=? AND receipt_id=?",rows->new ReceiptConsumption(rows.getString("tenant_id"),uuid(rows,"receipt_id"),uuid(rows,"document_id"),uuid(rows,"version_id"),new Principal(rows.getString("actor_issuer"),rows.getString("actor_subject")),rows.getString("append_idempotency_key"),rows.getString("append_fingerprint"),time(rows,"consumed_at")),tenant,id);
    }
    @Override public void consume(ReceiptConsumption consumed) {
        change("INSERT INTO dm.upload_receipt_consumptions VALUES (?,?,?,?,?,?,?,?,?,?,?)",consumed.tenant(),consumed.receiptId(),consumed.documentId(),consumed.versionId(),consumed.actor().issuer(),consumed.actor().subject(),"document.version.append",consumed.documentId().toString(),consumed.commandKey(),consumed.fingerprint(),consumed.consumedAt());
    }
    private DeletionFence readFence(ResultSet rows)throws SQLException{return new DeletionFence(uuid(rows,"attempt_id"),rows.getString("tenant_id"),uuid(rows,"document_id"),uuid(rows,"upload_id"),identity(rows,"bucket","object_key","version_id"),uuid(rows,"executor_instance_id"),time(rows,"created_at"),FencePhase.valueOf(rows.getString("phase")));}
    @Override public Optional<DeletionFence> unresolvedFence(String tenant,UUID document) {return one("SELECT * FROM dm.upload_deletion_fences WHERE tenant_id=? AND document_id=? AND phase IN ('AUTHORIZED','DISPATCHED') FOR UPDATE",this::readFence,tenant,document);}
    @Override public void insertFence(DeletionFence fence) {
        change("INSERT INTO dm.upload_deletion_fences VALUES (?,?,?,?,?,?,?,?,?,?)",fence.tenant(),fence.documentId(),fence.attemptId(),fence.uploadId(),bucket(fence.object()),objectKey(fence.object()),version(fence.object()),fence.executorId(),fence.createdAt(),fence.phase().name());
    }
    @Override public void dispatchFence(DeletionFence expected,DeletionFence dispatched) {
        if(expected.phase()!=FencePhase.AUTHORIZED || !expected.phase(FencePhase.DISPATCHED).equals(dispatched))throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
        phase(expected,dispatched.phase());
    }
    private void phase(DeletionFence expected,FencePhase next) {
        if(change("UPDATE dm.upload_deletion_fences SET phase=? WHERE tenant_id=? AND attempt_id=? AND executor_instance_id=? AND phase=?",next.name(),expected.tenant(),expected.attemptId(),expected.executorId(),expected.phase().name())!=1)throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
    }
    @Override public void settleFence(DeletionFence expected,DeletionFence terminal,String requestId,Instant at) {
        if(expected.phase()!=FencePhase.DISPATCHED || !(terminal.phase()==FencePhase.SETTLED_SUCCESS || terminal.phase()==FencePhase.SETTLED_FAILURE)
            || !new DeletionFence(expected.attemptId(),expected.tenant(),expected.documentId(),expected.uploadId(),expected.object(),expected.executorId(),expected.createdAt(),terminal.phase()).equals(terminal)
            || requestId==null || requestId.isBlank() || requestId.length()>1024 || at==null)throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
        change("INSERT INTO dm.upload_deletion_settlements VALUES (?,?,?,?,?,true,?)",expected.tenant(),expected.attemptId(),requestId,terminal.phase().name(),at,terminal.phase()==FencePhase.SETTLED_SUCCESS);
        phase(expected,terminal.phase());
    }
    @Override public void enqueueCleanup(CleanupWork work) {
        change("INSERT INTO dm.upload_cleanup_work (tenant_id,work_id,upload_id,document_id,bucket,object_key,version_id,reason,created_at,attempt_count,next_attempt_at,completed_at) VALUES (?,?,?,?,?,?,?,?,?,0,?,?) ON CONFLICT (tenant_id,upload_id,bucket,object_key,version_id) DO NOTHING",work.tenant(),work.workId(),work.uploadId(),work.documentId(),work.key().bucket(),work.key().key(),version(work.object()),work.reason().name(),work.createdAt(),work.createdAt(),work.complete()?work.createdAt():null);
    }
    private CleanupWork readCleanup(ResultSet rows)throws SQLException {
        ObjectKey key=new ObjectKey(rows.getString("bucket"),rows.getString("object_key"));String version=rows.getString("version_id");
        return new CleanupWork(uuid(rows,"work_id"),rows.getString("tenant_id"),uuid(rows,"upload_id"),uuid(rows,"document_id"),key,version==null?null:new ObjectIdentity(key,version),CleanupReason.valueOf(rows.getString("reason")),time(rows,"created_at"),time(rows,"completed_at")!=null);
    }
    @Override public Optional<CleanupWork> cleanup(String tenant,UUID id){return one("SELECT * FROM dm.upload_cleanup_work WHERE tenant_id=? AND work_id=? FOR UPDATE",this::readCleanup,tenant,id);}
    @Override public List<CleanupWork> pendingCleanup(String tenant,UUID upload) {
        try(PreparedStatement statement=prepare("SELECT * FROM dm.upload_cleanup_work WHERE tenant_id=? AND upload_id=? AND completed_at IS NULL ORDER BY created_at,work_id FOR UPDATE",tenant,upload);ResultSet rows=statement.executeQuery()) {
            List<CleanupWork> result=new ArrayList<>();while(rows.next())result.add(readCleanup(rows));return List.copyOf(result);
        }catch(SQLException failure){throw unavailable();}
    }
    @Override public List<UploadSlot> expirable(Instant at,int limit) {
        requireLimit(limit);Objects.requireNonNull(at);
        return many("SELECT * FROM dm.upload_slots WHERE expires_at<=? AND state NOT IN ('CONSUMED','EXPIRED','REJECTED') ORDER BY expires_at,tenant_id,upload_id LIMIT ?",this::readSlot,at,limit);
    }
    @Override public List<CleanupWork> dueCleanup(Instant at,int limit) {
        requireLimit(limit);Objects.requireNonNull(at);
        return many("SELECT * FROM dm.upload_cleanup_work WHERE completed_at IS NULL AND next_attempt_at<=? ORDER BY next_attempt_at,tenant_id,work_id LIMIT ?",this::readCleanup,at,limit);
    }
    private static void requireLimit(int limit) {if(limit<1||limit>50)throw new IllegalArgumentException("Invalid internal batch size");}
    private <T> List<T> many(String sql,Reader<T> read,Object... values) {
        try(PreparedStatement statement=prepare(sql,values);ResultSet rows=statement.executeQuery()) {
            List<T> result=new ArrayList<>();while(rows.next())result.add(read.read(rows));return List.copyOf(result);
        }catch(SQLException failure){throw unavailable();}
    }
    @Override public void deferCleanup(CleanupWork expected,Instant nextAttempt) {
        Objects.requireNonNull(nextAttempt);
        if(change("UPDATE dm.upload_cleanup_work SET attempt_count=attempt_count+1,next_attempt_at=? WHERE tenant_id=? AND work_id=? AND completed_at IS NULL",nextAttempt,expected.tenant(),expected.workId())!=1)throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
    }
    @Override public void completeCleanup(CleanupWork expected) {
        if(change("UPDATE dm.upload_cleanup_work SET completed_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND work_id=? AND completed_at IS NULL",expected.tenant(),expected.workId())!=1)throw new DmFailure(409,"UPLOAD_STATE_CONFLICT");
    }
}
