package org.documentknowledge.documentmanagement.application.upload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.documentknowledge.documentmanagement.application.CommandKey;
import org.documentknowledge.documentmanagement.domain.Principal;
import org.documentknowledge.documentmanagement.domain.upload.UploadSlot;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
/** SAME connection/UoW as DocumentRepository. All lookups include tenant. All updates CAS and audit atomically. */
public interface UploadRepository {
 /** Locks global then tenant; callers subsequently lock document, slot, receipt/fence, outcome in that order. */
 Accounts lockAccounts(String tenant);
 void saveAccounts(String tenant,Accounts before,Accounts after);
 Optional<UploadSlot> slot(String tenant,UUID uploadId);
 void insertSlot(UploadSlot slot);
 void updateSlot(UploadSlot next,long expectedRevision);
 Optional<UploadOutcome> command(CommandKey key);
 void record(UploadOutcome outcome,UploadAudit audit);
 void audit(UploadAudit audit);
 void insertEvidence(VerificationEvidence evidence);
 Optional<VerificationEvidence> evidence(String tenant,UUID evidenceId);
 void insertReceipt(UploadReceipt receipt);
 Optional<UploadReceipt> receipt(String tenant,UUID receiptId);
 Optional<UploadReceipt> receiptForUpload(String tenant,UUID uploadId);
 Optional<ReceiptConsumption> consumption(String tenant,UUID receiptId);
 void consume(ReceiptConsumption consumption);
 Optional<DeletionFence> unresolvedFence(String tenant,UUID documentId);
 void insertFence(DeletionFence fence);
 void dispatchFence(DeletionFence expected,DeletionFence dispatched);
 void settleFence(DeletionFence expected,DeletionFence terminal,String requestId,Instant at);
 void enqueueCleanup(CleanupWork work);
 Optional<CleanupWork> cleanup(String tenant,UUID workId);
 List<UploadSlot> expirable(Instant at,int limit);
 List<CleanupWork> dueCleanup(Instant at,int limit);
 void deferCleanup(CleanupWork expected,Instant nextAttempt);
 List<CleanupWork> pendingCleanup(String tenant,UUID uploadId);
 void completeCleanup(CleanupWork expected);
 record UploadOutcome(CommandKey key,String fingerprint,int status,UUID uploadId,UUID receiptId) { }
 record UploadAudit(UUID eventId,String tenant,UUID documentId,UUID uploadId,Principal actor,String operation,long stateRevision,Instant at,String code) { }
}
