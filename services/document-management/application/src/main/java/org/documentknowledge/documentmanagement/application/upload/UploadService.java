package org.documentknowledge.documentmanagement.application.upload;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.domain.upload.*;
import org.documentknowledge.identity.ResourceAuthorizationPort;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.documentknowledge.documentmanagement.application.upload.UploadRepository.*;

/** Durable transitions surround external work; a network operation never runs under a SQL transaction. */
public final class UploadService {
 private final DocumentUnitOfWork transactions;private final UploadPolicy policy;private final UploadStoragePort storage;
 private final MalwareScanPort scanner;private final StructureInspectionPort inspector;private final Clock clock;private final Path temporary;
 private final Semaphore verification=new Semaphore(1),transfers=new Semaphore(10);
 private final Set<UUID> transferring=java.util.concurrent.ConcurrentHashMap.newKeySet();
 public UploadService(DocumentUnitOfWork transactions,ResourceAuthorizationPort grants,UploadStoragePort storage,
     MalwareScanPort scanner,StructureInspectionPort inspector,Clock clock,Path temporary){
  this.transactions=Objects.requireNonNull(transactions);policy=new UploadPolicy(grants);this.storage=Objects.requireNonNull(storage);
  this.scanner=Objects.requireNonNull(scanner);this.inspector=Objects.requireNonNull(inspector);this.clock=Objects.requireNonNull(clock);this.temporary=Objects.requireNonNull(temporary);
 }
 public record SlotReceipt(UUID uploadId) { }
 public record CompleteReceipt(UUID uploadId,UUID receiptId) { }
 public record Status(UUID uploadId,UploadState state,Instant expiresAt,String failureCode) { }
 public record ConsumedVersion(UUID documentId,UUID versionId) { }
 public SlotReceipt initialize(DocumentCaller caller,String key,UUID documentId,long expectedRevision,UploadFormat format,long size){
  policy.route(caller,Action.VERSION_APPEND,documentId,false);
  if(format==null||size<1||size>MAX_BYTES||expectedRevision<0||expectedRevision>9_007_199_254_740_991L)throw new DmFailure(size>MAX_BYTES?413:400,size>MAX_BYTES?"PAYLOAD_TOO_LARGE":"INVALID_REQUEST");
  var command=UploadSupport.command(caller,"upload.initialize",documentId.toString(),key);
  String fingerprint=UploadSupport.fingerprint(documentId,expectedRevision,format,size);
  return transactions.execute(repository->{repository.lockCommand(command);var u=repository.uploads();var accounts=u.lockAccounts(caller.tenant());
   var document=policy.document(repository,caller,documentId,Action.VERSION_APPEND,false);var previous=u.command(command);
   if(previous.isPresent()){same(previous.get(),fingerprint);policy.document(repository,caller,documentId,Action.VERSION_APPEND,false);return new SlotReceipt(previous.get().uploadId());}
   if(expectedRevision!=document.revision())throw new DmFailure(409,"REVISION_CONFLICT");
   Accounts next=reserve(accounts,size);var slot=UploadSlot.initialize(UUID.randomUUID(),caller.tenant(),documentId,caller.principal(),now(),expectedRevision,format,size);
   policy.document(repository,caller,documentId,Action.VERSION_APPEND,false);u.saveAccounts(caller.tenant(),accounts,next);u.insertSlot(slot);
   u.record(new UploadOutcome(command,fingerprint,201,slot.uploadId(),null),audit(slot,"upload.initialize",null));return new SlotReceipt(slot.uploadId());
  });
 }
 public SlotReceipt transfer(DocumentCaller caller,UUID uploadId,String key,InputStream input){
  UUID document=locate(caller,uploadId,false);read(caller,document,uploadId,false);
  if(!transfers.tryAcquire())throw new DmFailure(429,"QUOTA_EXCEEDED");
  if(!transferring.add(uploadId)){transfers.release();throw new DmFailure(429,"QUOTA_EXCEEDED");}
  try{return transferBounded(caller,uploadId,key,input);}finally{transferring.remove(uploadId);transfers.release();}
 }
 private SlotReceipt transferBounded(DocumentCaller caller,UUID uploadId,String key,InputStream input){
  Objects.requireNonNull(input);UUID document=locate(caller,uploadId,false);var command=UploadSupport.command(caller,"upload.transfer",uploadId.toString(),key);
  TransferPlan plan=transactions.execute(repository->{repository.lockCommand(command);var u=repository.uploads();u.lockAccounts(caller.tenant());policy.document(repository,caller,document,Action.VERSION_APPEND,false);var slot=owned(u,caller,uploadId);live(slot);var previous=u.command(command);
   if(previous.isPresent())return new TransferPlan(slot,previous.get());
   if(slot.state()==UploadState.RECEIVING&&!slot.writerSettled()&&(slot.failureCode()!=null||!now().isBefore(slot.leaseUntil())))throw UploadPolicy.unavailable();
   if(slot.state()!=UploadState.INITIALIZED||slot.transferAttempts()>=3)throw conflict();var next=slot.receiving(UUID.randomUUID(),lease(slot,300),key);u.updateSlot(next,slot.revision());return new TransferPlan(next,null);
  });
  boolean attemptedWrite=false;ObjectIdentity object=null;ObjectKey target=UploadSupport.key(caller.tenant(),document,uploadId,false);
  try(var spool=UploadSupport.spool(input,temporary,plan.slot().declaredSize(),clock,300)){
   String fingerprint=UploadSupport.fingerprint(spool.sha256(),spool.size());
   if(plan.previous()!=null){same(plan.previous(),fingerprint);read(caller,document,uploadId,false);return new SlotReceipt(uploadId);}
   attemptedWrite=true;object=storage.put(target,spool.file(),spool.size(),spool.sha256());if(object==null||!object.objectKey().equals(target))throw UploadPolicy.unavailable();ObjectIdentity stored=object;
   return transactions.execute(repository->{repository.lockCommand(command);var u=repository.uploads();u.lockAccounts(caller.tenant());policy.document(repository,caller,document,Action.VERSION_APPEND,false);var current=owned(u,caller,uploadId);sameLease(current,plan.slot());live(current);var next=current.quarantined(stored,spool.sha256());policy.document(repository,caller,document,Action.VERSION_APPEND,false);u.updateSlot(next,current.revision());u.record(new UploadOutcome(command,fingerprint,200,uploadId,null),audit(next,"upload.transfer",null));return new SlotReceipt(uploadId);});
  }catch(RuntimeException failure){if(plan.previous()==null){if(!attemptedWrite)resetTransfer(plan.slot());else transferFailure(plan.slot(),target,object);}throw safe(failure);}
 }
 private record TransferPlan(UploadSlot slot,UploadOutcome previous) { }
 private void resetTransfer(UploadSlot claimed){transactions.execute(r->{var u=r.uploads();u.lockAccounts(claimed.tenant());r.find(claimed.tenant(),claimed.documentId()).orElseThrow(UploadPolicy::hidden);var current=u.slot(claimed.tenant(),claimed.uploadId()).orElseThrow(UploadPolicy::hidden);sameLeaseIdentity(current,claimed);u.updateSlot(current.transferReset(),current.revision());return null;});}
 public CompleteReceipt complete(DocumentCaller caller,UUID uploadId,String key){
  UUID document=locate(caller,uploadId,false);var command=UploadSupport.command(caller,"upload.complete",uploadId.toString(),key);String fingerprint=UploadSupport.fingerprint("complete");
  if(!verification.tryAcquire())throw new DmFailure(429,"QUOTA_EXCEEDED");
  Instant deadline=now().plusSeconds(240);UploadSlot claimed=null;
  try {
   claimed=transactions.execute(repository->{repository.lockCommand(command);var u=repository.uploads();u.lockAccounts(caller.tenant());policy.document(repository,caller,document,Action.VERSION_APPEND,false);
    var slot=owned(u,caller,uploadId);live(slot);var previous=u.command(command);
    if(previous.isPresent()){same(previous.get(),fingerprint);if(u.consumption(caller.tenant(),previous.get().receiptId()).isPresent())throw new DmFailure(409,"RECEIPT_CONSUMED");policy.document(repository,caller,document,Action.VERSION_APPEND,false);return slot;}
    boolean unresolved=slot.possibleOriginal()!=null&&!(slot.writerSettled()&&slot.copyCleanupConfirmed());
    if(unresolved&&(slot.failureCode()!=null||slot.leaseUntil()!=null&&!now().isBefore(slot.leaseUntil())))throw UploadPolicy.unavailable();
    if(slot.state()!=UploadState.QUARANTINED||slot.verificationAttempts()>=3)throw conflict();
    if(unresolved)throw UploadPolicy.unavailable();
    var next=slot.verifying(UUID.randomUUID(),lease(slot,300));u.updateSlot(next,slot.revision());return next;
   });
   if(claimed.state()==UploadState.VERIFIED){var receipt=transactions.execute(r->{policy.document(r,caller,document,Action.VERSION_APPEND,false);var u=r.uploads();var value=u.receiptForUpload(caller.tenant(),uploadId).orElseThrow(UploadPolicy::unavailable);if(u.consumption(caller.tenant(),value.receiptId()).isPresent())throw new DmFailure(409,"RECEIPT_CONSUMED");policy.document(r,caller,document,Action.VERSION_APPEND,false);return value;});return new CompleteReceipt(uploadId,receipt.receiptId());}
   UploadSlot lease=claimed;
   try(var input=storage.open(claimed.quarantine());var source=UploadSupport.spool(input,temporary,claimed.declaredSize(),clock,60)) {
    if(!source.sha256().equals(claimed.bodySha256()))throw UploadPolicy.unavailable();ArtifactSource artifact=new ArtifactSource(source.file(),claimed.format(),source.size(),source.sha256());
    ScanEvidence scan=scanner.scan(artifact);validateScan(scan,artifact);InspectionEvidence inspection=inspector.inspect(artifact);validateInspection(inspection,artifact);
    if(now().isAfter(deadline))throw UploadPolicy.unavailable();
    ObjectKey target=UploadSupport.key(caller.tenant(),document,UUID.randomUUID(),true);
    transactions.execute(repository->{var u=repository.uploads();u.lockAccounts(caller.tenant());policy.document(repository,caller,document,Action.VERSION_APPEND,false);var current=owned(u,caller,uploadId);sameLease(current,lease);live(current);u.updateSlot(current.possibleCopy(target),current.revision());return null;});
    ObjectIdentity original=storage.copy(claimed.quarantine(),target);if(original==null||!original.objectKey().equals(target))throw UploadPolicy.unavailable();
    transactions.execute(repository->{var u=repository.uploads();u.lockAccounts(caller.tenant());repository.find(caller.tenant(),document).orElseThrow(UploadPolicy::hidden);var current=owned(u,caller,uploadId);sameLeaseIdentity(current,lease);u.updateSlot(current.copied(original),current.revision());return null;});
    freshLease(lease);
    try(var readback=storage.open(original);var checked=UploadSupport.spool(readback,temporary,claimed.declaredSize(),clock,60)){if(!checked.sha256().equals(source.sha256()))throw UploadPolicy.unavailable();}
    return transactions.execute(repository->{repository.lockCommand(command);var u=repository.uploads();u.lockAccounts(caller.tenant());policy.document(repository,caller,document,Action.VERSION_APPEND,false);
     var current=owned(u,caller,uploadId);sameLease(current,lease);live(current);Instant at=now();if(at.isAfter(deadline))throw UploadPolicy.unavailable();validateScan(scan,artifact);validateInspection(inspection,artifact);
     var evidence=new VerificationEvidence(UUID.randomUUID(),caller.tenant(),uploadId,current.quarantine(),scan,inspection,at);
     var receipt=new UploadReceipt(UUID.randomUUID(),uploadId,caller.tenant(),document,caller.principal(),source.sha256(),source.size(),current.format(),original,evidence.evidenceId(),at,current.expiresAt());
     policy.document(repository,caller,document,Action.VERSION_APPEND,false);var next=current.verified();u.insertEvidence(evidence);u.insertReceipt(receipt);u.updateSlot(next,current.revision());
     u.record(new UploadOutcome(command,fingerprint,200,uploadId,receipt.receiptId()),audit(next,"upload.complete",null));
     u.enqueueCleanup(cleanup(next,current.quarantine().objectKey(),current.quarantine(),CleanupReason.VERIFIED_QUARANTINE_COPY));return new CompleteReceipt(uploadId,receipt.receiptId());
    });
   }catch(java.io.IOException failure){throw UploadPolicy.unavailable();}
  }catch(RuntimeException failure){if(claimed!=null&&claimed.state()==UploadState.VERIFYING)verificationFailure(claimed,failure);throw safe(failure);}
  finally {verification.release();}
 }
 public Status status(DocumentCaller caller,UUID uploadId){UUID document=locate(caller,uploadId,true);var slot=read(caller,document,uploadId,true);return new Status(slot.uploadId(),slot.state(),slot.expiresAt(),slot.failureCode());}
 public ConsumedVersion discover(DocumentCaller caller,UUID documentId,UUID receiptId){
  policy.route(caller,Action.CONTENT_REVIEW,documentId,true);
  return transactions.execute(repository->{policy.document(repository,caller,documentId,Action.CONTENT_REVIEW,true);var u=repository.uploads();var receipt=u.receipt(caller.tenant(),receiptId).orElseThrow(UploadPolicy::hidden);
   if(!receipt.tenant().equals(caller.tenant())||!receipt.documentId().equals(documentId)||!receipt.actor().equals(caller.principal()))throw UploadPolicy.hidden();var consumption=u.consumption(caller.tenant(),receiptId).orElseThrow(UploadPolicy::hidden);
   if(!consumption.documentId().equals(documentId)||!consumption.actor().equals(caller.principal())||!repository.find(caller.tenant(),documentId).orElseThrow(UploadPolicy::hidden).versions().stream().anyMatch(v->v.versionId().equals(consumption.versionId())))throw UploadPolicy.hidden();
   policy.document(repository,caller,documentId,Action.CONTENT_REVIEW,true);return new ConsumedVersion(documentId,consumption.versionId());
  });
 }
 private UUID locate(DocumentCaller caller,UUID upload,boolean hidden){policy.route(caller,Action.VERSION_APPEND,null,hidden);return transactions.execute(r->owned(r.uploads(),caller,upload).documentId());}
 private UploadSlot read(DocumentCaller caller,UUID document,UUID upload,boolean hidden){return transactions.execute(r->{policy.document(r,caller,document,Action.VERSION_APPEND,hidden);var slot=owned(r.uploads(),caller,upload);policy.document(r,caller,document,Action.VERSION_APPEND,hidden);return slot;});}
 static UploadSlot owned(UploadRepository repository,DocumentCaller caller,UUID upload){var slot=repository.slot(caller.tenant(),upload).orElseThrow(UploadPolicy::hidden);if(!slot.actor().equals(caller.principal())||!slot.tenant().equals(caller.tenant()))throw UploadPolicy.hidden();return slot;}
 private void transferFailure(UploadSlot claimed,ObjectKey key,ObjectIdentity object){ObjectIdentity exact=object!=null&&object.objectKey().equals(key)?object:null;transactions.execute(r->{var u=r.uploads();u.lockAccounts(claimed.tenant());r.find(claimed.tenant(),claimed.documentId());var current=u.slot(claimed.tenant(),claimed.uploadId()).orElseThrow(UploadPolicy::unavailable);if(Objects.equals(current.leaseId(),claimed.leaseId())&&Objects.equals(current.leaseUntil(),claimed.leaseUntil())&&current.state()==UploadState.RECEIVING){var next=current.transferPending(exact!=null);u.updateSlot(next,current.revision());u.enqueueCleanup(cleanup(next,key,exact,CleanupReason.TRANSFER_ORPHAN));u.audit(audit(next,"upload.transfer.failed","TRANSFER_INCOMPLETE"));}return null;});}
 private void verificationFailure(UploadSlot claimed,RuntimeException failure){transactions.execute(r->{var u=r.uploads();u.lockAccounts(claimed.tenant());r.find(claimed.tenant(),claimed.documentId());var current=u.slot(claimed.tenant(),claimed.uploadId()).orElseThrow(UploadPolicy::unavailable);
  if(Objects.equals(current.leaseId(),claimed.leaseId())&&Objects.equals(current.leaseUntil(),claimed.leaseUntil())&&current.state()==UploadState.VERIFYING){boolean rejected=failure instanceof DmFailure d&&d.status()==422;var next=rejected?current.terminal(UploadState.REJECTED,((DmFailure)failure).code()):current.transientFailure();u.updateSlot(next,current.revision());u.audit(audit(next,"upload.verify.failed",next.failureCode()));
   if(current.possibleOriginal()!=null)u.enqueueCleanup(cleanup(next,current.possibleOriginal(),current.original(),CleanupReason.FAILED_VERIFIED_COPY));if(rejected)u.enqueueCleanup(cleanup(next,current.quarantine().objectKey(),current.quarantine(),CleanupReason.REJECTED));}return null;});}
 private void validateScan(ScanEvidence scan,ArtifactSource source){Instant at=now();if(scan==null||!scan.sourceSha256().equals(source.sha256())||scan.sizeBytes()!=source.sizeBytes()||!"1.5.4".equals(scan.engineVersion())||scan.databaseUpdatedAt().isAfter(at)||scan.databaseUpdatedAt().isBefore(at.minusSeconds(86400))||scan.scannedAt().isAfter(at)||scan.scannedAt().isBefore(scan.databaseUpdatedAt()))throw UploadPolicy.unavailable();}
 private void validateInspection(InspectionEvidence result,ArtifactSource source){if(result==null||result.format()!=source.format()||result.sizeBytes()!=source.sizeBytes()||!result.sha256().equals(source.sha256())||result.inspectedAt().isAfter(now()))throw UploadPolicy.unavailable();}
 private static Accounts reserve(Accounts before,long size){ResourceAccount g=before.global(),t=before.tenant();if(t.admittedCount()>=5||g.admittedCount()>=10||t.transientBytes()>1_000_000_000L-2*size||g.transientBytes()>2_000_000_000L-2*size||t.promisedBytes()>5_000_000_000L||t.retainedBytes()>5_000_000_000L-t.promisedBytes()-size||g.promisedBytes()>100_000_000_000L||g.retainedBytes()>100_000_000_000L-g.promisedBytes()-size)throw new DmFailure(429,"QUOTA_EXCEEDED");return new Accounts(g.change(1,2*size,size,0),t.change(1,2*size,size,0));}
 private CleanupWork cleanup(UploadSlot slot,ObjectKey key,ObjectIdentity object,CleanupReason reason){return new CleanupWork(UUID.randomUUID(),slot.tenant(),slot.uploadId(),slot.documentId(),key,object,reason,now(),false);}
 private UploadAudit audit(UploadSlot slot,String operation,String code){return new UploadAudit(UUID.randomUUID(),slot.tenant(),slot.documentId(),slot.uploadId(),slot.actor(),operation,slot.revision(),now(),code==null?"OK":code);}
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
 private Instant lease(UploadSlot slot,int seconds){Instant until=now().plusSeconds(seconds);return until.isAfter(slot.expiresAt())?slot.expiresAt():until;}
 private void live(UploadSlot slot){if(slot.expired(now())||slot.state()==UploadState.EXPIRED)throw new DmFailure(409,"UPLOAD_EXPIRED");}
 private static void same(UploadOutcome old,String hash){if(!old.fingerprint().equals(hash))throw new DmFailure(409,"IDEMPOTENCY_CONFLICT");}
 private void sameLease(UploadSlot current,UploadSlot claimed){sameLeaseIdentity(current,claimed);freshLease(current);}
 private void freshLease(UploadSlot slot){if(slot.leaseUntil()==null||!now().isBefore(slot.leaseUntil()))throw UploadPolicy.unavailable();}
 private static void sameLeaseIdentity(UploadSlot current,UploadSlot claimed){if(current.state()!=claimed.state()||!Objects.equals(current.leaseId(),claimed.leaseId())||!Objects.equals(current.leaseUntil(),claimed.leaseUntil()))throw conflict();}
 private static DmFailure conflict(){return new DmFailure(409,"UPLOAD_STATE_CONFLICT");}
 private static RuntimeException safe(RuntimeException failure){return failure instanceof DmFailure?failure:UploadPolicy.unavailable();}
}
