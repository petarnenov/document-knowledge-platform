package org.documentknowledge.documentmanagement.domain.upload;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.documentknowledge.documentmanagement.domain.Principal;
import org.documentknowledge.documentmanagement.domain.Validation;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;

public record UploadSlot(UUID uploadId,String tenant,UUID documentId,Principal actor,Instant createdAt,Instant expiresAt,
 long expectedRevision,UploadFormat format,long declaredSize,UploadState state,long revision,
 ObjectIdentity quarantine,String bodySha256,String transferKey,int transferAttempts,int verificationAttempts,
 UUID leaseId,Instant leaseUntil,ObjectKey possibleOriginal,ObjectIdentity original,boolean writerSettled,
 boolean copyCleanupConfirmed,String failureCode,boolean quotaHeld,boolean quarantineCleanupConfirmed,long transientReservation) {
 public UploadSlot {
  Objects.requireNonNull(uploadId);Validation.text(tenant,256,false);Objects.requireNonNull(documentId);Objects.requireNonNull(actor);
  Objects.requireNonNull(createdAt);Objects.requireNonNull(expiresAt);Objects.requireNonNull(format);Objects.requireNonNull(state);size(declaredSize);
  if(!createdAt.isBefore(expiresAt)||expectedRevision<0||expectedRevision>9_007_199_254_740_991L||revision<0||revision>9_007_199_254_740_991L||transferAttempts<0||transferAttempts>3||verificationAttempts<0||verificationAttempts>3)throw new IllegalArgumentException("Invalid slot");
  if(transientReservation<0||transientReservation>2*declaredSize)throw new IllegalArgumentException("Invalid reservation");
  if(bodySha256!=null)hash(bodySha256);
  if((leaseId==null)!=(leaseUntil==null)||leaseUntil!=null&&leaseUntil.isAfter(expiresAt))throw new IllegalArgumentException("Invalid lease");
  if(original!=null&&(possibleOriginal==null||!original.objectKey().equals(possibleOriginal)))throw new IllegalArgumentException("Invalid original");
 }
 public static UploadSlot initialize(UUID id,String tenant,UUID document,Principal actor,Instant now,long expected,UploadFormat format,long size){
  return new UploadSlot(id,tenant,document,actor,now,now.plusSeconds(3600),expected,format,size,UploadState.INITIALIZED,0,null,null,null,0,0,null,null,null,null,true,true,null,true,false,2*size);
 }
 public boolean expired(Instant now){return !now.isBefore(expiresAt);}
 public UploadSlot receiving(UUID lease,Instant until,String key){
  require(state==UploadState.INITIALIZED&&transferAttempts<3);
  return changed(UploadState.RECEIVING,quarantine,bodySha256,key,transferAttempts+1,verificationAttempts,lease,until,null,null,false,true,null,quotaHeld);
 }
 public UploadSlot transferReset(){require(state==UploadState.RECEIVING);return changed(UploadState.INITIALIZED,null,null,transferKey,transferAttempts,verificationAttempts,null,null,null,null,true,true,"TRANSFER_INCOMPLETE",quotaHeld);}
 public UploadSlot transferPending(boolean settled){require(state==UploadState.RECEIVING);return changed(state,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,leaseId,leaseUntil,possibleOriginal,original,settled,copyCleanupConfirmed,"TRANSFER_INCOMPLETE",quotaHeld);}
 public UploadSlot quarantined(ObjectIdentity object,String sha){require(state==UploadState.RECEIVING);return changed(UploadState.QUARANTINED,object,sha,transferKey,transferAttempts,verificationAttempts,null,null,null,null,true,true,null,quotaHeld);}
 public UploadSlot verifying(UUID lease,Instant until){require(state==UploadState.QUARANTINED&&verificationAttempts<3&&(possibleOriginal==null||writerSettled&&copyCleanupConfirmed));
  return changed(UploadState.VERIFYING,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts+1,lease,until,null,null,true,true,null,quotaHeld);}
 public UploadSlot possibleCopy(ObjectKey key){require(state==UploadState.VERIFYING&&possibleOriginal==null);return changed(state,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,leaseId,leaseUntil,key,null,false,false,null,quotaHeld);}
 public UploadSlot copied(ObjectIdentity object){require(state==UploadState.VERIFYING&&possibleOriginal!=null&&possibleOriginal.equals(object.objectKey()));return changed(state,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,leaseId,leaseUntil,possibleOriginal,object,true,false,null,quotaHeld);}
 public UploadSlot verified(){require(state==UploadState.VERIFYING&&original!=null&&writerSettled);return changed(UploadState.VERIFIED,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,null,null,possibleOriginal,original,true,false,null,quotaHeld);}
 public UploadSlot consumed(){require(state==UploadState.VERIFIED);return changed(UploadState.CONSUMED,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,null,null,possibleOriginal,original,true,false,null,false).reservation(quarantineCleanupConfirmed?0:declaredSize);}
 public UploadSlot transientFailure(){require(state==UploadState.VERIFYING);boolean safe=possibleOriginal==null||writerSettled&&copyCleanupConfirmed;
  return changed(safe?UploadState.QUARANTINED:state,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,safe?null:leaseId,safe?null:leaseUntil,possibleOriginal,original,writerSettled,copyCleanupConfirmed,"DEPENDENCY_UNAVAILABLE",quotaHeld);}
 public UploadSlot terminal(UploadState next,String code){require(next==UploadState.EXPIRED||next==UploadState.REJECTED);require(state!=UploadState.CONSUMED);
  return changed(next,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,null,null,possibleOriginal,original,writerSettled,copyCleanupConfirmed,code,quotaHeld);}
 public UploadSlot cleaned(boolean originalCopy){var next=changed(state,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,leaseId,leaseUntil,possibleOriginal,original,writerSettled,originalCopy||copyCleanupConfirmed,failureCode,quotaHeld);return originalCopy?next:next.flags(true,transientReservation);}
 public UploadSlot reservation(long bytes){return flags(quarantineCleanupConfirmed,bytes);}
 private UploadSlot flags(boolean sourceCleaned,long bytes){return new UploadSlot(uploadId,tenant,documentId,actor,createdAt,expiresAt,expectedRevision,format,declaredSize,state,revision,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,leaseId,leaseUntil,possibleOriginal,original,writerSettled,copyCleanupConfirmed,failureCode,quotaHeld,sourceCleaned,bytes);}
 public UploadSlot settledWriter(){return new UploadSlot(uploadId,tenant,documentId,actor,createdAt,expiresAt,expectedRevision,format,declaredSize,state,revision,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,leaseId,leaseUntil,possibleOriginal,original,true,copyCleanupConfirmed,failureCode,quotaHeld,quarantineCleanupConfirmed,transientReservation);}
 public UploadSlot accounting(long bytes,boolean charged){return new UploadSlot(uploadId,tenant,documentId,actor,createdAt,expiresAt,expectedRevision,format,declaredSize,state,revision,quarantine,bodySha256,transferKey,transferAttempts,verificationAttempts,leaseId,leaseUntil,possibleOriginal,original,writerSettled,copyCleanupConfirmed,failureCode,charged,quarantineCleanupConfirmed,bytes);}
 public UploadSlot cleanupSucceeded(boolean originalCopy,long remaining,boolean release){boolean retry=state==UploadState.VERIFYING&&originalCopy&&writerSettled;boolean retryTransfer=state==UploadState.RECEIVING&&!originalCopy&&writerSettled;return new UploadSlot(uploadId,tenant,documentId,actor,createdAt,expiresAt,expectedRevision,format,declaredSize,retry?UploadState.QUARANTINED:retryTransfer?UploadState.INITIALIZED:state,revision+1,retryTransfer?null:quarantine,retryTransfer?null:bodySha256,transferKey,transferAttempts,verificationAttempts,retry||retryTransfer?null:leaseId,retry||retryTransfer?null:leaseUntil,possibleOriginal,original,writerSettled,originalCopy||copyCleanupConfirmed,failureCode,release?false:quotaHeld,retryTransfer?false:originalCopy?quarantineCleanupConfirmed:true,remaining);}
 private UploadSlot changed(UploadState s,ObjectIdentity q,String sha,String key,int t,int v,UUID lease,Instant until,ObjectKey possible,ObjectIdentity o,boolean settled,boolean cleaned,String failure,boolean charged){return new UploadSlot(uploadId,tenant,documentId,actor,createdAt,expiresAt,expectedRevision,format,declaredSize,s,revision+1,q,sha,key,t,v,lease,until,possible,o,settled,cleaned,failure,charged,quarantineCleanupConfirmed,transientReservation);}
 private static void require(boolean valid){if(!valid)throw new IllegalStateException("Invalid upload transition");}
}
