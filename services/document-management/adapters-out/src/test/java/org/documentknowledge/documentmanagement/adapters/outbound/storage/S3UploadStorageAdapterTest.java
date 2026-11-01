package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.junit.jupiter.api.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.RealVerificationWorkspaceFixture;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.awscore.AwsResponseMetadata;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3UploadStorageAdapterTest {
    Path temporary;VerificationWorkspacePort workspaces;
    @BeforeEach void workspace()throws Exception{Path parent=Path.of("build/storage-workspaces").toAbsolutePath();Files.createDirectories(parent);Files.setPosixFilePermissions(parent,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));temporary=Files.createTempDirectory(parent,"owned-",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));Files.writeString(temporary.resolve(".spool-authority"),"dkp-private-spool-v1\n");Files.setPosixFilePermissions(temporary.resolve(".spool-authority"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));workspaces=RealVerificationWorkspaceFixture.open(temporary);}
    @AfterEach void closeWorkspace()throws Exception{workspaces.close();try(var paths=Files.walk(temporary)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}}
    private static final byte[] BYTES = "synthetic-storage-contract".getBytes(StandardCharsets.UTF_8);
    private static final String KEY = "a".repeat(64) + "/00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000002";
    private static final ObjectKey QUARANTINE = new ObjectKey("dkp-quarantine", KEY);
    private static final ObjectKey ORIGINAL = new ObjectKey("dkp-originals", KEY);
    private static final ObjectIdentity OBJECT = new ObjectIdentity(QUARANTINE, "exact-version");

    @Test void completeCopyPropagatesOneRemainingStorageBudgetAndAbortDoesNotCloseSharedClient()throws Exception {
        var budget=new org.documentknowledge.documentmanagement.application.upload.CompleteBudget(java.time.Clock.systemUTC());var calls=new AtomicInteger();
        var adapter=adapter((method,args)->{
            calls.incrementAndGet();var request=(software.amazon.awssdk.awscore.AwsRequest)args[0];var remaining=request.overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow();
            assertTrue(!remaining.isNegative()&&!remaining.isZero()&&remaining.compareTo(java.time.Duration.ofSeconds(60))<=0);
            if(method.equals("putObject"))return PutObjectResponse.builder().versionId("exact-version").build();assertEquals("getObject",method);return body(BYTES,"exact-version",BYTES.length);
        });
        try(var phase=budget.phase(org.documentknowledge.documentmanagement.application.upload.CompleteBudget.Kind.STORAGE)){
            assertEquals(new ObjectIdentity(ORIGINAL,"exact-version"),copy(adapter,OBJECT,ORIGINAL,phase));assertEquals(3,calls.get());
            try(var input=adapter.open(OBJECT,phase)){assertArrayEquals(BYTES,input.readAllBytes());budget.cancel();assertThrows(DmFailure.class,input::read);}
        }
        assertArrayEquals(BYTES,adapter((m,a)->body(BYTES,"exact-version",BYTES.length)).open(OBJECT).readAllBytes());
    }

    @Test void genericRoleBudgetsPreserveSixtySecondTransportCapExactReadbackAndPerInvocationAbort()throws Exception {
        for(var role:GenericTempPort.Role.values()){
            var budget=new GenericTempBudget(java.time.Clock.systemUTC(),role);var calls=new AtomicInteger();var abort=new java.util.concurrent.CountDownLatch(1);var closes=new AtomicInteger();
            var adapter=adapter((method,args)->{
                if(method.equals("close")){closes.incrementAndGet();return null;}
                calls.incrementAndGet();var request=(software.amazon.awssdk.awscore.AwsRequest)args[0];var remaining=request.overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow();assertTrue(remaining.compareTo(java.time.Duration.ofSeconds(60))<=0);assertTrue(remaining.compareTo(java.time.Duration.ZERO)>0);
                if(method.equals("putObject"))return PutObjectResponse.builder().versionId("exact-version").build();
                return new ResponseInputStream<>(GetObjectResponse.builder().versionId("exact-version").contentLength((long)BYTES.length).build(),AbortableInputStream.create(new ByteArrayInputStream(BYTES),abort::countDown));
            });
            assertEquals(OBJECT,adapter.put(QUARANTINE,source(BYTES),BYTES.length,hash(BYTES),budget));
            try(var input=adapter.open(OBJECT,budget)){assertEquals(BYTES[0],input.read());budget.cancel();assertTrue(abort.await(2,java.util.concurrent.TimeUnit.SECONDS));assertThrows(DmFailure.class,input::read);}
            assertEquals(3,calls.get());assertEquals(0,closes.get());
        }
    }
    @Test void revokedGenericBudgetCannotDispatchPutOrGetAndDefaultPortNeverFallsBack()throws Exception {
        var budget=new GenericTempBudget(java.time.Clock.systemUTC(),GenericTempPort.Role.TRANSFER);budget.cancel();var calls=new AtomicInteger();var adapter=adapter((method,args)->{calls.incrementAndGet();throw new AssertionError("No expired dispatch");});
        assertUnavailable(()->adapter.put(QUARANTINE,source(BYTES),BYTES.length,hash(BYTES),budget));assertUnavailable(()->adapter.open(OBJECT,budget));assertEquals(0,calls.get());
        UploadStoragePort legacy=new UploadStoragePort(){public ObjectIdentity put(ObjectKey k,Path p,long n,String h){throw new AssertionError("No fallback");}public InputStream open(ObjectIdentity o){throw new AssertionError("No fallback");}public ObjectIdentity copy(ObjectIdentity a,ObjectKey b){throw new AssertionError();}public List<ObjectIdentity> versions(ObjectKey k){throw new AssertionError();}};
        var live=new GenericTempBudget(java.time.Clock.systemUTC(),GenericTempPort.Role.PREAPPEND);assertUnavailable(()->legacy.put(QUARANTINE,source(BYTES),BYTES.length,hash(BYTES),live));assertUnavailable(()->legacy.open(OBJECT,live));
    }

    @Test void readinessRequiresBothBucketsVersionedAndFailsClosedWithoutWrites() {
        var buckets = new java.util.ArrayList<String>();
        var adapter = adapter((method, args) -> {
            assertEquals("getBucketVersioning", method);
            var request = (GetBucketVersioningRequest) args[0];
            buckets.add(request.bucket());
            assertEquals(java.time.Duration.ofSeconds(2), request.overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow());
            return GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build();
        });
        assertTrue(adapter.ready());
        assertEquals(List.of("dkp-quarantine", "dkp-originals"), buckets);
        assertFalse(adapter((method, args) -> GetBucketVersioningResponse.builder().build()).ready());
        assertFalse(adapter((method, args) -> GetBucketVersioningResponse.builder().status(BucketVersioningStatus.SUSPENDED).build()).ready());
        assertFalse(adapter((method, args) -> { throw failure(403, "AccessDenied", "request-1"); }).ready());
    }

    @Test void conditionalPutHashesAndReadsBackExactVersion() throws Exception {
        var putCount = new AtomicInteger();
        var adapter = adapter((method, args) -> {
            if (method.equals("putObject")) {
                var request = (PutObjectRequest) args[0];
                assertEquals("*", request.ifNoneMatch());
                assertEquals(ChecksumAlgorithm.SHA256, request.checksumAlgorithm());
                assertEquals(java.util.Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hash(BYTES))), request.checksumSHA256());
                try (var input = ((RequestBody) args[1]).contentStreamProvider().newStream()) {
                    assertArrayEquals(BYTES, input.readAllBytes());
                } catch (Exception e) { throw new AssertionError(e); }
                putCount.incrementAndGet();
                return PutObjectResponse.builder().versionId("exact-version").build();
            }
            assertEquals("getObject", method);
            var get = (GetObjectRequest) args[0];
            assertEquals("exact-version", get.versionId());
            assertEquals(ChecksumMode.ENABLED, get.checksumMode());
            return body(BYTES, "exact-version", BYTES.length);
        });
        assertEquals(OBJECT, adapter.put(QUARANTINE, source(BYTES), BYTES.length, hash(BYTES)));
        assertEquals(1, putCount.get());
    }

    @Test void missingVersionAndReadbackSubstitutionCannotReturnTrustedIdentity() throws Exception {
        for (String version : new String[] {"", "null", "NULL"}) {
            var adapter = adapter((method, args) -> PutObjectResponse.builder().versionId(version).build());
            assertUnavailable(() -> adapter.put(QUARANTINE, source(BYTES), BYTES.length, hash(BYTES)));
        }
        var adapter = adapter((method, args) -> method.equals("putObject")
            ? PutObjectResponse.builder().versionId("exact-version").build()
            : body("different".getBytes(StandardCharsets.UTF_8), "exact-version", 9));
        assertUnavailable(() -> adapter.put(QUARANTINE, source(BYTES), BYTES.length, hash(BYTES)));
    }

    @Test void sourceBoundsHashAndPathAreCheckedBeforeAnyWrite() throws Exception {
        var calls = new AtomicInteger();
        var adapter = adapter((method, args) -> { calls.incrementAndGet(); throw new AssertionError("No write allowed"); });
        Path source = source(BYTES);
        assertUnavailable(() -> adapter.put(QUARANTINE, source, 0, hash(BYTES)));
        assertUnavailable(() -> adapter.put(QUARANTINE, source, BYTES.length + 1L, hash(BYTES)));
        assertUnavailable(() -> adapter.put(QUARANTINE, source, BYTES.length, "f".repeat(64)));
        assertUnavailable(() -> adapter.put(QUARANTINE, Path.of("relative"), BYTES.length, hash(BYTES)));
        Path outside = Files.createDirectories(temporary.resolve("other")).resolve("source");
        Files.write(outside, BYTES);
        assertUnavailable(() -> adapter.put(QUARANTINE, outside, BYTES.length, hash(BYTES)));
        Path link = temporary.resolve("link");
        Files.createSymbolicLink(link, source);
        assertUnavailable(() -> adapter.put(QUARANTINE, link, BYTES.length, hash(BYTES)));
        assertEquals(0, calls.get());
    }

    @Test void openRejectsWrongGenerationOversizeAndTruncatedBytes() throws Exception {
        assertUnavailable(() -> adapter((method, args) -> body(BYTES, "wrong", BYTES.length)).open(OBJECT));
        assertUnavailable(() -> adapter((method, args) -> body(BYTES, "exact-version", 100000001)).open(OBJECT));
        try (var input = adapter((method, args) -> body(BYTES, "exact-version", BYTES.length + 1)).open(OBJECT)) {
            assertThrows(java.io.IOException.class, input::readAllBytes);
        }
        try (var input = adapter((method, args) -> body(BYTES, "exact-version", BYTES.length - 1)).open(OBJECT)) {
            assertThrows(java.io.IOException.class, input::readAllBytes);
        }
        try (var input = adapter((method, args) -> body(new byte[] {1}, "exact-version", 1)).open(OBJECT)) {
            assertEquals(1, input.read());
            assertEquals(-1, input.read());
        }
    }

    @Test void copyUsesConditionalVerifiedPutAndRemovesPrivateTemporaryFile() throws Exception {
        var calls = new AtomicInteger();
        var adapter = adapter((method, args) -> {
            calls.incrementAndGet();
            if (method.equals("putObject")) {
                assertEquals("dkp-originals", ((PutObjectRequest) args[0]).bucket());
                assertEquals("*", ((PutObjectRequest) args[0]).ifNoneMatch());
                return PutObjectResponse.builder().versionId("exact-version").build();
            }
            assertEquals("getObject", method);
            return body(BYTES, "exact-version", BYTES.length);
        });
        assertEquals(new ObjectIdentity(ORIGINAL, "exact-version"), copy(adapter,OBJECT,ORIGINAL));
        assertEquals(3, calls.get());
        try (var files = Files.list(temporary)) { assertEquals(0,files.filter(p->p.getFileName().toString().startsWith("dkp-verification-")||p.getFileName().toString().startsWith("verified-copy-")).count()); }
        assertUnavailable(() -> copy(adapter,OBJECT,QUARANTINE));
        assertUnavailable(() -> copy(adapter,new ObjectIdentity(ORIGINAL,"exact-version"),ORIGINAL));
    }

    @Test void listingNeverExpandsToOtherKeysOrTrustsTruncatedEnumeration() {
        var adapter = adapter((method, args) -> ListObjectVersionsResponse.builder().isTruncated(false)
            .versions(ObjectVersion.builder().key(KEY).versionId("v1").build(),
                ObjectVersion.builder().key(KEY + "other").versionId("foreign").build())
            .deleteMarkers(DeleteMarkerEntry.builder().key(KEY).versionId("marker1").build()).build());
        assertEquals(List.of(new ObjectIdentity(QUARANTINE, "v1"), new ObjectIdentity(QUARANTINE, "marker1")), adapter.versions(QUARANTINE));
        assertUnavailable(() -> adapter((method, args) -> ListObjectVersionsResponse.builder().isTruncated(true).build()).versions(QUARANTINE));
        assertUnavailable(() -> adapter((method, args) -> ListObjectVersionsResponse.builder().isTruncated(false)
            .versions(ObjectVersion.builder().key(KEY).versionId("null").build()).build()).versions(QUARANTINE));
    }



    private static DeletionFence fence(FencePhase phase) {
        return new DeletionFence(UUID.randomUUID(), "synthetic-tenant", UUID.randomUUID(), UUID.randomUUID(),
            OBJECT, UUID.randomUUID(), Instant.parse("2026-10-31T18:00:00Z"), phase);
    }
    private static DeleteObjectResponse deleted(String requestId, String version) {
        DeleteObjectResponse.Builder builder = DeleteObjectResponse.builder().versionId(version);
        builder.responseMetadata(S3ResponseMetadata.create(new AwsResponseMetadata(Map.of("x-amz-request-id", requestId)) { }));
        builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(204).build());
        return builder.build();
    }
    private static S3Exception failure(int status, String code, String requestId) {
        var builder = S3Exception.builder();
        builder.statusCode(status);
        builder.requestId(requestId);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return assertInstanceOf(S3Exception.class, builder.build());
    }

    @Test void fixturesPreservePinnedSdkResponseAndExceptionSemantics() {
        var response = deleted("request-1", "exact-version");
        assertEquals("request-1", response.responseMetadata().requestId());
        assertEquals("exact-version", response.versionId());
        assertEquals(204, response.sdkHttpResponse().statusCode());
        var exception = failure(404, "NoSuchVersion", "request-2");
        assertEquals(404, exception.statusCode());
        assertEquals("request-2", exception.requestId());
        assertEquals("NoSuchVersion", exception.awsErrorDetails().errorCode());
    }


    private ObjectIdentity copy(S3UploadStorageAdapter adapter,ObjectIdentity source,ObjectKey target){try(var phase=new CompleteBudget(java.time.Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE)){return copy(adapter,source,target,phase);}}
    private ObjectIdentity copy(S3UploadStorageAdapter adapter,ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase){
        var identity=VerificationWorkspacePort.Identity.of("synthetic",new org.documentknowledge.identity.Actor("https://issuer.example","synthetic"),UUID.randomUUID(),UUID.randomUUID(),BYTES.length);
        try(var workspace=workspaces.reserve(identity,phase)){workspace.source().write(new ByteArrayInputStream(BYTES),phase);try{return adapter.copy(source,target,phase,workspace);}finally{workspace.finish(phase);}}
    }
    @Test void legacyCopyAndForeignWorkspaceFailClosedBeforeAnyProviderCall(){
        var calls=new AtomicInteger();var adapter=adapter((m,a)->{calls.incrementAndGet();throw new AssertionError("No provider call");});
        assertUnavailable(()->adapter.copy(OBJECT,ORIGINAL));try(var phase=new CompleteBudget(java.time.Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE)){assertUnavailable(()->adapter.copy(OBJECT,ORIGINAL,phase));assertUnavailable(()->adapter.copy(OBJECT,ORIGINAL,phase,null));}assertEquals(0,calls.get());
    }
    private S3UploadStorageAdapter adapter(BiFunction<String, Object[], Object> implementation) {
        var client = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class},
            (proxy, method, args) -> {
                if (method.getName().equals("close")) return null;
                return implementation.apply(method.getName(), args);
            });
        return new S3UploadStorageAdapter(client, temporary,workspaces);
    }
    private Path source(byte[] bytes) throws Exception {
        Path file = Files.createTempFile(temporary, "source-", ".bin");
        Files.write(file, bytes);
        return file;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static ResponseInputStream<GetObjectResponse> body(byte[] bytes, String version, long length) {
        return new ResponseInputStream<>(GetObjectResponse.builder().versionId(version).contentLength(length).build(),
            AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }
    private static void assertUnavailable(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(503, assertThrows(DmFailure.class, operation).status());
    }
}
