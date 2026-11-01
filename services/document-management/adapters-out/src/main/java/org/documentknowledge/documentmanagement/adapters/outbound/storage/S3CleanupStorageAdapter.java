package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import java.util.List;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.CleanupStoragePort;
import org.documentknowledge.documentmanagement.application.upload.UploadStoragePort.DeletionResult;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Cleanup-only client: independent least-privilege credential; no put, copy or content-read capability. */
public final class S3CleanupStorageAdapter implements CleanupStoragePort, AutoCloseable {
    private final S3Client client;
    public static S3CleanupStorageAdapter connect(S3UploadStorageAdapter.Configuration configuration) {
        return new S3CleanupStorageAdapter(S3UploadStorageAdapter.client(configuration, "cleanup"));
    }
    S3CleanupStorageAdapter(S3Client client) { this.client = java.util.Objects.requireNonNull(client); }
    public boolean ready() { return S3UploadStorageAdapter.ready(client); }
    @Override public List<ObjectIdentity> versions(ObjectKey key) { return S3UploadStorageAdapter.versions(client,key); }
    @Override
    public DeletionResult deleteOnce(DeletionFence fence) {
        if (fence.phase() != FencePhase.DISPATCHED) throw unavailable();
        var object = fence.object();
        try {
            var key = object.objectKey();
            var response = client.deleteObject(DeleteObjectRequest.builder().bucket(key.bucket()).key(key.key())
                .versionId(object.versionId()).build());
            String requestId = response.responseMetadata().requestId();
            if (response.sdkHttpResponse().statusCode() != 204 || !requestId(requestId)
                    || (response.versionId() != null && !response.versionId().equals(object.versionId()))) {
                return unknown(fence);
            }
            boolean absent = absent(object);
            return new DeletionResult(fence.attemptId(), object, requestId, true, true, absent, true);
        } catch (S3Exception failure) {
            // A validated terminal 4xx is a rejected one-shot attempt, not proof of deletion.
            if (failure.statusCode() >= 400 && failure.statusCode() < 500 && requestId(failure.requestId())) {
                return new DeletionResult(fence.attemptId(), object, failure.requestId(), true, true, false, false);
            }
            return unknown(fence);
        } catch (SdkClientException failure) {
            return unknown(fence); // Timeout/lost response must never clear the durable fence.
        }
    }

    private boolean absent(ObjectIdentity object) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(object.objectKey().bucket())
                .key(object.objectKey().key()).versionId(object.versionId()).build());
            return false;
        } catch (S3Exception failure) {
            return failure.statusCode() == 404 && failure.awsErrorDetails() != null
                && List.of("NoSuchKey", "NoSuchVersion", "NotFound").contains(failure.awsErrorDetails().errorCode());
        }
    }

    private static boolean requestId(String value) {
        // AWS response metadata substitutes UNKNOWN when the server supplied no request ID.
        return value != null && !"UNKNOWN".equalsIgnoreCase(value)
            && value.matches("[A-Za-z0-9._:-]{1,256}");
    }
    private static DeletionResult unknown(DeletionFence fence) {
        return new DeletionResult(fence.attemptId(), fence.object(), null, false, false, false, false);
    }
    private static DmFailure unavailable() { return new DmFailure(503,"UNAVAILABLE"); }
    @Override public void close() { client.close(); }
}
