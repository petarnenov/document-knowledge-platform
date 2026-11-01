package org.documentknowledge.documentmanagement.application;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.domain.upload.*;
import org.documentknowledge.identity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real ContentService/private-file admission with explicitly seeded UNIT evidence, not provider acceptance. */
class ContentCapacityTest {
    @TempDir Path temporary;
    private final UploadMemory store=new UploadMemory();
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
    private final Actor first=new Actor("https://issuer.example","first"),second=new Actor("https://issuer.example","second"),third=new Actor("https://issuer.example","third");
    private final Set<Actor> denied=new HashSet<>();
    private final AtomicInteger acquisitions=new AtomicInteger(),sourceCloses=new AtomicInteger();
    private final Map<ObjectIdentity,Long> objects=new HashMap<>();
    private boolean corrupt,failRead;
    private Runnable sourceClosed=()->{},sourceDrained=()->{};
    private final UploadStoragePort storage=new UploadStoragePort(){
        public InputStream open(ObjectIdentity object){
            Long size=objects.get(object);assertNotNull(size,"Only the exact immutable fixture identity is available");acquisitions.incrementAndGet();
            return new InputStream(){long left=size;boolean changed,closed;
                public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:Byte.toUnsignedInt(b[0]);}
                public int read(byte[] b,int offset,int count)throws IOException{if(failRead)throw new IOException("Synthetic source failure");if(count==0)return 0;if(left==0){sourceDrained.run();return -1;}int n=(int)Math.min(left,count);Arrays.fill(b,offset,offset+n,(byte)0);if(corrupt&&!changed){b[offset]=1;changed=true;}left-=n;return n;}
                public void close(){if(!closed){closed=true;sourceCloses.incrementAndGet();sourceClosed.run();}}
            };
        }
        public ObjectIdentity put(ObjectKey k,Path p,long n,String hash){throw new AssertionError("Content must not write object storage");}
        public ObjectIdentity copy(ObjectIdentity s,ObjectKey t){throw new AssertionError("Content must not copy objects");}
        public List<ObjectIdentity> versions(ObjectKey k){throw new AssertionError("Content must not enumerate mutable object identities");}
        public DeletionResult deleteOnce(DeletionFence f){throw new AssertionError("Content must not delete immutable storage");}
    };
    private ContentService service;
    private record Version(UUID document,UUID version,long size) { }
    @BeforeEach void setup()throws IOException{
        Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rwx------"));
        service=new ContentService(store,(actor,tenant,action,resource)->Set.of(first,second,third).contains(actor)&&tenant.equals("tenant-a")&&action.equals(Action.CONTENT_REVIEW.wire())&&!denied.contains(actor)?AuthorityDecision.ALLOW:AuthorityDecision.DENY,storage,clock,temporary);
    }
    private DocumentCaller caller(Actor actor){return new DocumentCaller(actor,"tenant-a",()->AuthorityDecision.ALLOW);}
    private static String zeroHash(long size)throws Exception{var digest=MessageDigest.getInstance("SHA-256");byte[] zeros=new byte[65536];for(long left=size;left>0;left-=Math.min(left,zeros.length))digest.update(zeros,0,(int)Math.min(left,zeros.length));return HexFormat.of().formatHex(digest.digest());}
    private Version fixture(long size)throws Exception{
        UUID doc=UUID.randomUUID(),version=UUID.randomUUID(),upload=UUID.randomUUID(),receipt=UUID.randomUUID(),evidence=UUID.randomUUID();String hash=zeroHash(size);Instant at=clock.instant();
        var key=new ObjectKey("dkp-originals","a".repeat(64)+"/"+doc+"/"+UUID.randomUUID());var original=new ObjectIdentity(key,"immutable-unit-version");objects.put(original,size);
        var source=new ObjectIdentity(new ObjectKey("dkp-quarantine","a".repeat(64)+"/"+doc+"/"+UUID.randomUUID()),"immutable-unit-quarantine");
        var scan=new ScanEvidence(hash,size,"1.5.4","explicit-unit-standard-fixture",at,"b".repeat(64),at);
        var inspect=new InspectionEvidence(UploadFormat.PDF,UploadFormat.PDF.mime(),size,hash,1,"1","c".repeat(64),"sha256:"+"d".repeat(64),at);
        store.uploads.insertEvidence(new VerificationEvidence(evidence,"tenant-a",upload,source,scan,inspect,at));
        store.uploads.insertReceipt(new UploadReceipt(receipt,upload,"tenant-a",doc,new Principal(first.issuer(),first.subject()),hash,size,UploadFormat.PDF,original,evidence,at,at.plusSeconds(3600)));
        store.uploads.consumed.put(new UploadMemory.Ref("tenant-a",receipt),new ReceiptConsumption("tenant-a",receipt,doc,version,new Principal(first.issuer(),first.subject()),"explicit-unit-consumption","e".repeat(64),at));
        Set<AclRule> rules=new HashSet<>();for(var actor:List.of(first,second,third))rules.add(new AclRule(new Principal(actor.issuer(),actor.subject()),Action.CONTENT_REVIEW,AclRule.Effect.ALLOW));
        store.docs.put(doc,new DocumentAggregate(doc,"tenant-a",new Principal(first.issuer(),first.subject()),at,1,new Metadata("Synthetic capacity", ""),null,rules,List.of(),List.of(new DocumentVersion(version,doc,1,hash,UploadFormat.PDF.mime(),receipt.toString(),"explicit-unit-fixture",new Principal(first.issuer(),first.subject()),at))));
        return new Version(doc,version,size);
    }
    private ContentService.ContentResponse open(Actor actor,Version version){return service.open(caller(actor),version.document(),version.version(),null);}
    private void denied(int status,Runnable work){DmFailure failure=assertThrows(DmFailure.class,work::run);assertEquals(status,failure.status());}
    @AfterEach void shutdown(){service.close();}
    private Path contentDirectory(){return temporary.resolve("content-gateway");}
    private long privateBytes()throws IOException{if(!Files.exists(contentDirectory()))return 0;try(var paths=Files.list(contentDirectory())){long total=0;for(Path p:paths.toList()){assertTrue(Files.isRegularFile(p));assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(p));if(p.toString().endsWith(".bytes"))total=Math.addExact(total,Files.size(p));}return total;}}
    @Test void twoDistinctActorsOwnExactlyTwoHundredMillionBytesAndThirdCannotAcquireOneMore()throws Exception{
        var maximum=fixture(100_000_000);var one=fixture(1);
        try(var a=open(first,maximum);var b=open(second,maximum)){
            assertEquals(200_000_000,privateBytes());assertEquals(2,acquisitions.get());assertEquals(2,sourceCloses.get());
            denied(429,()->open(third,one));assertEquals(2,acquisitions.get());assertEquals(200_000_000,privateBytes());
            a.close();assertEquals(100_000_000,privateBytes());
            try(var c=open(third,maximum)){assertEquals(3,acquisitions.get());assertEquals(200_000_000,privateBytes());}
            assertEquals(100_000_000,privateBytes());
        }
        assertEquals(0,privateBytes());try(var recovered=open(third,one);var bytes=recovered.openStream()){assertEquals(0,bytes.read());assertEquals(-1,bytes.read());}assertEquals(0,privateBytes());
    }
    @Test void sameActorIsRejectedWithoutSourceAndDisconnectedStreamReturnsExactCapacity()throws Exception{
        var version=fixture(262_144);try(var firstResponse=open(first,version)){
            denied(429,()->open(first,version));assertEquals(1,acquisitions.get());
            try(var secondResponse=open(second,version);var stream=secondResponse.openStream()){
                byte[] chunk=new byte[100_000];assertEquals(65536,stream.read(chunk));assertEquals(2*version.size(),privateBytes());stream.close();assertEquals(version.size(),privateBytes());
                try(var replacement=open(third,version)){assertEquals(3,acquisitions.get());assertEquals(2*version.size(),privateBytes());}
            }
        }
        assertEquals(0,privateBytes());
    }
    @Test void hashMismatchSourceFailureAndFinalAuthorityWithholdingDoNotLeakFilesOrPermits()throws Exception{
        var version=fixture(131_072);corrupt=true;denied(503,()->open(first,version));assertEquals(0,privateBytes());corrupt=false;
        failRead=true;denied(503,()->open(first,version));assertEquals(0,privateBytes());failRead=false;
        sourceDrained=()->denied.add(first);denied(404,()->open(first,version));assertEquals(0,privateBytes());sourceDrained=()->{};denied.clear();
        try(var a=open(first,version);var b=open(second,version)){assertEquals(2*version.size(),privateBytes());denied(429,()->open(third,version));}
        assertEquals(0,privateBytes());
        try(var response=open(first,version)){denied.add(first);denied(404,response::openStream);assertEquals(0,privateBytes());}denied.clear();
        try(var a=open(first,version);var b=open(second,version)){assertEquals(2*version.size(),privateBytes());}assertEquals(0,privateBytes());
    }
    @Test void responseCloseClosesOpenInodeBeforeAdmissionAndShutdownReleasesExclusiveOwner()throws Exception{
        var version=fixture(131_072);var response=open(first,version);var stream=response.openStream();assertEquals(0,stream.read());response.close();assertThrows(IOException.class,stream::read);assertEquals(0,privateBytes());
        var secondResponse=open(second,version);var secondStream=secondResponse.openStream();service.close();assertThrows(IOException.class,secondStream::read);assertEquals(0,privateBytes());denied(503,()->open(third,version));
        service=new ContentService(store,(a,t,x,r)->AuthorityDecision.ALLOW,storage,clock,temporary);try(var replacement=open(third,version)){assertEquals(version.size(),privateBytes());}
    }
    @Test void failedOpenCleanupRemainsChargedUntilConfirmedRecovery()throws Exception{
        var version=fixture(131_072);corrupt=true;sourceDrained=()->{try{Files.setPosixFilePermissions(contentDirectory(),PosixFilePermissions.fromString("r-x------"));}catch(IOException e){throw new UncheckedIOException(e);}};
        try{denied(503,()->open(first,version));assertEquals(version.size(),privateBytes());}finally{Files.setPosixFilePermissions(contentDirectory(),PosixFilePermissions.fromString("rwx------"));}
        corrupt=false;sourceDrained=()->{};try(var recovered=open(first,version)){assertEquals(version.size(),privateBytes());}assertEquals(0,privateBytes());
    }
    @Test void immutableReceiptConsumptionAndEvidenceMismatchNeverAcquireSource()throws Exception{
        for(String mismatch:List.of("receipt-tenant","receipt-document","consumed-document","consumed-version","version-hash","version-mime","evidence-upload","evidence-hash","evidence-size")){
            var version=fixture(16);var doc=store.docs.get(version.document());var found=doc.versions().getFirst();UUID id=UUID.fromString(found.originalArtifactRef());var key=new UploadMemory.Ref("tenant-a",id);var receipt=store.uploads.receipts.get(key);var consumed=store.uploads.consumed.get(key);var evidenceKey=new UploadMemory.Ref("tenant-a",receipt.evidenceId());var evidence=store.uploads.evidence.get(evidenceKey);
            if(mismatch.startsWith("receipt-"))store.uploads.receipts.put(key,new UploadReceipt(receipt.receiptId(),receipt.uploadId(),mismatch.equals("receipt-tenant")?"other-tenant":receipt.tenant(),mismatch.equals("receipt-document")?UUID.randomUUID():receipt.documentId(),receipt.actor(),receipt.sha256(),receipt.sizeBytes(),receipt.format(),receipt.original(),receipt.evidenceId(),receipt.createdAt(),receipt.expiresAt()));
            if(mismatch.startsWith("consumed-"))store.uploads.consumed.put(key,new ReceiptConsumption(consumed.tenant(),consumed.receiptId(),mismatch.equals("consumed-document")?UUID.randomUUID():consumed.documentId(),mismatch.equals("consumed-version")?UUID.randomUUID():consumed.versionId(),consumed.actor(),consumed.commandKey(),consumed.fingerprint(),consumed.consumedAt()));
            if(mismatch.startsWith("version-")){var changed=new DocumentVersion(found.versionId(),found.documentId(),1,mismatch.equals("version-hash")?"f".repeat(64):found.sha256(),mismatch.equals("version-mime")?UploadFormat.DOCX.mime():found.mimeType(),found.originalArtifactRef(),found.producer(),found.creator(),found.createdAt());store.docs.put(doc.documentId(),new DocumentAggregate(doc.documentId(),doc.tenant(),doc.creator(),doc.createdAt(),doc.revision(),doc.metadata(),doc.activeVersionId(),doc.rules(),doc.holds(),List.of(changed)));}
            if(mismatch.startsWith("evidence-")){var i=evidence.inspection();var changed=new InspectionEvidence(i.format(),i.mimeType(),mismatch.equals("evidence-size")?17:i.sizeBytes(),mismatch.equals("evidence-hash")?"f".repeat(64):i.sha256(),i.pageCount(),i.inspectorVersion(),i.profileDigest(),i.imageDigest(),i.inspectedAt());store.uploads.evidence.put(evidenceKey,new VerificationEvidence(evidence.evidenceId(),evidence.tenant(),mismatch.equals("evidence-upload")?UUID.randomUUID():evidence.uploadId(),evidence.source(),new ScanEvidence(changed.sha256(),changed.sizeBytes(),evidence.scan().engineVersion(),evidence.scan().databaseVersion(),evidence.scan().databaseUpdatedAt(),evidence.scan().databaseDigest(),evidence.scan().scannedAt()),changed,evidence.verifiedAt()));}
            denied(mismatch.startsWith("evidence-")?503:404,()->open(first,version));assertEquals(0,acquisitions.get());assertEquals(0,privateBytes());
        }
    }
    @Test void failedDeletionMustRetainItsReservationAndRecoverOnlyAfterOwnedBytesAreDeleted()throws Exception{
        var maximum=fixture(100_000_000);var a=open(first,maximum);var b=open(second,maximum);
        try{
            assertEquals(200_000_000,privateBytes());Files.setPosixFilePermissions(contentDirectory(),PosixFilePermissions.fromString("r-x------"));
            assertFalse(Files.isWritable(contentDirectory()),"This physical-deletion test requires the real unprivileged filesystem owner");
            denied(503,a::close);assertEquals(200_000_000,privateBytes(),"A failed unlink leaves both real100MB files charged");
            // Re-enable creation only AFTER the failed deletion; the residual100MB is still present.
            Files.setPosixFilePermissions(contentDirectory(),PosixFilePermissions.fromString("rwx------"));
            // Current reconciliation may safely delete the owned residual before admitting replacement.
            try(var c=open(third,maximum)){
                assertEquals(200_000_000,privateBytes(),"Retained file plus released permit must never admit300000000 private bytes");
            }
            assertEquals(100_000_000,privateBytes());
        }finally{
            Files.setPosixFilePermissions(contentDirectory(),PosixFilePermissions.fromString("rwx------"));a.close();b.close();
        }
        assertEquals(0,privateBytes());
    }

}
