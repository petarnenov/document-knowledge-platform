package org.documentknowledge.documentmanagement.domain.upload;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.documentknowledge.documentmanagement.domain.Principal;
import org.documentknowledge.documentmanagement.domain.Validation;

/** Immutable persistence-bound values; no storage SDK, database or HTTP types. */
public final class UploadModels {
 private UploadModels() { }
 public static final long MAX_BYTES=100_000_000L;
 public record ObjectKey(String bucket,String key) {
  public ObjectKey {if(!("dkp-quarantine".equals(bucket)||"dkp-originals".equals(bucket)))throw new IllegalArgumentException("Invalid bucket");
   if(key==null||!key.matches("[0-9a-f]{64}/[0-9a-f-]{36}/[0-9a-f-]{36}"))throw new IllegalArgumentException("Invalid key");}
 }
 public record ObjectIdentity(ObjectKey objectKey,String versionId) {
  public ObjectIdentity {Objects.requireNonNull(objectKey);Validation.text(versionId,1024,false);if(versionId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024||"null".equals(versionId))throw new IllegalArgumentException("Mutable S3 null version");}
 }
 public record ScanEvidence(String sourceSha256,long sizeBytes,String engineVersion,String databaseVersion,
     Instant databaseUpdatedAt,String databaseDigest,Instant scannedAt) {
  public ScanEvidence {hash(sourceSha256);size(sizeBytes);Validation.text(engineVersion,64,false);Validation.text(databaseVersion,256,false);
   Objects.requireNonNull(databaseUpdatedAt);hash(databaseDigest);Objects.requireNonNull(scannedAt);}
 }
 public record InspectionEvidence(UploadFormat format,String mimeType,long sizeBytes,String sha256,Integer pageCount,
     String inspectorVersion,String profileDigest,String imageDigest,Instant inspectedAt) {
  public InspectionEvidence {Objects.requireNonNull(format);if(!format.mime().equals(mimeType))throw new IllegalArgumentException("Invalid MIME");
   size(sizeBytes);hash(sha256);if(format==UploadFormat.PDF?(pageCount==null||pageCount<1||pageCount>1000):pageCount!=null)throw new IllegalArgumentException("Invalid pages");
   if(!"1".equals(inspectorVersion))throw new IllegalArgumentException("Invalid inspector");hash(profileDigest);
   if(imageDigest==null||!imageDigest.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid image");Objects.requireNonNull(inspectedAt);}
 }
 public record VerificationEvidence(UUID evidenceId,String tenant,UUID uploadId,ObjectIdentity source,
     ScanEvidence scan,InspectionEvidence inspection,Instant verifiedAt) {
  public VerificationEvidence {Objects.requireNonNull(evidenceId);scope(tenant,uploadId);Objects.requireNonNull(source);Objects.requireNonNull(scan);Objects.requireNonNull(inspection);Objects.requireNonNull(verifiedAt);
   if(!scan.sourceSha256().equals(inspection.sha256())||scan.sizeBytes()!=inspection.sizeBytes())throw new IllegalArgumentException("Mismatched evidence");}
 }
 public record UploadReceipt(UUID receiptId,UUID uploadId,String tenant,UUID documentId,Principal actor,
     String sha256,long sizeBytes,UploadFormat format,ObjectIdentity original,UUID evidenceId,Instant createdAt,Instant expiresAt) {
  public UploadReceipt {Objects.requireNonNull(receiptId);scope(tenant,uploadId);Objects.requireNonNull(documentId);Objects.requireNonNull(actor);hash(sha256);size(sizeBytes);Objects.requireNonNull(format);Objects.requireNonNull(original);Objects.requireNonNull(evidenceId);Objects.requireNonNull(createdAt);Objects.requireNonNull(expiresAt);
   if(!"dkp-originals".equals(original.objectKey().bucket())||!createdAt.isBefore(expiresAt))throw new IllegalArgumentException("Invalid receipt");}
 }
 public record ReceiptConsumption(String tenant,UUID receiptId,UUID documentId,UUID versionId,Principal actor,
     String commandKey,String fingerprint,Instant consumedAt) {
  public ReceiptConsumption {scope(tenant,receiptId);Objects.requireNonNull(documentId);Objects.requireNonNull(versionId);Objects.requireNonNull(actor);Validation.text(commandKey,128,false);hash(fingerprint);Objects.requireNonNull(consumedAt);}
 }
 public record ResourceAccount(long admittedCount,long transientBytes,long promisedBytes,long retainedBytes,long revision) {
  public ResourceAccount {if(admittedCount<0||transientBytes<0||promisedBytes<0||retainedBytes<0||revision<0)throw new IllegalArgumentException("Negative account");}
  public static ResourceAccount empty(){return new ResourceAccount(0,0,0,0,0);}
  public ResourceAccount change(long slots,long transientDelta,long promisedDelta,long retainedDelta){return new ResourceAccount(Math.addExact(admittedCount,slots),Math.addExact(transientBytes,transientDelta),Math.addExact(promisedBytes,promisedDelta),Math.addExact(retainedBytes,retainedDelta),Math.incrementExact(revision));}
 }
 public record Accounts(ResourceAccount global,ResourceAccount tenant) {public Accounts {Objects.requireNonNull(global);Objects.requireNonNull(tenant);}}
 public record DeletionFence(UUID attemptId,String tenant,UUID documentId,UUID uploadId,ObjectIdentity object,
     UUID executorId,Instant createdAt,FencePhase phase) {
  public DeletionFence {Objects.requireNonNull(attemptId);scope(tenant,documentId);Objects.requireNonNull(uploadId);Objects.requireNonNull(object);Objects.requireNonNull(executorId);Objects.requireNonNull(createdAt);Objects.requireNonNull(phase);}
  public DeletionFence phase(FencePhase next){if(phase!=FencePhase.AUTHORIZED||next!=FencePhase.DISPATCHED)throw new IllegalStateException("Invalid dispatch");return new DeletionFence(attemptId,tenant,documentId,uploadId,object,executorId,createdAt,next);}
 }
 public enum FencePhase {AUTHORIZED,DISPATCHED,SETTLED_SUCCESS,SETTLED_FAILURE}
 public record CleanupWork(UUID workId,String tenant,UUID uploadId,UUID documentId,ObjectKey key,ObjectIdentity object,
     CleanupReason reason,Instant createdAt,boolean complete) {
  public CleanupWork {Objects.requireNonNull(workId);scope(tenant,uploadId);Objects.requireNonNull(documentId);Objects.requireNonNull(key);Objects.requireNonNull(reason);Objects.requireNonNull(createdAt);if(object!=null&&!object.objectKey().equals(key))throw new IllegalArgumentException("Wrong cleanup object");}
  public CleanupWork completed(){return new CleanupWork(workId,tenant,uploadId,documentId,key,object,reason,createdAt,true);}
 }
 public enum CleanupReason {REJECTED,EXPIRED,TRANSFER_ORPHAN,FAILED_VERIFIED_COPY,VERIFIED_QUARANTINE_COPY}
 public static String hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid hash");return value;}
 public static long size(long value){if(value<1||value>MAX_BYTES)throw new IllegalArgumentException("Invalid size");return value;}
 private static void scope(String tenant,UUID id){Validation.text(tenant,256,false);Objects.requireNonNull(id);}
}
