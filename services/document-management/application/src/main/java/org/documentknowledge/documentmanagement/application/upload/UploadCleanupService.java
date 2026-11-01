package org.documentknowledge.documentmanagement.application.upload;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.upload.*;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
/** Internal system work only. No public delete/hold-release authority is exposed. Unknown remote attempts never transfer ownership. */
public final class UploadCleanupService {
 private final DocumentUnitOfWork transactions;private final CleanupStoragePort storage;private final Clock clock;
 public UploadCleanupService(DocumentUnitOfWork transactions,CleanupStoragePort storage,Clock clock){this.transactions=Objects.requireNonNull(transactions);this.storage=Objects.requireNonNull(storage);this.clock=Objects.requireNonNull(clock);}
 public record SweepResult(int expired,int cleaned,int blocked) { }
 public SweepResult sweep(UUID executor,int limit){if(limit<1||limit>50)throw new IllegalArgumentException("Invalid cleanup batch");int expired=0,cleaned=0,blocked=0;
  for(var slot:transactions.execute(r->r.uploads().expirable(now(),limit))){try{expire(slot.tenant(),slot.uploadId());expired++;}catch(DmFailure unavailable){blocked++;}}
  for(var work:transactions.execute(r->r.uploads().dueCleanup(now(),limit))){try{if(cleanup(work.tenant(),work.workId(),executor))cleaned++;else{blocked++;defer(work);}}catch(DmFailure unavailable){blocked++;defer(work);}}
  return new SweepResult(expired,cleaned,blocked);
 }
 private void defer(CleanupWork work){transactions.execute(r->{var u=r.uploads();var current=u.cleanup(work.tenant(),work.workId());if(current.isPresent()&&!current.get().complete())u.deferCleanup(current.get(),now().plusSeconds(60));return null;});}
 public void expire(String tenant,UUID uploadId){
  UUID document=transactions.execute(r->r.uploads().slot(tenant,uploadId).orElseThrow(UploadPolicy::hidden).documentId());
  transactions.execute(r->{var u=r.uploads();var accounts=u.lockAccounts(tenant);r.find(tenant,document).orElseThrow(UploadPolicy::hidden);var slot=u.slot(tenant,uploadId).orElseThrow(UploadPolicy::hidden);
   if(!slot.expired(now())||slot.state()==UploadState.CONSUMED||slot.state()==UploadState.EXPIRED||slot.state()==UploadState.REJECTED)return null;
   var next=slot.terminal(UploadState.EXPIRED,"UPLOAD_EXPIRED");
   if(slot.state()==UploadState.INITIALIZED){next=next.accounting(0,false);long bytes=slot.transientReservation();u.saveAccounts(tenant,accounts,new Accounts(accounts.global().change(-1,-bytes,-slot.declaredSize(),0),accounts.tenant().change(-1,-bytes,-slot.declaredSize(),0)));u.updateSlot(next,slot.revision());return null;}
   u.updateSlot(next,slot.revision());
   ObjectKey source=UploadSupport.key(tenant,document,uploadId,false);if(!slot.quarantineCleanupConfirmed()&&u.pendingCleanup(tenant,uploadId).stream().noneMatch(w->w.key().equals(source)))u.enqueueCleanup(work(next,source,slot.quarantine(),CleanupReason.EXPIRED));
   if(slot.possibleOriginal()!=null&&!slot.copyCleanupConfirmed())u.enqueueCleanup(work(next,slot.possibleOriginal(),slot.original(),CleanupReason.EXPIRED));return null;
  });
 }
 public boolean cleanup(String tenant,UUID workId,UUID executor){
  CleanupWork work=transactions.execute(r->r.uploads().cleanup(tenant,workId).orElseThrow(UploadPolicy::hidden));if(work.complete())return true;
  if(work.object()==null)return reconcile(work);
  // No HEAD/list/lease heuristic can settle an unknown writer. These records remain charged.
  DeletionFence authorized=transactions.execute(r->{var u=r.uploads();u.lockAccounts(tenant);var document=r.find(tenant,work.documentId()).orElseThrow(UploadPolicy::hidden);var slot=u.slot(tenant,work.uploadId()).orElseThrow(UploadPolicy::hidden);
   var current=u.cleanup(tenant,workId).orElseThrow(UploadPolicy::hidden);if(current.complete())return null;
   if(!document.holds().isEmpty()||u.unresolvedFence(tenant,work.documentId()).isPresent()||current.object()==null||!slot.writerSettled())throw UploadPolicy.unavailable();
   if("dkp-originals".equals(current.key().bucket())){var receipt=u.receiptForUpload(tenant,slot.uploadId());if(receipt.isPresent()&&u.consumption(tenant,receipt.get().receiptId()).isPresent()||slot.state()==UploadState.CONSUMED)throw UploadPolicy.unavailable();
    if(current.reason()!=CleanupReason.FAILED_VERIFIED_COPY&&slot.state()!=UploadState.EXPIRED&&slot.state()!=UploadState.REJECTED)throw UploadPolicy.unavailable();}
   else if(current.reason()!=CleanupReason.VERIFIED_QUARANTINE_COPY&&current.reason()!=CleanupReason.TRANSFER_ORPHAN&&slot.state()!=UploadState.EXPIRED&&slot.state()!=UploadState.REJECTED)throw UploadPolicy.unavailable();
   var fence=new DeletionFence(UUID.randomUUID(),tenant,work.documentId(),work.uploadId(),current.object(),executor,now(),FencePhase.AUTHORIZED);u.insertFence(fence);return fence;
  });
  if(authorized==null)return true;
  DeletionFence dispatched=transactions.execute(r->{var u=r.uploads();u.lockAccounts(tenant);r.find(tenant,work.documentId()).orElseThrow(UploadPolicy::hidden);var fence=u.unresolvedFence(tenant,work.documentId()).orElseThrow(UploadPolicy::unavailable);
   if(!fence.equals(authorized))throw UploadPolicy.unavailable();var next=fence.phase(FencePhase.DISPATCHED);u.dispatchFence(fence,next);return next;});
  UploadStoragePort.DeletionResult result;
  try{result=storage.deleteOnce(dispatched);}catch(RuntimeException unknown){throw UploadPolicy.unavailable();}
  if(result==null||!dispatched.attemptId().equals(result.attemptId())||!dispatched.object().equals(result.object())||result.requestId()==null||result.requestId().isBlank()||!result.terminal()||!result.noFurtherExecution())throw UploadPolicy.unavailable();
  if(result.successful()&&!result.absent())throw UploadPolicy.unavailable();
  return transactions.execute(r->{var u=r.uploads();var accounts=u.lockAccounts(tenant);r.find(tenant,work.documentId()).orElseThrow(UploadPolicy::hidden);var slot=u.slot(tenant,work.uploadId()).orElseThrow(UploadPolicy::hidden);var fence=u.unresolvedFence(tenant,work.documentId()).orElseThrow(UploadPolicy::unavailable);if(!fence.equals(dispatched))throw UploadPolicy.unavailable();
   var terminal=new DeletionFence(fence.attemptId(),tenant,fence.documentId(),fence.uploadId(),fence.object(),executor,fence.createdAt(),result.successful()?FencePhase.SETTLED_SUCCESS:FencePhase.SETTLED_FAILURE);
   u.settleFence(fence,terminal,result.requestId(),now());if(!result.successful())return false;
   finish(u,accounts,slot,work);
   return true;
  });
 }
 private void finish(UploadRepository u,Accounts accounts,UploadSlot slot,CleanupWork work){String tenant=slot.tenant();
   u.completeCleanup(work);boolean original="dkp-originals".equals(work.key().bucket());
   boolean terminalSlot=slot.state()==UploadState.EXPIRED||slot.state()==UploadState.REJECTED;
   boolean noMore=u.pendingCleanup(slot.tenant(),slot.uploadId()).stream().noneMatch(c->!c.complete()&&!c.workId().equals(work.workId()));
   boolean release=terminalSlot&&slot.quotaHeld()&&noMore;
   long released=release?slot.transientReservation():(terminalSlot||slot.state()==UploadState.CONSUMED?Math.min(slot.declaredSize(),slot.transientReservation()):0);
   long slotsDelta=release?-1:0,promisedDelta=release?-slot.declaredSize():0;
   var next=slot.cleanupSucceeded(original,slot.transientReservation()-released,release);
   u.updateSlot(next,slot.revision());if(released!=0||release)u.saveAccounts(slot.tenant(),accounts,new Accounts(accounts.global().change(slotsDelta,-released,promisedDelta,0),accounts.tenant().change(slotsDelta,-released,promisedDelta,0)));
 }
 private boolean reconcile(CleanupWork work){
  // Listing is not settlement evidence: the durable independently established writerSettled bit is mandatory first.
  transactions.execute(r->{var u=r.uploads();u.lockAccounts(work.tenant());r.find(work.tenant(),work.documentId()).orElseThrow(UploadPolicy::hidden);var slot=u.slot(work.tenant(),work.uploadId()).orElseThrow(UploadPolicy::hidden);if(!slot.writerSettled()||u.unresolvedFence(work.tenant(),work.documentId()).isPresent())throw UploadPolicy.unavailable();return null;});
  var versions=storage.versions(work.key());if(versions==null||versions.size()>1||versions.stream().anyMatch(v->!v.objectKey().equals(work.key())))throw UploadPolicy.unavailable();
  return transactions.execute(r->{var u=r.uploads();var accounts=u.lockAccounts(work.tenant());r.find(work.tenant(),work.documentId()).orElseThrow(UploadPolicy::hidden);var slot=u.slot(work.tenant(),work.uploadId()).orElseThrow(UploadPolicy::hidden);
   if(!slot.writerSettled()||u.unresolvedFence(work.tenant(),work.documentId()).isPresent())throw UploadPolicy.unavailable();var current=u.cleanup(work.tenant(),work.workId()).orElseThrow(UploadPolicy::hidden);if(current.complete())return true;
   if(versions.isEmpty()){finish(u,accounts,slot,current);return true;}
   u.enqueueCleanup(new CleanupWork(UUID.randomUUID(),work.tenant(),work.uploadId(),work.documentId(),work.key(),versions.getFirst(),work.reason(),now(),false));u.completeCleanup(current);return false;
  });
 }
 private CleanupWork work(UploadSlot slot,ObjectKey key,ObjectIdentity object,CleanupReason reason){return new CleanupWork(UUID.randomUUID(),slot.tenant(),slot.uploadId(),slot.documentId(),key,object,reason,now(),false);}
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
}
