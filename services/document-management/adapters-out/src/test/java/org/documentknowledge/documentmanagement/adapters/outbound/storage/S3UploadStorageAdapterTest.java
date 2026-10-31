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
import org.junit.jupiter.api.Test;
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
    @TempDir Path temporary;
    private static final byte[] BYTES = "synthetic-storage-contract".getBytes(StandardCharsets.UTF_8);
    private static final String KEY = "a".repeat(64) + "/00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000002";
    private static final ObjectKey QUARANTINE = new ObjectKey("dkp-quarantine", KEY);
    private static final ObjectKey ORIGINAL = new ObjectKey("dkp-originals", KEY);
    private static final ObjectIdentity OBJECT = new ObjectIdentity(QUARANTINE, "exact-version");

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
        assertEquals(new ObjectIdentity(ORIGINAL, "exact-version"), adapter.copy(OBJECT, ORIGINAL));
        assertEquals(3, calls.get());
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
        assertUnavailable(() -> adapter.copy(OBJECT, QUARANTINE));
        assertUnavailable(() -> adapter.copy(new ObjectIdentity(ORIGINAL, "exact-version"), ORIGINAL));
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

    @Test void deletionRequiresDispatchedFenceAndOneBoundTerminalResponsePlusAbsence() {
        var deletes = new AtomicInteger();
        var adapter = adapter((method, args) -> {
            if (method.equals("deleteObject")) {
                var request = (DeleteObjectRequest) args[0];
                assertEquals(OBJECT.versionId(), request.versionId());
                assertEquals(KEY, request.key());
                deletes.incrementAndGet();
                return deleted("request-1", "exact-version");
            }
            assertEquals("headObject", method);
            assertEquals(OBJECT.versionId(), ((HeadObjectRequest) args[0]).versionId());
            throw failure(404, "NoSuchVersion", "request-2");
        });
        var fence = fence(FencePhase.DISPATCHED);
        var result = adapter.deleteOnce(fence);
        assertTrue(result.terminal()); assertTrue(result.noFurtherExecution());
        assertTrue(result.absent()); assertTrue(result.successful());
        assertEquals(fence.attemptId(), result.attemptId()); assertEquals(OBJECT, result.object());
        assertEquals("request-1", result.requestId()); assertEquals(1, deletes.get());
        assertUnavailable(() -> adapter.deleteOnce(fence(FencePhase.AUTHORIZED)));
    }

    @Test void timeoutsServerErrorsAndUnboundResponsesKeepDeletionUnresolved() {
        for (var failure : List.of(SdkClientException.create("synthetic timeout"), failure(503, "Unavailable", "request-1"))) {
            var adapter = adapter((method, args) -> { throw failure; });
            var result = adapter.deleteOnce(fence(FencePhase.DISPATCHED));
            assertFalse(result.terminal()); assertFalse(result.noFurtherExecution()); assertFalse(result.absent());
        }
        for (var response : List.of(deleted("", "exact-version"), deleted("request-1", "wrong-version"))) {
            var result = adapter((method, args) -> response).deleteOnce(fence(FencePhase.DISPATCHED));
            assertFalse(result.terminal()); assertFalse(result.absent());
        }
        var denied = adapter((method, args) -> { throw failure(403, "AccessDenied", "request-1"); })
            .deleteOnce(fence(FencePhase.DISPATCHED));
        assertTrue(denied.terminal()); assertTrue(denied.noFurtherExecution());
        assertFalse(denied.successful()); assertFalse(denied.absent());
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

    @Test void missingSdkRequestMetadataCannotSettleDeletion() {
        DeleteObjectResponse.Builder builder = DeleteObjectResponse.builder().versionId("exact-version");
        builder.responseMetadata(S3ResponseMetadata.create(new AwsResponseMetadata(Map.of()) { }));
        builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(204).build());
        var missing = builder.build();
        assertEquals("UNKNOWN", missing.responseMetadata().requestId());
        var calls = new AtomicInteger();
        var result = adapter((method, args) -> {
            assertEquals("deleteObject", method);
            calls.incrementAndGet();
            return missing;
        }).deleteOnce(fence(FencePhase.DISPATCHED));
        assertEquals(1, calls.get());
        assertFalse(result.terminal());
        assertFalse(result.noFurtherExecution());
        assertFalse(result.absent());
        assertFalse(result.successful());
        var rejected = adapter((method, args) -> { throw failure(403, "AccessDenied", "UNKNOWN"); })
            .deleteOnce(fence(FencePhase.DISPATCHED));
        assertFalse(rejected.terminal());
        assertFalse(rejected.noFurtherExecution());
        assertFalse(rejected.absent());
    }

    private S3UploadStorageAdapter adapter(BiFunction<String, Object[], Object> implementation) {
        var client = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class},
            (proxy, method, args) -> {
                if (method.getName().equals("close")) return null;
                return implementation.apply(method.getName(), args);
            });
        return new S3UploadStorageAdapter(client, temporary);
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
