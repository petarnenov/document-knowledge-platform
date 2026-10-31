package org.documentknowledge.documentmanagement.application;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.domain.upload.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.documentknowledge.documentmanagement.application.upload.UploadRepository.*;
/** Explicit transaction-bound test repository. Rollback snapshots include receipt consumption/fences/accounts. */
class UploadMemory implements DocumentUnitOfWork,DocumentRepository {
 Map<UUID,DocumentAggregate> docs=new HashMap<>();Map<CommandKey,StoredCommand> commands=new HashMap<>();List<AuditFact> audits=new ArrayList<>();int finds,executions;boolean failRecord;
 final Uploads uploads=new Uploads();
 public synchronized <T>T execute(Function<DocumentRepository,T> work){executions++;var d=new HashMap<>(docs);var c=new HashMap<>(commands);var a=new ArrayList<>(audits);var state=uploads.snapshot();try{return work.apply(this);}catch(RuntimeException failure){docs=d;commands=c;audits=a;uploads.restore(state);throw failure;}}
 public UploadRepository uploads(){return uploads;}public void lockCommand(CommandKey key){}
 public Optional<StoredCommand> command(CommandKey key){return Optional.ofNullable(commands.get(key));}
 public Optional<DocumentAggregate> find(String tenant,UUID id){finds++;return Optional.ofNullable(docs.get(id)).filter(d->d.tenant().equals(tenant));}
 public List<DocumentAggregate> listVisible(String tenant,Principal actor,Action action,PagePosition position,int limit){return docs.values().stream().filter(d->d.tenant().equals(tenant)&&d.permits(actor,action)).sorted(Comparator.comparing(DocumentAggregate::createdAt).thenComparing(d->d.documentId().toString())).limit(limit).toList();}
 public void insert(DocumentAggregate document){if(docs.putIfAbsent(document.documentId(),document)!=null)throw new IllegalStateException();}
 public void update(DocumentAggregate document,long expected){if(docs.get(document.documentId()).revision()!=expected)throw new DmFailure(409,"REVISION_CONFLICT");docs.put(document.documentId(),document);}
 public void record(StoredCommand command,AuditFact audit){if(failRecord)throw new IllegalStateException();commands.put(command.key(),command);audits.add(audit);}
 record Ref(String tenant,UUID id) { }
 class Uploads implements UploadRepository {
  boolean failDispatch,failSettle;
  Map<Ref,UploadSlot> slots=new HashMap<>();Map<Ref,UploadReceipt> receipts=new HashMap<>();Map<Ref,VerificationEvidence> evidence=new HashMap<>();Map<Ref,ReceiptConsumption> consumed=new HashMap<>();Map<CommandKey,UploadOutcome> outcomes=new HashMap<>();Map<Ref,ResourceAccount> accounts=new HashMap<>();Map<Ref,DeletionFence> fences=new HashMap<>();Map<Ref,CleanupWork> cleanup=new HashMap<>();List<UploadAudit> audit=new ArrayList<>();ResourceAccount global=ResourceAccount.empty();Map<Ref,Instant> deferred=new HashMap<>();
  record State(Map<Ref,UploadSlot>s,Map<Ref,UploadReceipt>r,Map<Ref,VerificationEvidence>e,Map<Ref,ReceiptConsumption>c,Map<CommandKey,UploadOutcome>o,Map<Ref,ResourceAccount>a,Map<Ref,DeletionFence>f,Map<Ref,CleanupWork>w,List<UploadAudit>u,ResourceAccount g,Map<Ref,Instant>deferred){}
  State snapshot(){return new State(new HashMap<>(slots),new HashMap<>(receipts),new HashMap<>(evidence),new HashMap<>(consumed),new HashMap<>(outcomes),new HashMap<>(accounts),new HashMap<>(fences),new HashMap<>(cleanup),new ArrayList<>(audit),global,new HashMap<>(deferred));}
  void restore(State s){slots=s.s;receipts=s.r;evidence=s.e;consumed=s.c;outcomes=s.o;accounts=s.a;fences=s.f;cleanup=s.w;audit=s.u;global=s.g;deferred=s.deferred;}
  public Accounts lockAccounts(String tenant){return new Accounts(global,accounts.getOrDefault(new Ref(tenant,new UUID(0,0)),ResourceAccount.empty()));}
  public void saveAccounts(String tenant,Accounts before,Accounts after){if(!before.equals(lockAccounts(tenant))||after.global().revision()!=before.global().revision()+1||after.tenant().revision()!=before.tenant().revision()+1)throw new IllegalStateException("Account CAS");global=after.global();accounts.put(new Ref(tenant,new UUID(0,0)),after.tenant());}
  public Optional<UploadSlot> slot(String tenant,UUID id){return Optional.ofNullable(slots.get(new Ref(tenant,id)));}
  public void insertSlot(UploadSlot slot){if(slots.putIfAbsent(new Ref(slot.tenant(),slot.uploadId()),slot)!=null)throw new IllegalStateException();}
  public void updateSlot(UploadSlot slot,long expected){var key=new Ref(slot.tenant(),slot.uploadId());if(slots.get(key).revision()!=expected||slot.revision()!=expected+1)throw new IllegalStateException("Slot CAS");slots.put(key,slot);}
  public Optional<UploadOutcome> command(CommandKey key){return Optional.ofNullable(outcomes.get(key));}
  public void record(UploadOutcome outcome,UploadAudit fact){if(failRecord)throw new IllegalStateException();if(outcomes.putIfAbsent(outcome.key(),outcome)!=null)throw new IllegalStateException();audit.add(fact);}
  public void audit(UploadAudit fact){audit.add(fact);}
  public void insertEvidence(VerificationEvidence value){if(evidence.putIfAbsent(new Ref(value.tenant(),value.evidenceId()),value)!=null)throw new IllegalStateException();}
  public Optional<VerificationEvidence> evidence(String tenant,UUID id){return Optional.ofNullable(evidence.get(new Ref(tenant,id)));}
  public void insertReceipt(UploadReceipt value){if(receipts.putIfAbsent(new Ref(value.tenant(),value.receiptId()),value)!=null)throw new IllegalStateException();}
  public Optional<UploadReceipt> receipt(String tenant,UUID id){return Optional.ofNullable(receipts.get(new Ref(tenant,id)));}
  public Optional<UploadReceipt> receiptForUpload(String tenant,UUID id){return receipts.values().stream().filter(v->v.tenant().equals(tenant)&&v.uploadId().equals(id)).findFirst();}
  public Optional<ReceiptConsumption> consumption(String tenant,UUID id){return Optional.ofNullable(consumed.get(new Ref(tenant,id)));}
  public void consume(ReceiptConsumption value){if(consumed.putIfAbsent(new Ref(value.tenant(),value.receiptId()),value)!=null)throw new DmFailure(409,"RECEIPT_CONSUMED");}
  public Optional<DeletionFence> unresolvedFence(String tenant,UUID document){return Optional.ofNullable(fences.get(new Ref(tenant,document)));}
  public void insertFence(DeletionFence value){if(fences.putIfAbsent(new Ref(value.tenant(),value.documentId()),value)!=null)throw UploadPolicy.unavailable();}
  public void dispatchFence(DeletionFence expected,DeletionFence dispatched){if(failDispatch)throw new IllegalStateException("Simulated dispatch crash");var key=new Ref(expected.tenant(),expected.documentId());if(!expected.equals(fences.get(key)))throw new IllegalStateException();fences.put(key,dispatched);}
  public void settleFence(DeletionFence expected,DeletionFence terminal,String requestId,Instant at){if(failSettle)throw new IllegalStateException("Simulated settlement crash");var key=new Ref(expected.tenant(),expected.documentId());if(!expected.equals(fences.get(key)))throw new IllegalStateException();fences.remove(key);}
  public void enqueueCleanup(CleanupWork value){if(cleanup.values().stream().noneMatch(c->c.tenant().equals(value.tenant())&&c.uploadId().equals(value.uploadId())&&c.key().equals(value.key())&&Objects.equals(c.object(),value.object())))cleanup.put(new Ref(value.tenant(),value.workId()),value);}
  public Optional<CleanupWork> cleanup(String tenant,UUID id){return Optional.ofNullable(cleanup.get(new Ref(tenant,id)));}
  public List<UploadSlot> expirable(Instant at,int limit){return slots.values().stream().filter(s->s.expired(at)&&s.state()!=UploadState.CONSUMED&&s.state()!=UploadState.REJECTED&&s.state()!=UploadState.EXPIRED).limit(limit).toList();}
  public List<CleanupWork> dueCleanup(Instant at,int limit){return cleanup.values().stream().filter(c->!c.complete()&&!deferred.getOrDefault(new Ref(c.tenant(),c.workId()),Instant.MIN).isAfter(at)).limit(limit).toList();}
  public void deferCleanup(CleanupWork expected,Instant next){if(cleanup(expected.tenant(),expected.workId()).filter(w->!w.complete()).isPresent())deferred.put(new Ref(expected.tenant(),expected.workId()),next);}
  public List<CleanupWork> pendingCleanup(String tenant,UUID upload){return cleanup.values().stream().filter(c->c.tenant().equals(tenant)&&c.uploadId().equals(upload)&&!c.complete()).toList();}
  public void completeCleanup(CleanupWork value){cleanup.put(new Ref(value.tenant(),value.workId()),value.completed());}
 }
}
