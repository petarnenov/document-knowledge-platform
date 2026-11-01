package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.AwsResponseMetadata;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3CleanupStorageAdapterTest {
    private static final String KEY = "a".repeat(64) + "/00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000002";
    private static final ObjectKey QUARANTINE = new ObjectKey("dkp-quarantine", KEY);
    private static final ObjectIdentity OBJECT = new ObjectIdentity(QUARANTINE, "exact-version");








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

    @Test void cleanupCapabilityHasOnlyReconciliationAndFencedDeletionAndClosesOwnedClient() {
        assertFalse(java.util.Arrays.stream(S3UploadStorageAdapter.class.getMethods()).anyMatch(m->m.getName().equals("deleteOnce")));
        assertFalse(java.util.Arrays.stream(S3CleanupStorageAdapter.class.getMethods()).anyMatch(m->List.of("put","copy","open").contains(m.getName())));
        var calls=new AtomicInteger();var client=(S3Client)Proxy.newProxyInstance(S3Client.class.getClassLoader(),new Class<?>[]{S3Client.class},(proxy,method,args)->{
            if(method.getName().equals("close")){calls.incrementAndGet();return null;}
            if(method.getName().equals("getBucketVersioning"))return GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build();
            assertEquals("listObjectVersions",method.getName());return ListObjectVersionsResponse.builder().isTruncated(false).versions(ObjectVersion.builder().key(KEY).versionId("exact-version").build()).build();
        });
        try(var cleanup=new S3CleanupStorageAdapter(client)){assertTrue(cleanup.ready());assertEquals(List.of(OBJECT),cleanup.versions(QUARANTINE));}
        assertEquals(1,calls.get());
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

    private S3CleanupStorageAdapter adapter(BiFunction<String, Object[], Object> implementation) {
        var client = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class},
            (proxy, method, args) -> {
                if (method.getName().equals("close")) return null;
                return implementation.apply(method.getName(), args);
            });
        return new S3CleanupStorageAdapter(client);
    }
    private static void assertUnavailable(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(503, assertThrows(DmFailure.class, operation).status());
    }
}
