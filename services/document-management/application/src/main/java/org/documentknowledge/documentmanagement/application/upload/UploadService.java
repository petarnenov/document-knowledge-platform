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
 private final VerificationWorkspacePort workspaces;private final GenericTempPort genericTemps;
 private final Semaphore verification=new Semaphore(1),transfers=new Semaphore(10);
 private final Set<UUID> transferring=java.util.concurrent.ConcurrentHashMap.newKeySet();
 public UploadService(DocumentUnitOfWork transactions,ResourceAuthorizationPort grants,UploadStoragePort storage,
     MalwareScanPort scanner,StructureInspectionPort inspector,Clock clock,Path temporary){
  this(transactions,grants,storage,scanner,inspector,clock,temporary,VerificationWorkspacePort.unavailable());
 }
 public UploadService(DocumentUnitOfWork transactions,ResourceAuthorizationPort grants,UploadStoragePort storage,
     MalwareScanPort scanner,StructureInspectionPort inspector,Clock clock,Path temporary,VerificationWorkspacePort workspaces){this(transactions,grants,storage,scanner,inspector,clock,temporary,workspaces,GenericTempPort.unavailable());}
 public UploadService(DocumentUnitOfWork transactions,ResourceAuthorizationPort grants,UploadStoragePort storage,
     MalwareScanPort scanner,StructureInspectionPort inspector,Clock clock,Path temporary,VerificationWorkspacePort workspaces,GenericTempPort genericTemps){
  this.genericTemps=Objects.requireNonNull(genericTemps);this.workspaces=Objects.requireNonNull(workspaces);
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
  var budget=new GenericTempBudget(clock,GenericTempPort.Role.TRANSFER);return budget.execute(()->transferOwned(caller,uploadId,key,input,budget));
 }
 private SlotReceipt transferOwned(DocumentCaller caller,UUID uploadId,String key,InputStream input,GenericTempBudget budget){
  budget.check();UUID document=locate(caller,uploadId,false,budget);read(caller,document,uploadId,false,budget);budget.check();
  if(!transfers.tryAcquire())throw new DmFailure(429,"QUOTA_EXCEEDED");
  if(!transferring.add(uploadId)){transfers.release();throw new DmFailure(429,"QUOTA_EXCEEDED");}
  try{return transferBounded(caller,uploadId,key,input,budget);}finally{transferring.remove(uploadId);transfers.release();}
 }
 private SlotReceipt transferBounded(DocumentCaller caller,UUID uploadId,String key,InputStream input,GenericTempBudget budget){
  Objects.requireNonNull(input);UUID document=locate(caller,uploadId,false,budget);var command=UploadSupport.command(caller,"upload.transfer",uploadId.toString(),key);
  budget.check();TransferPlan plan=transactions.execute(repository->{budget.check();boundary(budget,()->repository.lockCommand(command));var u=repository.uploads();checked(budget,()->u.lockAccounts(caller.tenant()));policy.document(repository,caller,document,Action.VERSION_APPEND,false,budget::check);var slot=owned(u,caller,uploadId,budget);live(slot);var previous=checked(budget,()->u.command(command));
   if(previous.isPresent())return new TransferPlan(slot,previous.get());
   if(slot.state()==UploadState.RECEIVING&&!slot.writerSettled()&&(slot.failureCode()!=null||!now().isBefore(slot.leaseUntil())))throw UploadPolicy.unavailable();
   if(slot.state()!=UploadState.INITIALIZED||slot.transferAttempts()>=3)throw conflict();var next=slot.receiving(UUID.randomUUID(),lease(slot,300),key);boundary(budget,()->u.updateSlot(next,slot.revision()));return new TransferPlan(next,null);
  });
  boolean attemptedWrite=false;ObjectIdentity object=null;ObjectKey target=UploadSupport.key(caller.tenant(),document,uploadId,false);
  try {budget.check();try(var workspace=genericTemps.reserve(GenericTempPort.Role.TRANSFER,GenericTempPort.Identity.of(caller.tenant(),caller.actor(),document,uploadId,null,plan.slot().declaredSize()),budget)){
   boolean cleanupAttempted=false;
   try {
    var spool=workspace.write(input,budget);String fingerprint=UploadSupport.fingerprint(spool.sha256(),spool.size());
    if(plan.previous()!=null){same(plan.previous(),fingerprint);read(caller,document,uploadId,false,budget);cleanupAttempted=true;workspace.finish(budget);budget.check();return new SlotReceipt(uploadId);}
    budget.check();attemptedWrite=true;
    try(var reader=workspace.retain(budget)){object=storage.put(target,spool.path(),spool.size(),spool.sha256(),budget);}
    if(object==null||!object.objectKey().equals(target))throw UploadPolicy.unavailable();ObjectIdentity stored=object;
    cleanupAttempted=true;workspace.finish(budget);budget.check();
    return transactions.execute(repository->{budget.check();boundary(budget,()->repository.lockCommand(command));var u=repository.uploads();checked(budget,()->u.lockAccounts(caller.tenant()));policy.document(repository,caller,document,Action.VERSION_APPEND,false,budget::check);var current=owned(u,caller,uploadId,budget);sameLease(current,plan.slot());live(current);var next=current.quarantined(stored,spool.sha256());policy.document(repository,caller,document,Action.VERSION_APPEND,false,budget::check);boundary(budget,()->u.updateSlot(next,current.revision()));boundary(budget,()->u.record(new UploadOutcome(command,fingerprint,200,uploadId,null),audit(next,"upload.transfer",null)));budget.check();return new SlotReceipt(uploadId);});
   }catch(RuntimeException failure){if(!cleanupAttempted){cleanupAttempted=true;workspace.finish(budget);}throw failure;}
  }}catch(RuntimeException failure){if(plan.previous()==null){if(!attemptedWrite)resetTransfer(plan.slot());else transferFailure(plan.slot(),target,object);}throw safe(failure);}
 }
 private record TransferPlan(UploadSlot slot,UploadOutcome previous) { }
 private void resetTransfer(UploadSlot claimed){transactions.execute(r->{var u=r.uploads();u.lockAccounts(claimed.tenant());r.find(claimed.tenant(),claimed.documentId()).orElseThrow(UploadPolicy::hidden);var current=u.slot(claimed.tenant(),claimed.uploadId()).orElseThrow(UploadPolicy::hidden);sameLeaseIdentity(current,claimed);u.updateSlot(current.transferReset(),current.revision());return null;});}
 public CompleteReceipt complete(DocumentCaller caller,UUID uploadId,String key){
  var budget=new CompleteBudget(clock);
  return budget.execute(()->completeBounded(caller,uploadId,key,budget));
 }
 private CompleteReceipt completeBounded(DocumentCaller caller,UUID uploadId,String key,CompleteBudget budget){
  try(var preparation=budget.phase(CompleteBudget.Kind.STORAGE)){
  UUID document=locate(caller,uploadId,false,preparation);read(caller,document,uploadId,false,preparation);var command=UploadSupport.command(caller,"upload.complete",uploadId.toString(),key);String fingerprint=UploadSupport.fingerprint("complete");
  preparation.check();if(!verification.tryAcquire())throw new DmFailure(429,"QUOTA_EXCEEDED");
  UploadSlot claimed=null;
  try {
   preparation.check();claimed=transactions.execute(repository->{preparation.check();boundary(preparation,()->repository.lockCommand(command));var u=repository.uploads();checked(preparation,()->u.lockAccounts(caller.tenant()));policy.document(repository,caller,document,Action.VERSION_APPEND,false,preparation::check);
    var slot=owned(u,caller,uploadId,preparation);live(slot);var previous=checked(preparation,()->u.command(command));
    if(previous.isPresent()){same(previous.get(),fingerprint);if(checked(preparation,()->u.consumption(caller.tenant(),previous.get().receiptId())).isPresent())throw new DmFailure(409,"RECEIPT_CONSUMED");policy.document(repository,caller,document,Action.VERSION_APPEND,false,preparation::check);preparation.check();return slot;}
    boolean unresolved=slot.possibleOriginal()!=null&&!(slot.writerSettled()&&slot.copyCleanupConfirmed());
    if(unresolved&&(slot.failureCode()!=null||slot.leaseUntil()!=null&&!now().isBefore(slot.leaseUntil())))throw UploadPolicy.unavailable();
    if(slot.state()!=UploadState.QUARANTINED||slot.verificationAttempts()>=3)throw conflict();
    if(unresolved)throw UploadPolicy.unavailable();
    var next=slot.verifying(UUID.randomUUID(),lease(slot,300));boundary(preparation,()->u.updateSlot(next,slot.revision()));return next;
   });
   preparation.check();preparation.close();budget.check();
   if(claimed.state()==UploadState.VERIFIED){try(var replay=budget.phase(CompleteBudget.Kind.STORAGE)){replay.check();var receipt=transactions.execute(r->{replay.check();policy.document(r,caller,document,Action.VERSION_APPEND,false,replay::check);var u=r.uploads();var value=checked(replay,()->u.receiptForUpload(caller.tenant(),uploadId)).orElseThrow(UploadPolicy::unavailable);if(checked(replay,()->u.consumption(caller.tenant(),value.receiptId())).isPresent())throw new DmFailure(409,"RECEIPT_CONSUMED");policy.document(r,caller,document,Action.VERSION_APPEND,false,replay::check);replay.check();return value;});replay.check();return new CompleteReceipt(uploadId,receipt.receiptId());}}
   UploadSlot lease=claimed;
   try(var workspace=reserveWorkspace(caller,claimed,budget)) {
   boolean cleanupAttempted=false;
   try {
    var source=completeSpool(claimed.quarantine(),workspace,budget);
    if(!source.sha256().equals(claimed.bodySha256()))throw UploadPolicy.unavailable();ArtifactSource artifact=new ArtifactSource(source.path(),claimed.format(),source.size(),source.sha256());
    ScanEvidence scan;try(var phase=budget.phase(CompleteBudget.Kind.SCAN);var use=workspace.retain(phase)){scan=scanner.scan(artifact,phase);phase.check();validateScan(scan,artifact);}
    InspectionEvidence inspection;try(var phase=budget.phase(CompleteBudget.Kind.INSPECT);var use=workspace.retain(phase)){inspection=inspector.inspect(artifact,phase);phase.check();validateInspection(inspection,artifact);}
    budget.check();
    try(var copying=budget.phase(CompleteBudget.Kind.STORAGE)){
    ObjectKey target=UploadSupport.key(caller.tenant(),document,UUID.randomUUID(),true);
    copying.check();transactions.execute(repository->{copying.check();var u=repository.uploads();checked(copying,()->u.lockAccounts(caller.tenant()));policy.document(repository,caller,document,Action.VERSION_APPEND,false,copying::check);var current=owned(u,caller,uploadId,copying);sameLease(current,lease);live(current);boundary(copying,()->u.updateSlot(current.possibleCopy(target),current.revision()));return null;});
    copying.check();ObjectIdentity original=storage.copy(claimed.quarantine(),target,copying,workspace);if(original==null||!original.objectKey().equals(target))throw UploadPolicy.unavailable();
    // Settlement-only binding of a known remote write; not receipt publication or a rollback claim.
    transactions.execute(repository->{var u=repository.uploads();u.lockAccounts(caller.tenant());repository.find(caller.tenant(),document).orElseThrow(UploadPolicy::hidden);var current=owned(u,caller,uploadId);sameLeaseIdentity(current,lease);u.updateSlot(current.copied(original),current.revision());return null;});
    copying.check();freshLease(lease);
    var readbackBuffer=workspace.scratch(VerificationWorkspacePort.Role.READBACK,copying);
    try(var readback=storage.open(original,copying);var abort=copying.onCancel(()->closeStream(readback))){var checked=readbackBuffer.write(readback,copying);copying.check();if(!checked.sha256().equals(source.sha256()))throw UploadPolicy.unavailable();}
    readbackBuffer.cleanup(copying);
    // Confirm all local absence and durable ownership release BEFORE receipt publication.
    cleanupAttempted=true;workspace.finish(copying);copying.check();
    copying.close();
    try(var persistence=budget.phase(CompleteBudget.Kind.STORAGE)){
    persistence.check();return transactions.execute(repository->{persistence.check();boundary(persistence,()->repository.lockCommand(command));var u=repository.uploads();checked(persistence,()->u.lockAccounts(caller.tenant()));policy.document(repository,caller,document,Action.VERSION_APPEND,false,persistence::check);
     var current=owned(u,caller,uploadId,persistence);sameLease(current,lease);live(current);Instant at=now();persistence.check();validateScan(scan,artifact);validateInspection(inspection,artifact);
     var evidence=new VerificationEvidence(UUID.randomUUID(),caller.tenant(),uploadId,current.quarantine(),scan,inspection,at);
     var receipt=new UploadReceipt(UUID.randomUUID(),uploadId,caller.tenant(),document,caller.principal(),source.sha256(),source.size(),current.format(),original,evidence.evidenceId(),at,current.expiresAt());
     policy.document(repository,caller,document,Action.VERSION_APPEND,false,persistence::check);var next=current.verified();boundary(persistence,()->u.insertEvidence(evidence));boundary(persistence,()->u.insertReceipt(receipt));boundary(persistence,()->u.updateSlot(next,current.revision()));
     boundary(persistence,()->u.record(new UploadOutcome(command,fingerprint,200,uploadId,receipt.receiptId()),audit(next,"upload.complete",null)));
     boundary(persistence,()->u.enqueueCleanup(cleanup(next,current.quarantine().objectKey(),current.quarantine(),CleanupReason.VERIFIED_QUARANTINE_COPY)));persistence.check();return new CompleteReceipt(uploadId,receipt.receiptId());
    });}
    }
   }catch(RuntimeException|java.io.IOException failure){
    if(!cleanupAttempted){cleanupAttempted=true;try(var cleanup=budget.phase(CompleteBudget.Kind.STORAGE)){workspace.finish(cleanup);cleanup.check();}}
    throw failure instanceof RuntimeException runtime?runtime:UploadPolicy.unavailable();
   }
   }
  }catch(RuntimeException failure){if(claimed!=null&&claimed.state()==UploadState.VERIFYING)verificationFailure(claimed,failure);throw safe(failure);}
  finally{verification.release();}
  }
 }
 private VerificationWorkspacePort.Workspace reserveWorkspace(DocumentCaller caller,UploadSlot slot,CompleteBudget budget){
  try(var phase=budget.phase(CompleteBudget.Kind.STORAGE)){var result=workspaces.reserve(VerificationWorkspacePort.Identity.of(caller.tenant(),caller.actor(),slot.documentId(),slot.uploadId(),slot.declaredSize()),phase);try{phase.check();return result;}catch(RuntimeException failure){result.close();throw failure;}}
 }
 private VerificationWorkspacePort.ArtifactFile completeSpool(ObjectIdentity object,VerificationWorkspacePort.Workspace workspace,CompleteBudget budget){
  try(var phase=budget.phase(CompleteBudget.Kind.STORAGE);var input=storage.open(object,phase);var abort=phase.onCancel(()->closeStream(input))){
   var spool=workspace.source().write(input,phase);phase.check();return spool;
  }catch(java.io.IOException failure){throw UploadPolicy.unavailable();}
 }
 private static void closeStream(InputStream stream){try{stream.close();}catch(java.io.IOException ignored){}}
 public Status status(DocumentCaller caller,UUID uploadId){UUID document=locate(caller,uploadId,true);var slot=read(caller,document,uploadId,true);return new Status(slot.uploadId(),slot.state(),slot.expiresAt(),slot.failureCode());}
 public ConsumedVersion discover(DocumentCaller caller,UUID documentId,UUID receiptId){
  policy.route(caller,Action.CONTENT_REVIEW,documentId,true);
  return transactions.execute(repository->{policy.document(repository,caller,documentId,Action.CONTENT_REVIEW,true);var u=repository.uploads();var receipt=u.receipt(caller.tenant(),receiptId).orElseThrow(UploadPolicy::hidden);
   if(!receipt.tenant().equals(caller.tenant())||!receipt.documentId().equals(documentId)||!receipt.actor().equals(caller.principal()))throw UploadPolicy.hidden();var consumption=u.consumption(caller.tenant(),receiptId).orElseThrow(UploadPolicy::hidden);
   if(!consumption.documentId().equals(documentId)||!consumption.actor().equals(caller.principal())||!repository.find(caller.tenant(),documentId).orElseThrow(UploadPolicy::hidden).versions().stream().anyMatch(v->v.versionId().equals(consumption.versionId())))throw UploadPolicy.hidden();
   policy.document(repository,caller,documentId,Action.CONTENT_REVIEW,true);return new ConsumedVersion(documentId,consumption.versionId());
  });
 }
 private static <T>T checked(GenericTempBudget budget,java.util.function.Supplier<T> query){budget.check();T result=query.get();budget.check();return result;}
 private static void boundary(GenericTempBudget budget,Runnable action){budget.check();action.run();budget.check();}
 private UUID locate(DocumentCaller caller,UUID upload,boolean hidden,GenericTempBudget budget){
  policy.route(caller,Action.VERSION_APPEND,null,hidden,budget::check);
  return checked(budget,()->transactions.execute(r->{budget.check();return owned(r.uploads(),caller,upload,budget).documentId();}));
 }
 private UploadSlot read(DocumentCaller caller,UUID document,UUID upload,boolean hidden,GenericTempBudget budget){
  return checked(budget,()->transactions.execute(r->{budget.check();policy.document(r,caller,document,Action.VERSION_APPEND,hidden,budget::check);var slot=owned(r.uploads(),caller,upload,budget);policy.document(r,caller,document,Action.VERSION_APPEND,hidden,budget::check);budget.check();return slot;}));
 }
 private static UploadSlot owned(UploadRepository repository,DocumentCaller caller,UUID upload,GenericTempBudget budget){
  var found=checked(budget,()->repository.slot(caller.tenant(),upload));var slot=found.orElseThrow(UploadPolicy::hidden);if(!slot.actor().equals(caller.principal())||!slot.tenant().equals(caller.tenant()))throw UploadPolicy.hidden();budget.check();return slot;
 }
 private static <T>T checked(CompleteBudget.Phase phase,java.util.function.Supplier<T> query){phase.check();T result=query.get();phase.check();return result;}
 private static void boundary(CompleteBudget.Phase phase,Runnable action){phase.check();action.run();phase.check();}
 private UUID locate(DocumentCaller caller,UUID upload,boolean hidden,CompleteBudget.Phase phase){
  policy.route(caller,Action.VERSION_APPEND,null,hidden,phase::check);return checked(phase,()->transactions.execute(r->{phase.check();return owned(r.uploads(),caller,upload,phase).documentId();}));
 }
 private UploadSlot read(DocumentCaller caller,UUID document,UUID upload,boolean hidden,CompleteBudget.Phase phase){
  return checked(phase,()->transactions.execute(r->{phase.check();policy.document(r,caller,document,Action.VERSION_APPEND,hidden,phase::check);var slot=owned(r.uploads(),caller,upload,phase);policy.document(r,caller,document,Action.VERSION_APPEND,hidden,phase::check);phase.check();return slot;}));
 }
 private static UploadSlot owned(UploadRepository repository,DocumentCaller caller,UUID upload,CompleteBudget.Phase phase){
  var found=checked(phase,()->repository.slot(caller.tenant(),upload));var slot=found.orElseThrow(UploadPolicy::hidden);if(!slot.actor().equals(caller.principal())||!slot.tenant().equals(caller.tenant()))throw UploadPolicy.hidden();phase.check();return slot;
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
