package org.documentknowledge.documentmanagement.application;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.domain.upload.*;
import org.documentknowledge.identity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.junit.jupiter.api.Assertions.*;

class UploadLifecycleTest {
 @TempDir Path temporary;
 final UploadMemory store=new UploadMemory();final TestClock clock=new TestClock();final Actor actor=new Actor("https://issuer.example","staff");
 final DocumentCaller caller=new DocumentCaller(actor,"tenant-a",()->AuthorityDecision.ALLOW);final EnumSet<Action> allowed=EnumSet.allOf(Action.class);
 final BytesStorage storage=new BytesStorage();AuthorityDecision override;Runnable grantHook=()->{};
 final ResourceAuthorizationPort grants=(a,t,x,r)->{grantHook.run();return override!=null?override:allowed.contains(Action.parse(x))?AuthorityDecision.ALLOW:AuthorityDecision.DENY;};
 MalwareScanPort scanner=source->new ScanEvidence(source.sha256(),source.sizeBytes(),"1.5.4","standard-main+daily",clock.instant().minusSeconds(30),"d".repeat(64),clock.instant());
 StructureInspectionPort inspector=source->new InspectionEvidence(source.format(),source.format().mime(),source.sizeBytes(),source.sha256(),source.format()==UploadFormat.PDF?1:null,"1","f".repeat(64),"sha256:"+"a".repeat(64),clock.instant());
 UUID document;
 DocumentService documents(){return new DocumentService(store,grants,new DelegationPolicyPort(){public AuthorityDecision targetGrant(Actor a,String t,Action x){return AuthorityDecision.ALLOW;}public AuthorityDecision ceiling(Actor a,String t,Action x){return AuthorityDecision.ALLOW;}},new ReceiptArtifactAuthority(store,storage,clock,temporary),new DocumentCursorPort(){public PagePosition decode(String c,Actor a,String t){throw new IllegalArgumentException();}public String encode(PagePosition p,Actor a,String t){return "cursor";}},clock);}
 UploadService uploads(){return new UploadService(store,grants,storage,scanner,inspector,clock,temporary);}
 ContentService content(){return new ContentService(store,grants,storage,clock,temporary);}
 UploadCleanupService cleanup(){return new UploadCleanupService(store,storage,clock);}
 @BeforeEach void create(){document=documents().create(caller,"create",new Metadata("Synthetic draft","")).documentId();}
 void failure(int status,String code,Runnable call){var failure=assertThrows(DmFailure.class,call::run);assertEquals(status,failure.status());assertEquals(code,failure.code());}
 byte[] bytes(){return "explicit synthetic UNIT fixture only".getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 UUID admitted(UploadService app,UploadFormat format){return app.initialize(caller,UUID.randomUUID().toString(),document,store.docs.get(document).revision(),format,bytes().length).uploadId();}
 UploadService.CompleteReceipt verified(UploadService app,UploadFormat format){UUID slot=admitted(app,format);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));return app.complete(caller,slot,"complete");}
 @Test void successfulLifecycleRecordsFiniteAuditCodesAndReplayDoesNotDuplicateThem(){
  var app=uploads();var receipt=verified(app,UploadFormat.PDF);
  assertEquals(List.of("upload.initialize","upload.transfer","upload.complete"),store.uploads.audit.stream().map(a->a.operation()).toList());
  assertTrue(store.uploads.audit.stream().allMatch(a->"OK".equals(a.code())));
  int before=store.uploads.audit.size();assertEquals(receipt,app.complete(caller,receipt.uploadId(),"complete"));
  assertEquals(before,store.uploads.audit.size());
 }
 @Test void everyFormatCreatesRealUnitEvidenceAndAtomicSingleUseVersionWithoutPublishing()throws Exception{
  int count=0;for(var format:UploadFormat.values()){
   var app=uploads();var complete=verified(app,format);assertEquals(0,store.docs.get(document).versions().size()-count);assertEquals(complete,app.complete(caller,complete.uploadId(),"complete"));
   UUID activeBefore=store.docs.get(document).activeVersionId();long revision=store.docs.get(document).revision();var appended=documents().appendVersion(caller,document,revision,"append"+count,complete.receiptId().toString());
   assertEquals(revision+1,appended.revision());assertEquals(appended,documents().appendVersion(caller,document,revision,"append"+count,complete.receiptId().toString()));
   var mapping=app.discover(caller,document,complete.receiptId());assertEquals(document,mapping.documentId());assertEquals(mapping.versionId(),store.docs.get(document).versions().getLast().versionId());assertEquals(activeBefore,store.docs.get(document).activeVersionId());if(count==0){var d=store.docs.get(document);store.docs.put(document,new DocumentAggregate(d.documentId(),d.tenant(),d.creator(),d.createdAt(),d.revision(),d.metadata(),mapping.versionId(),d.rules(),d.holds(),d.versions()));}
   try(var response=content().open(caller,document,mapping.versionId(),null);var stream=response.openStream()){assertEquals(200,response.status());assertEquals(format.mime(),response.mimeType());assertArrayEquals(bytes(),stream.readAllBytes());assertTrue(response.filename().endsWith("."+format.extension()));}
   var slot=store.uploads.slot(caller.tenant(),complete.uploadId()).orElseThrow();assertEquals(UploadState.CONSUMED,slot.state());assertEquals(bytes().length,slot.transientReservation());
   failure(409,"RECEIPT_CONSUMED",()->app.complete(caller,complete.uploadId(),"complete"));failure(409,"RECEIPT_CONSUMED",()->documents().appendVersion(caller,document,appended.revision(),"different",complete.receiptId().toString()));count++;
  }
  assertEquals(4,store.docs.get(document).versions().size());assertEquals(4,store.uploads.consumed.size());
 }
 @Test void admissionReplayQuotaConcurrencyAndBoundsAreDurable(){
  var app=uploads();UUID first=app.initialize(caller,"same",document,0,UploadFormat.PDF,bytes().length).uploadId();assertEquals(first,app.initialize(caller,"same",document,0,UploadFormat.PDF,bytes().length).uploadId());
  failure(409,"IDEMPOTENCY_CONFLICT",()->app.initialize(caller,"same",document,0,UploadFormat.DOCX,bytes().length));
  for(int i=0;i<4;i++)admitted(app,UploadFormat.PDF);failure(429,"QUOTA_EXCEEDED",()->admitted(app,UploadFormat.PDF));assertEquals(5,store.uploads.global.admittedCount());
  failure(413,"PAYLOAD_TOO_LARGE",()->app.initialize(caller,"large",document,0,UploadFormat.PDF,100_000_001));failure(400,"INVALID_REQUEST",()->app.initialize(caller,"empty",document,0,UploadFormat.PDF,0));failure(400,"INVALID_REQUEST",()->app.initialize(caller,"nullformat",document,0,null,1));
  var after=store.uploads.global;override=AuthorityDecision.DENY;failure(403,"OPERATION_DENIED",()->app.initialize(caller,"forbidden",UUID.randomUUID(),0,UploadFormat.PDF,1));assertEquals(after,store.uploads.global);
 }
 @Test void explicitRevocationMissingAuthorityAndOwnershipNeverRevealStatusOrDiscovery(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);var other=new DocumentCaller(new Actor("https://other.example",actor.subject()),caller.tenant(),()->AuthorityDecision.ALLOW);
  failure(404,"NOT_FOUND",()->app.status(other,slot));failure(404,"NOT_FOUND",()->app.status(new DocumentCaller(actor,"tenant-b",()->AuthorityDecision.ALLOW),slot));
  allowed.remove(Action.VERSION_APPEND);failure(404,"NOT_FOUND",()->app.status(caller,slot));failure(403,"OPERATION_DENIED",()->app.complete(caller,slot,"x"));
  override=AuthorityDecision.UNAVAILABLE;failure(503,"UNAVAILABLE",()->app.status(caller,UUID.randomUUID()));override=null;allowed.add(Action.VERSION_APPEND);
  failure(404,"NOT_FOUND",()->app.discover(caller,document,UUID.randomUUID()));allowed.remove(Action.CONTENT_REVIEW);failure(404,"NOT_FOUND",()->app.discover(caller,document,UUID.randomUUID()));
 }
 @Test void bodyHashReplaySizeMismatchAndTransferFailureKeepSafeState(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);failure(422,"SIZE_MISMATCH",()->app.transfer(caller,slot,"bad",new ByteArrayInputStream(new byte[1])));assertEquals(UploadState.INITIALIZED,app.status(caller,slot).state());
  app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));byte[] different=bytes();different[0]++;
  failure(409,"IDEMPOTENCY_CONFLICT",()->app.transfer(caller,slot,"body",new ByteArrayInputStream(different)));failure(409,"UPLOAD_STATE_CONFLICT",()->app.transfer(caller,slot,"other",new ByteArrayInputStream(bytes())));
  UUID another=admitted(app,UploadFormat.PDF);storage.failPut=true;failure(503,"UNAVAILABLE",()->app.transfer(caller,another,"body",new ByteArrayInputStream(bytes())));assertEquals(UploadState.RECEIVING,app.status(caller,another).state());assertFalse(store.uploads.pendingCleanup(caller.tenant(),another).isEmpty());assertEquals(2,store.uploads.global.admittedCount());
 }
 @Test void scannerFreshnessAndStructuralFailuresNeverIssueReceiptAndRetryIsBounded(){
  for(int mode=0;mode<5;mode++){
   int selected=mode;scanner=source->{if(selected==0)throw new DmFailure(422,"MALWARE_DETECTED");if(selected==1)return null;return new ScanEvidence(source.sha256(),source.sizeBytes(),selected==2?"wrong":"1.5.4","standard",clock.instant().minusSeconds(selected==3?86401:30),"d".repeat(64),clock.instant());};
   inspector=source->{throw new DmFailure(422,"UNSUPPORTED_CONTENT");};var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));
   failure(mode==0||mode==4?422:503,mode==0?"MALWARE_DETECTED":mode==4?"UNSUPPORTED_CONTENT":"UNAVAILABLE",()->app.complete(caller,slot,"complete"));assertTrue(store.uploads.receiptForUpload(caller.tenant(),slot).isEmpty());
   clock.advance(3601);cleanup().expire(caller.tenant(),slot);
  }
 }
 @Test void copyUnknownOrReadbackMismatchCannotCreateAnotherUnchargedCopy(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));storage.failCopy=true;
  failure(503,"UNAVAILABLE",()->app.complete(caller,slot,"complete"));int copies=storage.copyCalls;failure(503,"UNAVAILABLE",()->app.complete(caller,slot,"retry"));assertEquals(copies,storage.copyCalls);
  var current=store.uploads.slot(caller.tenant(),slot).orElseThrow();assertFalse(current.writerSettled());assertEquals(2*bytes().length,current.transientReservation());var work=store.uploads.pendingCleanup(caller.tenant(),slot).getFirst();failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertTrue(store.uploads.receipts.isEmpty());
 }
 @Test void finalAuthorityRevocationDuringCopySuppressesReceiptAndPersistsCleanup(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));storage.onCopy=()->allowed.remove(Action.VERSION_APPEND);
  failure(403,"OPERATION_DENIED",()->app.complete(caller,slot,"complete"));assertTrue(store.uploads.receipts.isEmpty());assertFalse(store.uploads.pendingCleanup(caller.tenant(),slot).isEmpty());assertEquals(2*bytes().length,store.uploads.global.transientBytes());
 }
 @Test void appendRollbackPreservesReceiptAndCurrentReplaySurvivesExpiry(){
  var app=uploads();var receipt=verified(app,UploadFormat.PDF);store.failRecord=true;assertThrows(IllegalStateException.class,()->documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString()));assertTrue(store.uploads.consumed.isEmpty());assertTrue(store.docs.get(document).versions().isEmpty());
  store.failRecord=false;var committed=documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString());clock.advance(3601);assertEquals(committed,documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString()));assertNotNull(app.discover(caller,document,receipt.receiptId()).versionId());
  allowed.remove(Action.VERSION_APPEND);failure(403,"OPERATION_DENIED",()->documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString()));
 }
 @Test void unknownReceiptWrongScopeAndExpiredUnconsumedRemainHidden(){
  failure(404,"NOT_FOUND",()->documents().appendVersion(caller,document,0,"x","not-a-receipt"));var app=uploads();var receipt=verified(app,UploadFormat.PDF);failure(404,"NOT_FOUND",()->app.discover(caller,document,receipt.receiptId()));
  clock.advance(3601);failure(409,"UPLOAD_EXPIRED",()->documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString()));cleanup().expire(caller.tenant(),receipt.uploadId());assertEquals(UploadState.EXPIRED,app.status(caller,receipt.uploadId()).state());
 }
 @Test void safeKnownCleanupBeforeOrAfterConsumptionReleasesExactCharges(){
  for(boolean before:List.of(true,false)){
   var app=uploads();var receipt=verified(app,UploadFormat.PDF);var work=store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()).getFirst();
   if(before)assertTrue(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));
   documents().appendVersion(caller,document,store.docs.get(document).revision(),UUID.randomUUID().toString(),receipt.receiptId().toString());
   if(!before)assertTrue(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));
   assertEquals(0,store.uploads.slot(caller.tenant(),receipt.uploadId()).orElseThrow().transientReservation());assertEquals(0,store.uploads.global.transientBytes());
  }
  assertEquals(2*bytes().length,store.uploads.global.retainedBytes());
 }
 @Test void timeoutFenceBlocksNewHoldAndRetryAcrossRestartWhileKnownSettlementRecovers(){
  var receipt=verified(uploads(),UploadFormat.PDF);var work=store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()).getFirst();storage.deleteUnknown=true;
  failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertTrue(store.uploads.unresolvedFence(caller.tenant(),document).isPresent());
  failure(503,"UNAVAILABLE",()->documents().placeHold(caller,document,0,"newhold","reason"));failure(503,"UNAVAILABLE",()->new UploadCleanupService(store,storage,clock).cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));
  storage.objects.remove(store.uploads.unresolvedFence(caller.tenant(),document).orElseThrow().object());failure(503,"UNAVAILABLE",()->documents().placeHold(caller,document,0,"after-delayed-delete","reason"));assertTrue(store.docs.get(document).holds().isEmpty());assertEquals(0,store.docs.get(document).revision());assertEquals(2*bytes().length,store.uploads.global.transientBytes());assertEquals(1,storage.deleteCalls);
 }
 @Test void holdFirstBlocksRemoteDeleteAndAuthorizedOriginalReplayPrecedesFence(){
  var receipt=verified(uploads(),UploadFormat.PDF);var held=documents().placeHold(caller,document,0,"hold","reason");var work=store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()).getFirst();failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertEquals(0,storage.deleteCalls);
  store.uploads.insertFence(new DeletionFence(UUID.randomUUID(),caller.tenant(),document,receipt.uploadId(),store.uploads.receipt(caller.tenant(),receipt.receiptId()).orElseThrow().original(),UUID.randomUUID(),clock.instant(),FencePhase.AUTHORIZED));
  assertEquals(held,documents().placeHold(caller,document,0,"hold","reason"));failure(503,"UNAVAILABLE",()->documents().placeHold(caller,document,1,"other","reason"));assertEquals(1,store.docs.get(document).holds().size());
 }
 @Test void pausedDeletionCannotRaceSuccessfulNewHold(){
  var receipt=verified(uploads(),UploadFormat.PDF);var work=store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()).getFirst();storage.onDelete=()->failure(503,"UNAVAILABLE",()->documents().placeHold(caller,document,0,"during","reason"));
  assertTrue(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertEquals(1,storage.deleteCalls);assertTrue(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertEquals(1,storage.deleteCalls);
  assertEquals(1,documents().placeHold(caller,document,0,"after","reason").revision());
 }
 @Test void initializedExpiryReleasesAdmissionWithoutInventingRemoteDeletion(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);cleanup().expire(caller.tenant(),slot);assertEquals(UploadState.INITIALIZED,app.status(caller,slot).state());clock.advance(3601);cleanup().expire(caller.tenant(),slot);cleanup().expire(caller.tenant(),slot);assertEquals(0,store.uploads.global.admittedCount());assertEquals(0,store.uploads.global.transientBytes());assertEquals(0,storage.deleteCalls);
 }
 @Test void malformedRangesAndFinalContentRevocationNeverExposeBytes()throws Exception{
  var app=uploads();var receipt=verified(app,UploadFormat.PDF);documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString());UUID version=app.discover(caller,document,receipt.receiptId()).versionId();var gateway=content();
  for(String range:List.of("bytes=1-3","bytes=2-","bytes=-3","bytes=-9999"))try(var response=gateway.open(caller,document,version,range);var body=response.openStream()){assertEquals(206,response.status());assertEquals(response.length(),body.readAllBytes().length);assertNotNull(response.contentRange());}
  for(String range:List.of("bytes=-0","bytes=99-","bytes=3-1","bytes=1-2,4-5","items=1-2","bytes=99999999999999999999999-"))failure(416,"RANGE_NOT_SATISFIABLE",()->gateway.open(caller,document,version,range));
  var response=gateway.open(caller,document,version,null);failure(429,"QUOTA_EXCEEDED",()->gateway.open(caller,document,version,null));allowed.remove(Action.CONTENT_REVIEW);failure(404,"NOT_FOUND",response::openStream);assertTrue(Files.list(temporary).findAny().isEmpty());
 }
 @Test void wrongSourceHashAndFailureOutcomesKeepFenceAndAccounting(){
  var receipt=verified(uploads(),UploadFormat.PDF);var work=store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()).getFirst();storage.deleteSuccess=false;assertFalse(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertTrue(store.uploads.unresolvedFence(caller.tenant(),document).isEmpty());assertEquals(2*bytes().length,store.uploads.global.transientBytes());
  storage.deleteSuccess=true;storage.badDeleteBinding=true;failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertTrue(store.uploads.unresolvedFence(caller.tenant(),document).isPresent());
 }

 void resetUploads(){store.uploads.restore(new UploadMemory().uploads.snapshot());storage.objects.clear();storage.failPut=false;storage.failCopy=false;storage.onCopy=()->{};storage.onDelete=()->{};storage.deletion=null;}
 @Test void allIndependentResourceBudgetsDenyAdmissionWithoutChangingCharges(){
  for(int mode=0;mode<6;mode++){resetUploads();var zero=ResourceAccount.empty();var tenant=zero;var global=zero;
   switch(mode){case 0->global=new ResourceAccount(10,0,0,0,0);case 1->tenant=new ResourceAccount(0,1_000_000_000L,0,0,0);case 2->global=new ResourceAccount(0,2_000_000_000L,0,0,0);case 3->tenant=new ResourceAccount(0,0,5_000_000_000L,0,0);case 4->global=new ResourceAccount(0,0,0,100_000_000_000L,0);case 5->tenant=new ResourceAccount(0,0,0,5_000_000_000L,0);}
   store.uploads.global=global;store.uploads.accounts.put(new UploadMemory.Ref(caller.tenant(),new UUID(0,0)),tenant);var before=store.uploads.lockAccounts(caller.tenant());failure(429,"QUOTA_EXCEEDED",()->admitted(uploads(),UploadFormat.PDF));assertEquals(before,store.uploads.lockAccounts(caller.tenant()));
  }
  resetUploads();failure(409,"REVISION_CONFLICT",()->uploads().initialize(caller,"stale",document,99,UploadFormat.PDF,1));failure(400,"INVALID_REQUEST",()->uploads().initialize(caller,"negative",document,-1,UploadFormat.PDF,1));
  for(String key:Arrays.asList(null,"","bad key"))failure(400,"INVALID_REQUEST",()->uploads().initialize(caller,key,document,0,UploadFormat.PDF,1));
 }
 @Test void scannerEvidenceMustMatchBytesEngineAndFreshServerTime(){
  for(int mode=0;mode<7;mode++){resetUploads();int selected=mode;
   scanner=source->new ScanEvidence(selected==0?"b".repeat(64):source.sha256(),source.sizeBytes()+(selected==1?1:0),selected==2?"wrong":"1.5.4","standard",clock.instant().plusSeconds(selected==3?1:selected==4?-86401:-30),"d".repeat(64),clock.instant().plusSeconds(selected==5?1:selected==6?-60:0));
   var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));failure(503,"UNAVAILABLE",()->app.complete(caller,slot,"complete"));assertTrue(store.uploads.receipts.isEmpty());assertEquals(UploadState.QUARANTINED,app.status(caller,slot).state());
  }
 }
 @Test void inspectorEvidenceCannotReplaceActualSourceOrClock(){
  for(int mode=0;mode<5;mode++){resetUploads();int selected=mode;inspector=source->{if(selected==0)return null;var format=selected==1?UploadFormat.DOCX:source.format();return new InspectionEvidence(format,format.mime(),source.sizeBytes()+(selected==2?1:0),selected==3?"b".repeat(64):source.sha256(),format==UploadFormat.PDF?1:null,"1","f".repeat(64),"sha256:"+"a".repeat(64),clock.instant().plusSeconds(selected==4?1:0));};
   var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));failure(503,"UNAVAILABLE",()->app.complete(caller,slot,"complete"));assertTrue(store.uploads.receipts.isEmpty());
  }
 }
 @Test void repeatedTransientVerificationHasThreeAttemptsAndFixedExpiry(){
  scanner=source->{throw new IllegalStateException("private adapter diagnostic");};var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));
  for(int i=0;i<3;i++){String key="try"+i;failure(503,"UNAVAILABLE",()->app.complete(caller,slot,key));}failure(409,"UPLOAD_STATE_CONFLICT",()->app.complete(caller,slot,"exhausted"));
  clock.advance(3599);var another=admitted(uploads(),UploadFormat.PDF); // deadline remains initializedAt+60min, never retry-extended
  clock.advance(2);failure(409,"UPLOAD_EXPIRED",()->app.complete(caller,slot,"expired"));assertEquals(UploadState.INITIALIZED,uploads().status(caller,another).state());
 }
 @Test void originalUploadWorksOneMicrosecondBeforeExpiryAndRejectsExactlyAtAndAfter(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);Instant expires=app.status(caller,slot).expiresAt();
  assertEquals(clock.instant().plusSeconds(3600),expires);
  clock.now=expires.minusNanos(1000);
  assertEquals(slot,app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes())).uploadId());
  var receipt=app.complete(caller,slot,"complete");
  assertEquals(receipt,app.complete(caller,slot,"complete"));
  assertEquals(expires,store.uploads.receipt(caller.tenant(),receipt.receiptId()).orElseThrow().expiresAt());
  var before=store.uploads.slot(caller.tenant(),slot).orElseThrow();var accounts=store.uploads.global;int outcomes=store.uploads.outcomes.size();
  for(Instant boundary:List.of(expires,expires.plusNanos(1000))){
   clock.now=boundary;
   failure(409,"UPLOAD_EXPIRED",()->app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes())));
   failure(409,"UPLOAD_EXPIRED",()->app.complete(caller,slot,"complete"));
   failure(409,"UPLOAD_EXPIRED",()->documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString()));
   assertEquals(before,store.uploads.slot(caller.tenant(),slot).orElseThrow());assertEquals(accounts,store.uploads.global);
   assertEquals(outcomes,store.uploads.outcomes.size());assertEquals(1,store.uploads.receipts.size());assertEquals(1,store.uploads.evidence.size());
   assertTrue(store.docs.get(document).versions().isEmpty());assertTrue(store.uploads.consumed.isEmpty());assertEquals(2,storage.objects.size());
  }
 }
 @Test void receiptConsumedImmediatelyBeforeExpiryKeepsOnlyOriginalReplayAndDiscoveryAtBoundary(){
  var app=uploads();var receipt=verified(app,UploadFormat.PDF);Instant expires=app.status(caller,receipt.uploadId()).expiresAt();
  clock.now=expires.minusNanos(1000);
  var committed=documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString());
  var mapping=app.discover(caller,document,receipt.receiptId());
  for(Instant boundary:List.of(expires,expires.plusNanos(1000))){
   clock.now=boundary;
   assertEquals(committed,documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString()));
   assertEquals(mapping,app.discover(caller,document,receipt.receiptId()));
   failure(409,"RECEIPT_CONSUMED",()->documents().appendVersion(caller,document,1,"different",receipt.receiptId().toString()));
   assertEquals(1,store.docs.get(document).versions().size());assertEquals(1,store.uploads.consumed.size());
  }
 }
 @Test void delayedTransientRetriesAndLateVerificationNeverExtendOriginalDeadline(){
  var available=scanner;scanner=source->{throw new IllegalStateException("Synthetic transient scanner outage");};
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);Instant expires=app.status(caller,slot).expiresAt();
  app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));
  for(int attempt=1;attempt<=2;attempt++){
   clock.advance(600);String key="retry"+attempt;
   failure(503,"UNAVAILABLE",()->app.complete(caller,slot,key));
   assertEquals(expires,app.status(caller,slot).expiresAt());assertEquals(UploadState.QUARANTINED,app.status(caller,slot).state());
   assertEquals(attempt,store.uploads.slot(caller.tenant(),slot).orElseThrow().verificationAttempts());
   assertTrue(store.uploads.receipts.isEmpty());assertEquals(2*bytes().length,store.uploads.global.transientBytes());
  }
  scanner=available;clock.now=expires.minusNanos(1000);var receipt=uploads().complete(caller,slot,"last-retry");
  assertEquals(expires,app.status(caller,slot).expiresAt());
  assertEquals(expires,store.uploads.receipt(caller.tenant(),receipt.receiptId()).orElseThrow().expiresAt());
  assertEquals(3,store.uploads.slot(caller.tenant(),slot).orElseThrow().verificationAttempts());
  clock.now=expires;
  failure(409,"UPLOAD_EXPIRED",()->app.complete(caller,slot,"last-retry"));
  failure(409,"UPLOAD_EXPIRED",()->documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString()));
 }
 @Test void expiredActiveHoldPreservesAllObjectsEvidenceAndExactChargesWithoutDeletion(){
  var app=uploads();var receipt=verified(app,UploadFormat.PDF);Instant expires=app.status(caller,receipt.uploadId()).expiresAt();
  documents().placeHold(caller,document,0,"hold","Synthetic preservation reason");
  var held=store.docs.get(document);var global=store.uploads.global;var tenantAccounts=new HashMap<>(store.uploads.accounts);
  var objects=new HashMap<ObjectIdentity,byte[]>();storage.objects.forEach((key,value)->objects.put(key,value.clone()));
  for(Instant boundary:List.of(expires,expires.plusSeconds(3600))){
   clock.now=boundary;cleanup().expire(caller.tenant(),receipt.uploadId());
   assertEquals(UploadState.EXPIRED,app.status(caller,receipt.uploadId()).state());
   for(var work:store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()))failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));
   assertEquals(held,store.docs.get(document));assertEquals(global,store.uploads.global);assertEquals(tenantAccounts,store.uploads.accounts);
   assertEquals(1,global.admittedCount());assertEquals(2*bytes().length,global.transientBytes());assertEquals(bytes().length,global.promisedBytes());assertEquals(0,global.retainedBytes());
   assertEquals(objects.keySet(),storage.objects.keySet());objects.forEach((key,value)->assertArrayEquals(value,storage.objects.get(key)));
   assertEquals(0,storage.deleteCalls);assertTrue(store.uploads.fences.isEmpty());assertEquals(1,store.uploads.receipts.size());assertEquals(1,store.uploads.evidence.size());
  }
 }
 @Test void expiredUnsettledDeleteKeepsFullChargesDespiteObservedAbsenceAndServiceRecreation(){
  var receipt=verified(uploads(),UploadFormat.PDF);clock.now=uploads().status(caller,receipt.uploadId()).expiresAt();cleanup().expire(caller.tenant(),receipt.uploadId());
  var work=store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()).getFirst();var accounts=store.uploads.global;
  storage.deletion=fence->{storage.objects.remove(fence.object());return new UploadStoragePort.DeletionResult(fence.attemptId(),fence.object(),"synthetic-lost-terminal-proof",false,false,true,false);};
  failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));
  var fence=store.uploads.unresolvedFence(caller.tenant(),document).orElseThrow();assertEquals(FencePhase.DISPATCHED,fence.phase());assertFalse(storage.objects.containsKey(fence.object()));
  clock.advance(3601);
  failure(503,"UNAVAILABLE",()->new UploadCleanupService(store,storage,clock).cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));
  failure(503,"UNAVAILABLE",()->documents().placeHold(caller,document,0,"latehold","Synthetic reason"));
  assertEquals(fence,store.uploads.unresolvedFence(caller.tenant(),document).orElseThrow());assertEquals(accounts,store.uploads.global);
  assertEquals(1,accounts.admittedCount());assertEquals(2*bytes().length,accounts.transientBytes());assertEquals(bytes().length,accounts.promisedBytes());
  assertEquals(1,storage.deleteCalls);assertFalse(store.uploads.cleanup(caller.tenant(),work.workId()).orElseThrow().complete());
 }
 @Test void expiryCleanupPreservesHistoricalEvidenceAndFreesAllCopiesOnlyAfterSuccess(){
  var receipt=verified(uploads(),UploadFormat.PDF);clock.advance(3601);var clean=cleanup();clean.expire(caller.tenant(),receipt.uploadId());
  var work=new ArrayList<>(store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()));assertEquals(2,work.size());assertEquals(2*bytes().length,store.uploads.global.transientBytes());
  assertTrue(clean.cleanup(caller.tenant(),work.getFirst().workId(),UUID.randomUUID()));
  assertEquals(1,store.uploads.global.admittedCount());assertEquals(bytes().length,store.uploads.global.transientBytes());assertEquals(bytes().length,store.uploads.global.promisedBytes());assertEquals(1,storage.objects.size());
  assertTrue(clean.cleanup(caller.tenant(),work.getLast().workId(),UUID.randomUUID()));assertTrue(storage.objects.isEmpty());assertEquals(2,storage.deleteCalls);
  assertEquals(0,store.uploads.global.admittedCount());assertEquals(0,store.uploads.global.transientBytes());assertEquals(0,store.uploads.global.promisedBytes());assertEquals(1,store.uploads.receipts.size());assertEquals(1,store.uploads.evidence.size());assertTrue(store.uploads.dueCleanup(clock.instant().plusSeconds(61),50).isEmpty());
 }
 @Test void knownSettledUnknownVersionRecoveryEnumeratesWithoutInferringWriterSettlement(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);clock.advance(3601);cleanup().expire(caller.tenant(),slot);assertEquals(0,store.uploads.global.transientBytes());
  resetUploads();var complete=verified(uploads(),UploadFormat.PDF);var original=store.uploads.receipt(caller.tenant(),complete.receiptId()).orElseThrow().original();var current=store.uploads.slot(caller.tenant(),complete.uploadId()).orElseThrow();
  // Explicit known-settled source locator models crash after terminal writer evidence but before locator recording.
  var sourceWork=store.uploads.pendingCleanup(caller.tenant(),current.uploadId()).getFirst();store.uploads.cleanup.clear();var locator=new CleanupWork(UUID.randomUUID(),caller.tenant(),current.uploadId(),document,sourceWork.key(),null,CleanupReason.VERIFIED_QUARANTINE_COPY,clock.instant(),false);store.uploads.enqueueCleanup(locator);
  assertFalse(cleanup().cleanup(caller.tenant(),locator.workId(),UUID.randomUUID()));var exact=store.uploads.pendingCleanup(caller.tenant(),current.uploadId()).getFirst();assertNotNull(exact.object());assertTrue(cleanup().cleanup(caller.tenant(),exact.workId(),UUID.randomUUID()));assertTrue(storage.objects.containsKey(original));
 }
 @Test void scheduledSweepDiscoversPersistedExpiryAndBlockedCleanup(){
  var app=uploads();UUID empty=admitted(app,UploadFormat.PDF);var receipt=verified(app,UploadFormat.PDF);clock.advance(3601);storage.deleteUnknown=true;
  var result=cleanup().sweep(UUID.randomUUID(),50);assertEquals(2,result.expired());assertTrue(result.blocked()>0);assertEquals(UploadState.EXPIRED,app.status(caller,empty).state());assertFalse(store.uploads.dueCleanup(clock.instant().plusSeconds(61),50).isEmpty());
  assertThrows(IllegalArgumentException.class,()->cleanup().sweep(UUID.randomUUID(),0));assertThrows(IllegalArgumentException.class,()->cleanup().sweep(UUID.randomUUID(),51));
 }
 @Test void storageCorruptionIsRejectedByPreflightAndContentBeforeAnyBytes()throws Exception{
  var app=uploads();var receipt=verified(app,UploadFormat.PDF);var authority=new ReceiptArtifactAuthority(store,storage,clock,temporary);
  failure(404,"NOT_FOUND",()->authority.verify(actor,caller.tenant(),document,"bad"));failure(404,"NOT_FOUND",()->authority.verify(new Actor("https://different.example",actor.subject()),caller.tenant(),document,receipt.receiptId().toString()));failure(404,"NOT_FOUND",()->authority.verify(new Actor(actor.issuer(),"other"),caller.tenant(),document,receipt.receiptId().toString()));failure(404,"NOT_FOUND",()->authority.verify(actor,caller.tenant(),UUID.randomUUID(),receipt.receiptId().toString()));
  var stored=store.uploads.receipt(caller.tenant(),receipt.receiptId()).orElseThrow();byte[] original=storage.objects.get(stored.original());byte[] corrupt=original.clone();corrupt[0]++;storage.objects.put(stored.original(),corrupt);failure(503,"UNAVAILABLE",()->authority.verify(actor,caller.tenant(),document,receipt.receiptId().toString()));storage.objects.put(stored.original(),original);
  documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString());UUID version=app.discover(caller,document,receipt.receiptId()).versionId();storage.objects.put(stored.original(),corrupt);failure(503,"UNAVAILABLE",()->content().open(caller,document,version,null));storage.objects.put(stored.original(),original);
  store.uploads.evidence.clear();failure(503,"UNAVAILABLE",()->content().open(caller,document,version,null));
 }
 @Test void contentStreamIsSingleUseBoundedAndClosesOnDeadline()throws Exception{
  var app=uploads();var receipt=verified(app,UploadFormat.PDF);documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString());UUID version=app.discover(caller,document,receipt.receiptId()).versionId();var gateway=content();
  try(var response=gateway.open(caller,document,version,null);var stream=response.openStream()){assertEquals(Byte.toUnsignedInt(bytes()[0]),stream.read());failure(503,"UNAVAILABLE",response::openStream);clock.advance(301);assertThrows(IOException.class,stream::read);}
  var closed=gateway.open(caller,document,version,null);closed.close();closed.close();failure(503,"UNAVAILABLE",closed::openStream);
  failure(416,"RANGE_NOT_SATISFIABLE",()->gateway.open(caller,document,version,"x".repeat(129)));
 }

 @Test void everyAmbiguousOrMismatchedDeletionProofRetainsFenceAndFullCharges(){
  for(int mode=0;mode<7;mode++){resetUploads();int selected=mode;var receipt=verified(uploads(),UploadFormat.PDF);var work=store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()).getFirst();
   storage.deletion=f->{if(selected==0)return null;return new UploadStoragePort.DeletionResult(f.attemptId(),selected==1?new ObjectIdentity(f.object().objectKey(),"wrong-version"):f.object(),selected==2?null:selected==3?" ":"request",selected!=4,selected!=5,selected!=6,true);};
   failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertTrue(store.uploads.unresolvedFence(caller.tenant(),document).isPresent());assertEquals(2*bytes().length,store.uploads.global.transientBytes());failure(503,"UNAVAILABLE",()->documents().placeHold(caller,document,0,"new","reason"));
  }
 }
 @Test void knownCopyMismatchRequiresCleanupBeforeRetryAndDoesNotConsumeExtraReservation(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));storage.corruptCopy=true;failure(503,"UNAVAILABLE",()->app.complete(caller,slot,"first"));
  var before=store.uploads.slot(caller.tenant(),slot).orElseThrow();assertTrue(before.writerSettled());failure(503,"UNAVAILABLE",()->app.complete(caller,slot,"too-early"));var work=store.uploads.pendingCleanup(caller.tenant(),slot).getFirst();assertTrue(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));
  assertEquals(UploadState.QUARANTINED,app.status(caller,slot).state());assertEquals(2*bytes().length,store.uploads.global.transientBytes());storage.corruptCopy=false;assertNotNull(app.complete(caller,slot,"retry").receiptId());assertEquals(2,storage.copyCalls);
 }
 @Test void quarantineOnlyExpiryReclaimsTwoCopyReservationAndKnownAbsenceRecovery(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));clock.advance(3601);cleanup().expire(caller.tenant(),slot);var pending=store.uploads.pendingCleanup(caller.tenant(),slot).getFirst();
  // Known terminal write plus authoritative exact-key empty enumeration, without dispatching a new delete.
  storage.objects.clear();store.uploads.cleanup.clear();var locator=new CleanupWork(UUID.randomUUID(),pending.tenant(),pending.uploadId(),pending.documentId(),pending.key(),null,CleanupReason.EXPIRED,clock.instant(),false);store.uploads.enqueueCleanup(locator);
  assertTrue(cleanup().cleanup(caller.tenant(),locator.workId(),UUID.randomUUID()));assertEquals(0,store.uploads.global.transientBytes());assertEquals(0,store.uploads.global.admittedCount());assertEquals(0,storage.deleteCalls);
 }
 @Test void retainedOriginalAndUnexpiredWrongCleanupReasonsCannotAuthorizeDelete(){
  var app=uploads();var receipt=verified(app,UploadFormat.PDF);var original=store.uploads.receipt(caller.tenant(),receipt.receiptId()).orElseThrow().original();
  var forbidden=new CleanupWork(UUID.randomUUID(),caller.tenant(),receipt.uploadId(),document,original.objectKey(),original,CleanupReason.EXPIRED,clock.instant(),false);store.uploads.enqueueCleanup(forbidden);failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),forbidden.workId(),UUID.randomUUID()));
  documents().appendVersion(caller,document,0,"append",receipt.receiptId().toString());failure(503,"UNAVAILABLE",()->cleanup().cleanup(caller.tenant(),forbidden.workId(),UUID.randomUUID()));assertEquals(0,storage.deleteCalls);
 }

 @Test void crashesBeforeDispatchAndBeforeSettlementNeverTransferDeletionAuthority(){
  for(boolean beforeDispatch:List.of(true,false)){resetUploads();var receipt=verified(uploads(),UploadFormat.PDF);var work=store.uploads.pendingCleanup(caller.tenant(),receipt.uploadId()).getFirst();store.uploads.failDispatch=beforeDispatch;store.uploads.failSettle=!beforeDispatch;
   assertThrows(IllegalStateException.class,()->cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));var fence=store.uploads.unresolvedFence(caller.tenant(),document).orElseThrow();assertEquals(beforeDispatch?FencePhase.AUTHORIZED:FencePhase.DISPATCHED,fence.phase());assertEquals(2*bytes().length,store.uploads.global.transientBytes());
   failure(503,"UNAVAILABLE",()->documents().placeHold(caller,document,0,"hold","reason"));int calls=storage.deleteCalls;failure(503,"UNAVAILABLE",()->new UploadCleanupService(store,storage,clock).cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertEquals(calls,storage.deleteCalls);store.uploads.failDispatch=false;store.uploads.failSettle=false;
  }
 }

 @Test void actualStreamingBoundaryAcceptsExactly100MBAndRejectsOneByteMoreWithoutMetadataCapConfusion(){
  var app=uploads();UUID exact=app.initialize(caller,"max",document,0,UploadFormat.PDF,MAX_BYTES).uploadId();storage.discardLargePut=true;
  assertEquals(exact,app.transfer(caller,exact,"body",repeated(MAX_BYTES)).uploadId());assertEquals(UploadState.QUARANTINED,app.status(caller,exact).state());
  UUID oversized=app.initialize(caller,"overflow",document,0,UploadFormat.PDF,MAX_BYTES).uploadId();failure(413,"PAYLOAD_TOO_LARGE",()->app.transfer(caller,oversized,"body",repeated(MAX_BYTES+1)));assertEquals(UploadState.INITIALIZED,app.status(caller,oversized).state());
 }
 InputStream repeated(long length){return new InputStream(){long remaining=length;public int read(){if(remaining==0)return -1;remaining--;return 120;}public int read(byte[] b,int offset,int count){if(remaining==0)return -1;int n=(int)Math.min(remaining,count);Arrays.fill(b,offset,offset+n,(byte)120);remaining-=n;return n;}};}

 @Test void receivingKnownNoWriteFailuresRetryOnlyThreeTimesUnderOriginalExpiry(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);
  for(int i=0;i<3;i++){String key="try"+i;failure(422,"SIZE_MISMATCH",()->app.transfer(caller,slot,key,new ByteArrayInputStream(new byte[1])));assertEquals(UploadState.INITIALIZED,app.status(caller,slot).state());}
  failure(409,"UPLOAD_STATE_CONFLICT",()->app.transfer(caller,slot,"fourth",new ByteArrayInputStream(bytes())));assertEquals(3,store.uploads.slot(caller.tenant(),slot).orElseThrow().transferAttempts());assertEquals(2*bytes().length,store.uploads.global.transientBytes());assertTrue(storage.objects.isEmpty());
 }
 @Test void receivingKnownStoredFailureResetsOnlyAfterFencedCleanupAndThenRetries(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);storage.onPut=()->allowed.remove(Action.VERSION_APPEND);failure(403,"OPERATION_DENIED",()->app.transfer(caller,slot,"first",new ByteArrayInputStream(bytes())));
  allowed.add(Action.VERSION_APPEND);assertEquals(UploadState.RECEIVING,app.status(caller,slot).state());failure(409,"UPLOAD_STATE_CONFLICT",()->app.transfer(caller,slot,"early",new ByteArrayInputStream(bytes())));
  var work=store.uploads.pendingCleanup(caller.tenant(),slot).getFirst();assertNotNull(work.object());assertTrue(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertEquals(UploadState.INITIALIZED,app.status(caller,slot).state());assertEquals(2*bytes().length,store.uploads.global.transientBytes());
  storage.onPut=()->{};assertNotNull(app.transfer(caller,slot,"retry",new ByteArrayInputStream(bytes())));assertEquals(UploadState.QUARANTINED,app.status(caller,slot).state());assertEquals(2,store.uploads.slot(caller.tenant(),slot).orElseThrow().transferAttempts());
 }
 @Test void lateTransferProviderResultCannotCommitAndKnownCopyRemainsChargedForCleanup(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);storage.onPut=()->clock.advance(300);
  failure(503,"UNAVAILABLE",()->app.transfer(caller,slot,"late",new ByteArrayInputStream(bytes())));
  var current=store.uploads.slot(caller.tenant(),slot).orElseThrow();assertEquals(UploadState.RECEIVING,current.state());assertTrue(current.writerSettled());assertTrue(store.uploads.outcomes.isEmpty()||store.uploads.outcomes.values().stream().noneMatch(o->o.key().operation().equals("upload.transfer")));
  assertEquals(2*bytes().length,store.uploads.global.transientBytes());var work=store.uploads.pendingCleanup(caller.tenant(),slot).getFirst();assertNotNull(work.object());assertTrue(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));assertEquals(UploadState.INITIALIZED,app.status(caller,slot).state());storage.onPut=()->{};assertNotNull(app.transfer(caller,slot,"fresh",new ByteArrayInputStream(bytes())));
 }
 @Test void lateVerificationCopySettlesOnlyItsExactOutcomeWithoutReceiptAndRecoversAfterFencedCleanup(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);app.transfer(caller,slot,"body",new ByteArrayInputStream(bytes()));storage.onCopy=()->{failure(409,"UPLOAD_STATE_CONFLICT",()->uploads().complete(caller,slot,"concurrent"));clock.advance(300);failure(503,"UNAVAILABLE",()->uploads().complete(caller,slot,"expired-concurrent"));};
  failure(503,"UNAVAILABLE",()->app.complete(caller,slot,"late"));var current=store.uploads.slot(caller.tenant(),slot).orElseThrow();assertTrue(current.writerSettled());assertNotNull(current.original());assertTrue(store.uploads.receipts.isEmpty());assertTrue(store.uploads.evidence.isEmpty());assertEquals(2*bytes().length,current.transientReservation());
  int copies=storage.copyCalls;failure(503,"UNAVAILABLE",()->app.complete(caller,slot,"blocked"));assertEquals(copies,storage.copyCalls);
  var work=store.uploads.pendingCleanup(caller.tenant(),slot).getFirst();assertTrue(cleanup().cleanup(caller.tenant(),work.workId(),UUID.randomUUID()));storage.onCopy=()->{};assertNotNull(app.complete(caller,slot,"fresh").receiptId());
 }
 @Test void unknownTransferWriterReturnsUnavailableWithoutRetryingOrFreeingCharges(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);storage.failPut=true;failure(503,"UNAVAILABLE",()->app.transfer(caller,slot,"unknown",new ByteArrayInputStream(bytes())));failure(503,"UNAVAILABLE",()->app.transfer(caller,slot,"retry",new ByteArrayInputStream(bytes())));assertEquals(2*bytes().length,store.uploads.global.transientBytes());assertFalse(store.uploads.slot(caller.tenant(),slot).orElseThrow().writerSettled());assertTrue(store.uploads.receipts.isEmpty());
 }
 @Test void noWriteFailureAfterLeaseExpiryCanSafelyResetUnderTheOriginalAdmission(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);var broken=new InputStream(){public int read()throws IOException{clock.advance(300);throw new IOException("Synthetic interrupted body");}};
  failure(503,"UNAVAILABLE",()->app.transfer(caller,slot,"interrupted",broken));assertEquals(UploadState.INITIALIZED,app.status(caller,slot).state());assertTrue(storage.objects.isEmpty());assertEquals(2*bytes().length,store.uploads.global.transientBytes());assertNotNull(app.transfer(caller,slot,"fresh",new ByteArrayInputStream(bytes())));
 }
 @Test void exactLeaseDeadlineIdentityIsRequiredEvenWhenLeaseIdAndStateMatch(){
  var app=uploads();UUID slot=admitted(app,UploadFormat.PDF);storage.onPut=()->{var before=store.uploads.slot(caller.tenant(),slot).orElseThrow();var changed=new UploadSlot(before.uploadId(),before.tenant(),before.documentId(),before.actor(),before.createdAt(),before.expiresAt(),before.expectedRevision(),before.format(),before.declaredSize(),before.state(),before.revision(),before.quarantine(),before.bodySha256(),before.transferKey(),before.transferAttempts(),before.verificationAttempts(),before.leaseId(),before.leaseUntil().minusSeconds(1),before.possibleOriginal(),before.original(),before.writerSettled(),before.copyCleanupConfirmed(),before.failureCode(),before.quotaHeld(),before.quarantineCleanupConfirmed(),before.transientReservation());store.uploads.slots.put(new UploadMemory.Ref(caller.tenant(),slot),changed);};
  failure(409,"UPLOAD_STATE_CONFLICT",()->app.transfer(caller,slot,"mismatched",new ByteArrayInputStream(bytes())));assertEquals(UploadState.RECEIVING,app.status(caller,slot).state());assertTrue(store.uploads.pendingCleanup(caller.tenant(),slot).isEmpty());assertEquals(2*bytes().length,store.uploads.global.transientBytes());
 }
 static class TestClock extends Clock {Instant now=Instant.parse("2026-10-31T20:00:00Z");void advance(long seconds){now=now.plusSeconds(seconds);}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 static class BytesStorage implements UploadStoragePort {
  final Map<ObjectIdentity,byte[]> objects=new HashMap<>();boolean failPut,failCopy,deleteUnknown,badDeleteBinding,discardLargePut;boolean deleteSuccess=true,corruptCopy;java.util.function.Function<DeletionFence,DeletionResult> deletion;int copyCalls,deleteCalls;Runnable onCopy=()->{},onDelete=()->{},onPut=()->{};
  public ObjectIdentity put(ObjectKey key,Path file,long size,String sha){if(failPut)throw new IllegalStateException();try{var id=new ObjectIdentity(key,UUID.randomUUID().toString());objects.put(id,discardLargePut&&size>1_000_000?new byte[0]:Files.readAllBytes(file));onPut.run();return id;}catch(IOException e){throw new IllegalStateException(e);}}
  public InputStream open(ObjectIdentity object){byte[] bytes=objects.get(object);if(bytes==null)throw new IllegalStateException();return new ByteArrayInputStream(bytes);}
  public ObjectIdentity copy(ObjectIdentity source,ObjectKey target){copyCalls++;if(failCopy)throw new IllegalStateException();var id=new ObjectIdentity(target,UUID.randomUUID().toString());byte[] copy=objects.get(source).clone();if(corruptCopy)copy[0]++;objects.put(id,copy);onCopy.run();return id;}
  public List<ObjectIdentity> versions(ObjectKey key){return objects.keySet().stream().filter(v->v.objectKey().equals(key)).toList();}
  public DeletionResult deleteOnce(DeletionFence fence){deleteCalls++;onDelete.run();if(deletion!=null)return deletion.apply(fence);if(deleteUnknown)throw new IllegalStateException();if(deleteSuccess)objects.remove(fence.object());return new DeletionResult(badDeleteBinding?UUID.randomUUID():fence.attemptId(),fence.object(),"request",true,true,deleteSuccess,deleteSuccess);}
 }
}
