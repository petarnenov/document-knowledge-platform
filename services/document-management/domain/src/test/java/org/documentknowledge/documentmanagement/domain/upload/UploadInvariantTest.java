package org.documentknowledge.documentmanagement.domain.upload;
import java.time.*;
import java.util.*;
import org.documentknowledge.documentmanagement.domain.Principal;
import org.junit.jupiter.api.Test;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.junit.jupiter.api.Assertions.*;

class UploadInvariantTest {
 final Instant at=Instant.parse("2026-10-31T20:00:00Z");final UUID id=UUID.randomUUID(),document=UUID.randomUUID();final Principal actor=new Principal("https://issuer.example","staff");final String hash="a".repeat(64);
 ObjectKey key(boolean original){return new ObjectKey(original?"dkp-originals":"dkp-quarantine",hash+"/"+document+"/"+id);}
 ObjectIdentity object(boolean original){return new ObjectIdentity(key(original),"immutable-version");}
 UploadSlot initialized(){return UploadSlot.initialize(id,"tenant",document,actor,at,0,UploadFormat.PDF,100);}
 UploadSlot quarantined(){return initialized().receiving(UUID.randomUUID(),at.plusSeconds(300),"body").quarantined(object(false),hash);}
 UploadSlot copying(){return quarantined().verifying(UUID.randomUUID(),at.plusSeconds(300)).possibleCopy(key(true));}
 UploadSlot copied(){return copying().copied(object(true));}
 ScanEvidence scan(){return new ScanEvidence(hash,100,"1.5.4","standard",at.minusSeconds(30),hash,at);}
 InspectionEvidence inspect(){return new InspectionEvidence(UploadFormat.PDF,"application/pdf",100,hash,1000,"1",hash,"sha256:"+hash,at);}
 @Test void immutableObjectsRejectMutableAliasesAndMalformedScope(){
  assertEquals(key(false),object(false).objectKey());assertThrows(IllegalArgumentException.class,()->new ObjectKey("public",key(false).key()));assertThrows(IllegalArgumentException.class,()->new ObjectKey("dkp-originals","../key"));assertThrows(IllegalArgumentException.class,()->new ObjectKey("dkp-originals",null));
  assertThrows(NullPointerException.class,()->new ObjectIdentity(null,"v"));assertThrows(IllegalArgumentException.class,()->new ObjectIdentity(key(true),"null"));assertThrows(IllegalArgumentException.class,()->new ObjectIdentity(key(true),""));assertThrows(IllegalArgumentException.class,()->hash("invalid"));assertThrows(IllegalArgumentException.class,()->hash(null));assertThrows(IllegalArgumentException.class,()->size(0));assertThrows(IllegalArgumentException.class,()->size(MAX_BYTES+1));assertEquals(MAX_BYTES,size(MAX_BYTES));
 }
 @Test void evidenceRequiresMatchingSourceAndClosedFormatPageProfile(){
  var evidence=new VerificationEvidence(id,"tenant",id,object(false),scan(),inspect(),at);assertEquals(inspect().sha256(),evidence.scan().sourceSha256());
  assertThrows(IllegalArgumentException.class,()->new VerificationEvidence(id,"tenant",id,object(false),new ScanEvidence("b".repeat(64),100,"1.5.4","db",at,hash,at),inspect(),at));
  assertThrows(IllegalArgumentException.class,()->new VerificationEvidence(id,"tenant",id,object(false),new ScanEvidence(hash,99,"1.5.4","db",at,hash,at),inspect(),at));
  for(Integer pages:Arrays.asList(null,0,1001))assertThrows(IllegalArgumentException.class,()->new InspectionEvidence(UploadFormat.PDF,"application/pdf",100,hash,pages,"1",hash,"sha256:"+hash,at));
  for(var format:List.of(UploadFormat.DOCX,UploadFormat.PPTX,UploadFormat.XLSX)){assertNotNull(new InspectionEvidence(format,format.mime(),100,hash,null,"1",hash,"sha256:"+hash,at));assertThrows(IllegalArgumentException.class,()->new InspectionEvidence(format,format.mime(),100,hash,1,"1",hash,"sha256:"+hash,at));assertNotNull(format.extension());}
  assertThrows(IllegalArgumentException.class,()->new InspectionEvidence(UploadFormat.PDF,"text/html",100,hash,1,"1",hash,"sha256:"+hash,at));assertThrows(IllegalArgumentException.class,()->new InspectionEvidence(UploadFormat.PDF,"application/pdf",100,hash,1,"2",hash,"sha256:"+hash,at));assertThrows(IllegalArgumentException.class,()->new InspectionEvidence(UploadFormat.PDF,"application/pdf",100,hash,1,"1",hash,"bad",at));
 }
 @Test void receiptIsImmutableUnexpiredOriginalAndConsumptionIsSeparate(){
  var receipt=new UploadReceipt(id,id,"tenant",document,actor,hash,100,UploadFormat.PDF,object(true),id,at,at.plusSeconds(3600));assertEquals("application/pdf",receipt.format().mime());
  assertThrows(IllegalArgumentException.class,()->new UploadReceipt(id,id,"tenant",document,actor,hash,100,UploadFormat.PDF,object(false),id,at,at.plusSeconds(3600)));
  assertThrows(IllegalArgumentException.class,()->new UploadReceipt(id,id,"tenant",document,actor,hash,100,UploadFormat.PDF,object(true),id,at,at));
  var consumption=new ReceiptConsumption("tenant",id,document,UUID.randomUUID(),actor,"command",hash,at);assertEquals(id,consumption.receiptId());assertThrows(IllegalArgumentException.class,()->new ReceiptConsumption("tenant",id,document,id,actor,"",hash,at));
 }
 @Test void accountArithmeticCannotBecomeNegativeOrOverflow(){
  var empty=ResourceAccount.empty();assertEquals(0,empty.admittedCount());var charged=empty.change(1,200,100,0);assertEquals(1,charged.revision());assertEquals(100,charged.promisedBytes());assertNotNull(new Accounts(empty,charged));
  for(long[] values:List.of(new long[]{-1,0,0,0,0},new long[]{0,-1,0,0,0},new long[]{0,0,-1,0,0},new long[]{0,0,0,-1,0},new long[]{0,0,0,0,-1}))assertThrows(IllegalArgumentException.class,()->new ResourceAccount(values[0],values[1],values[2],values[3],values[4]));
  assertThrows(ArithmeticException.class,()->new ResourceAccount(Long.MAX_VALUE,0,0,0,0).change(1,0,0,0));assertThrows(NullPointerException.class,()->new Accounts(null,empty));
 }
 @Test void fenceOnlyDispatchesOnceAndCleanupCannotChangeObject(){
  var fence=new DeletionFence(id,"tenant",document,id,object(false),id,at,FencePhase.AUTHORIZED);assertEquals(FencePhase.DISPATCHED,fence.phase(FencePhase.DISPATCHED).phase());assertThrows(IllegalStateException.class,()->fence.phase(FencePhase.SETTLED_SUCCESS));assertThrows(IllegalStateException.class,()->fence.phase(FencePhase.DISPATCHED).phase(FencePhase.DISPATCHED));
  var work=new CleanupWork(id,"tenant",id,document,key(false),object(false),CleanupReason.EXPIRED,at,false);assertTrue(work.completed().complete());assertNotNull(new CleanupWork(id,"tenant",id,document,key(false),null,CleanupReason.EXPIRED,at,false));assertThrows(IllegalArgumentException.class,()->new CleanupWork(id,"tenant",id,document,key(false),object(true),CleanupReason.EXPIRED,at,false));
 }
 @Test void slotTransitionsPreserveWriteOnceBindingAndPhysicalCharges(){
  var initial=initialized();assertFalse(initial.expired(at));assertTrue(initial.expired(at.plusSeconds(3600)));assertThrows(IllegalStateException.class,initial::verified);
  var q=quarantined();assertEquals(UploadState.QUARANTINED,q.state());assertThrows(IllegalStateException.class,()->q.receiving(id,at,"x"));
  var noCopy=q.verifying(id,at.plusSeconds(300)).transientFailure();assertEquals(UploadState.QUARANTINED,noCopy.state());assertNull(noCopy.leaseId());
  var unknown=copying().transientFailure();assertEquals(UploadState.VERIFYING,unknown.state());assertFalse(unknown.writerSettled());assertThrows(IllegalStateException.class,()->unknown.verifying(id,at));
  var written=copied();assertTrue(written.writerSettled());assertThrows(IllegalStateException.class,()->copying().copied(object(false)));var retry=written.cleanupSucceeded(true,200,false);assertEquals(UploadState.QUARANTINED,retry.state());assertTrue(retry.copyCleanupConfirmed());assertEquals(written.revision()+1,retry.revision());assertNotNull(retry.verifying(id,at.plusSeconds(300)));
  var verified=written.verified();var consumed=verified.consumed();assertEquals(100,consumed.transientReservation());assertFalse(consumed.quotaHeld());assertThrows(IllegalStateException.class,()->consumed.terminal(UploadState.EXPIRED,"UPLOAD_EXPIRED"));
  var cleanSource=verified.cleaned(false);assertTrue(cleanSource.quarantineCleanupConfirmed());assertEquals(0,cleanSource.consumed().transientReservation());assertEquals(0,consumed.cleanupSucceeded(false,0,false).transientReservation());
  assertEquals(UploadState.REJECTED,q.terminal(UploadState.REJECTED,"INVALID_FILE").state());assertEquals(UploadState.EXPIRED,q.terminal(UploadState.EXPIRED,"UPLOAD_EXPIRED").state());assertThrows(IllegalStateException.class,()->q.terminal(UploadState.CONSUMED,"bad"));
  assertEquals(10,q.reservation(10).transientReservation());assertFalse(q.accounting(0,false).quotaHeld());assertTrue(copying().settledWriter().writerSettled());
 }
 @Test void invalidSlotCoordinatesAndLeaseBoundsReject(){
  assertThrows(IllegalArgumentException.class,()->UploadSlot.initialize(id,"tenant",document,actor,at,-1,UploadFormat.PDF,1));assertThrows(IllegalArgumentException.class,()->initialized().receiving(id,at.plusSeconds(3601),"x"));assertThrows(IllegalArgumentException.class,()->initialized().reservation(-1));assertThrows(IllegalArgumentException.class,()->initialized().reservation(201));
 }
 @Test void constructorEnforcesIndependentAttemptRevisionAndLeaseCoordinates(){
  var base=initialized();
  for(int[] pair:List.of(new int[]{-1,0},new int[]{4,0},new int[]{0,-1},new int[]{0,4}))assertThrows(IllegalArgumentException.class,()->coordinate(base,0,pair[0],pair[1],null,null,null,null));
  assertThrows(IllegalArgumentException.class,()->coordinate(base,-1,0,0,null,null,null,null));assertThrows(IllegalArgumentException.class,()->coordinate(base,0,0,0,id,null,null,null));assertThrows(IllegalArgumentException.class,()->coordinate(base,0,0,0,null,at,null,null));assertThrows(IllegalArgumentException.class,()->coordinate(base,0,0,0,null,null,null,object(true)));
  assertThrows(IllegalArgumentException.class,()->coordinate(base,0,0,0,null,null,key(false),object(true)));assertThrows(IllegalStateException.class,()->coordinate(base,0,3,0,null,null,null,null).receiving(id,at,"body"));assertThrows(IllegalStateException.class,()->coordinate(quarantined(),2,0,3,null,null,null,null).verifying(id,at));
  assertThrows(IllegalArgumentException.class,()->new ObjectIdentity(key(true),"é".repeat(513)));assertEquals(1024,new ObjectIdentity(key(true),"é".repeat(512)).versionId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
 }
 UploadSlot coordinate(UploadSlot b,long revision,int transfers,int verifies,UUID lease,Instant until,ObjectKey possible,ObjectIdentity original){return new UploadSlot(b.uploadId(),b.tenant(),b.documentId(),b.actor(),b.createdAt(),b.expiresAt(),b.expectedRevision(),b.format(),b.declaredSize(),b.state(),revision,b.quarantine(),b.bodySha256(),b.transferKey(),transfers,verifies,lease,until,possible,original,b.writerSettled(),b.copyCleanupConfirmed(),b.failureCode(),b.quotaHeld(),b.quarantineCleanupConfirmed(),b.transientReservation());}

 @Test void knownNoWriteAndConfirmedSourceCleanupResetOnlyReceivingAttempt(){
  var receiving=initialized().receiving(id,at.plusSeconds(300),"body");var reset=receiving.transferReset();assertEquals(UploadState.INITIALIZED,reset.state());assertEquals(1,reset.transferAttempts());assertNull(reset.leaseId());
  assertFalse(receiving.transferPending(false).writerSettled());var known=receiving.transferPending(true);assertTrue(known.writerSettled());var cleaned=known.cleanupSucceeded(false,200,false);assertEquals(UploadState.INITIALIZED,cleaned.state());assertFalse(cleaned.quarantineCleanupConfirmed());assertEquals(known.revision()+1,cleaned.revision());
  assertThrows(IllegalStateException.class,()->initialized().transferReset());assertThrows(IllegalStateException.class,()->quarantined().transferPending(true));assertThrows(IllegalArgumentException.class,()->UploadSlot.initialize(id,"tenant",document,actor,at,9_007_199_254_740_992L,UploadFormat.PDF,1));assertThrows(IllegalArgumentException.class,()->coordinate(initialized(),9_007_199_254_740_992L,0,0,null,null,null,null));
 }

}
