package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import javax.net.ssl.TrustManagerFactory;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.UploadStoragePort;
import org.documentknowledge.documentmanagement.application.upload.CompleteBudget;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Private versioned storage. Domain authorization, accounting and dispatch fencing belong to the UoW. */
public final class S3UploadStorageAdapter implements UploadStoragePort, AutoCloseable {
    private final S3Client client;
    private final Path spoolRoot;
    private final Semaphore copyAdmission = new Semaphore(1);

    public record Configuration(URI endpoint, String region, Path accessKeyFile,
            Path secretKeyFile, Path caFile, Path privateSpoolRoot) {
        public Configuration {
            Objects.requireNonNull(endpoint);
            if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                    || endpoint.getFragment() != null
                    || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))
                    || region == null || !region.matches("[a-z0-9-]{1,64}")) {
                throw new IllegalArgumentException("Invalid private storage endpoint");
            }
            Objects.requireNonNull(accessKeyFile);
            Objects.requireNonNull(secretKeyFile);
            Objects.requireNonNull(caFile);
            Objects.requireNonNull(privateSpoolRoot);
        }
    }

    /** Only this factory is used by runtime bindings; no ambient credentials, trust or retry fallback. */
    public static S3UploadStorageAdapter connect(Configuration configuration) {
        try {
            var root = configuration.privateSpoolRoot();
            byte[] marker = TrustedAuthorityFiles.read(root.resolve(".spool-authority"), 64, true);
            if (!MessageDigest.isEqual(marker, "dkp-private-spool-v1\n".getBytes(StandardCharsets.US_ASCII))) {
                throw unavailable();
            }
            S3Client s3 = client(configuration, "normal");
            return new S3UploadStorageAdapter(s3, root);
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    static S3Client client(Configuration configuration, String role) {
        try {
            String access = credential(configuration.accessKeyFile(), 16, 128);
            String secret = credential(configuration.secretKeyFile(), 32, 256);
            if (access.equals(secret) || Files.isSameFile(configuration.accessKeyFile(), configuration.secretKeyFile())) throw unavailable();
            String binding = "dkp-storage-" + role + "-v1\n" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(access.getBytes(StandardCharsets.US_ASCII))) + "\n" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII))) + "\n";
            byte[] authority = TrustedAuthorityFiles.read(configuration.accessKeyFile().resolveSibling(configuration.accessKeyFile().getFileName() + ".role"), 256, true);
            if (!MessageDigest.isEqual(authority, binding.getBytes(StandardCharsets.US_ASCII))) throw unavailable();
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(
                new java.io.ByteArrayInputStream(TrustedAuthorityFiles.read(configuration.caFile(), 65536, false)));
            if (certificates.isEmpty()) throw unavailable();
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int index = 0;
            for (var certificate : certificates) {
                if (!(certificate instanceof java.security.cert.X509Certificate x509)
                        || x509.getBasicConstraints() < 0) throw unavailable();
                x509.checkValidity();
                trust.setCertificateEntry("storage-ca-" + index++, certificate);
            }
            var trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustFactory.init(trust);
            var managers = trustFactory.getTrustManagers();
            S3Client s3 = S3Client.builder()
                .endpointOverride(configuration.endpoint()).region(Region.of(configuration.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                    .checksumValidationEnabled(true).build())
                .crossRegionAccessEnabled(false)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60))
                    .apiCallAttemptTimeout(Duration.ofSeconds(60))
                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                .httpClientBuilder(Apache5HttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                    .socketTimeout(Duration.ofSeconds(30)).tlsTrustManagersProvider(() -> managers))
                .build();
            return s3;
        } catch (Exception failure) { throw unavailable(); }
    }

    // Constructor seam for transport contract tests, never selected by runtime configuration.
    S3UploadStorageAdapter(S3Client client, Path spoolRoot) {
        this.client = Objects.requireNonNull(client);
        this.spoolRoot = Objects.requireNonNull(spoolRoot).toAbsolutePath().normalize();
    }

    /** Bounded non-mutating readiness; the runtime policy explicitly permits this on both private buckets. */
    public boolean ready() { return ready(client); }

    static boolean ready(S3Client client) {
        try {
            for (String bucket : List.of("dkp-quarantine", "dkp-originals")) {
                var request = software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest.builder()
                    .bucket(bucket).overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(2))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))).build();
                if (client.getBucketVersioning(request).status()
                        != software.amazon.awssdk.services.s3.model.BucketVersioningStatus.ENABLED) return false;
            }
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public ObjectIdentity put(ObjectKey key, Path source, long sizeBytes, String sha256) {return put(key,source,sizeBytes,sha256,null);}
    private ObjectIdentity put(ObjectKey key,Path source,long sizeBytes,String sha256,CompleteBudget.Phase phase) {
        try {
            UploadModels.size(sizeBytes);
            UploadModels.hash(sha256);
            Path normalized = source.toAbsolutePath().normalize();
            if (!source.isAbsolute() || !source.equals(normalized) || !spoolRoot.equals(source.getParent())
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            Digest before;
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                before = digest(input,phase);
            }
            if (before.size() != sizeBytes || !before.sha256().equals(sha256)) throw unavailable();
            var builder = PutObjectRequest.builder().bucket(key.bucket()).key(key.key())
                .ifNoneMatch("*").contentLength(sizeBytes).contentType("application/octet-stream")
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)));
            if(phase!=null){var remaining=phase.remaining();builder.overrideConfiguration(c->c.apiCallTimeout(remaining).apiCallAttemptTimeout(remaining));}
            var request=builder.build();
            ObjectIdentity object;
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                var response = client.putObject(request, RequestBody.fromInputStream(input, sizeBytes));
                object = identity(key, response.versionId());
            }
            // Stored checksums alone are insufficient: verify bytes of the exact returned version.
            try (InputStream input = open(object,phase)) {
                Digest readback = digest(input,phase);
                if (!before.equals(readback)) throw unavailable();
            }
            return object;
        } catch (Exception failure) {
            throw unavailable(); // A possible write stays charged and durably reconciled by the caller.
        }
    }

    @Override
    public InputStream open(ObjectIdentity object) {return open(object,null);}
    @Override public InputStream open(ObjectIdentity object,CompleteBudget.Phase phase) {
        ResponseInputStream<GetObjectResponse> input = null;
        try {
            var key = object.objectKey();
            var builder=GetObjectRequest.builder().bucket(key.bucket()).key(key.key()).versionId(object.versionId()).checksumMode(ChecksumMode.ENABLED);
            if(phase!=null){var remaining=phase.remaining();builder.overrideConfiguration(c->c.apiCallTimeout(remaining).apiCallAttemptTimeout(remaining));}
            input=client.getObject(builder.build());
            var response = input.response();
            if (!object.versionId().equals(response.versionId()) || response.contentLength() == null
                    || response.contentLength() < 1 || response.contentLength() > UploadModels.MAX_BYTES) {
                throw unavailable();
            }
            return new ExactLengthStream(input,response.contentLength(),phase);
        } catch (Exception failure) {
            if (input != null) input.abort();
            throw unavailable();
        }
    }

    @Override
    public ObjectIdentity copy(ObjectIdentity source, ObjectKey target) {return copy(source,target,null);}
    @Override public ObjectIdentity copy(ObjectIdentity source,ObjectKey target,CompleteBudget.Phase phase) {
        if(phase!=null)phase.check();
        if (!"dkp-quarantine".equals(source.objectKey().bucket()) || !"dkp-originals".equals(target.bucket())
                || !source.objectKey().key().substring(0, 102).equals(target.key().substring(0, 102))) {
            throw unavailable();
        }
        if (!copyAdmission.tryAcquire()) throw new DmFailure(429, "QUOTA_EXCEEDED");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(spoolRoot, "verified-copy-", ".bin");
            try (InputStream input = open(source,phase); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                long count = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count = Math.addExact(count, read);
                    if (count > UploadModels.MAX_BYTES) throw unavailable();
                    output.write(buffer, 0, read);
                }
            }
            Digest hash;
            try (InputStream input = Files.newInputStream(temporary, LinkOption.NOFOLLOW_LINKS)) {
                hash = digest(input,phase);
            }
            // A conditional PUT is used instead of relying on vendor-specific destination CopyObject conditions.
            return put(target,temporary,hash.size(),hash.sha256(),phase);
        } catch (Exception failure) {
            throw unavailable();
        } finally {
            boolean cleaned = true;
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException failure) { cleaned = false; }
            }
            // Retain admission after failed local cleanup: retries cannot accumulate unbounded spool files.
            if (cleaned) copyAdmission.release();
            else throw unavailable();
        }
    }

    @Override
    public List<ObjectIdentity> versions(ObjectKey key) { return versions(client, key); }

    static List<ObjectIdentity> versions(S3Client client, ObjectKey key) {
        try {
            var response = client.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(key.bucket()).prefix(key.key()).maxKeys(1000).build());
            if (Boolean.TRUE.equals(response.isTruncated())) throw unavailable();
            var objects = new java.util.ArrayList<ObjectIdentity>();
            response.versions().stream().filter(v -> key.key().equals(v.key()))
                .forEach(v -> objects.add(identity(key, v.versionId())));
            response.deleteMarkers().stream().filter(v -> key.key().equals(v.key()))
                .forEach(v -> objects.add(identity(key, v.versionId())));
            if (objects.size() > 1000) throw unavailable();
            return List.copyOf(objects);
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static ObjectIdentity identity(ObjectKey key, String versionId) {
        if (versionId == null || versionId.isBlank() || "null".equalsIgnoreCase(versionId)) throw unavailable();
        return new ObjectIdentity(key, versionId);
    }
    private static String credential(Path path, int minimum, int maximum) throws IOException {
        String value = new String(TrustedAuthorityFiles.read(path, maximum + 1, true), StandardCharsets.US_ASCII).strip();
        if (value.length() < minimum || value.length() > maximum || !value.matches("[A-Za-z0-9_+/=-]+")) throw unavailable();
        return value;
    }
    private record Digest(long size, String sha256) { }
    private static Digest digest(InputStream input) throws Exception {return digest(input,null);}
    private static Digest digest(InputStream input,CompleteBudget.Phase phase) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if(phase!=null)phase.check();
            total = Math.addExact(total, count);
            if (total > UploadModels.MAX_BYTES) throw unavailable();
            digest.update(buffer, 0, count);
        }
        if (total < 1) throw unavailable();
        return new Digest(total, HexFormat.of().formatHex(digest.digest()));
    }
    private static DmFailure unavailable() { return new DmFailure(503, "UNAVAILABLE"); }
    @Override public void close() { client.close(); }

    private static final class ExactLengthStream extends FilterInputStream {
        private final long expected;
        private final CompleteBudget.Phase phase;private final CompleteBudget.Phase.Registration cancellation;
        private long read;
        ExactLengthStream(ResponseInputStream<GetObjectResponse> input,long expected,CompleteBudget.Phase phase) {super(input);this.expected=expected;this.phase=phase;cancellation=phase==null?null:phase.onCancel(input::abort);}
        @Override public void close()throws IOException {try{if(phase==null)super.close();else ((ResponseInputStream<?>)in).abort();}finally{if(cancellation!=null)cancellation.close();}}
        @Override public int read() throws IOException {
            if(phase!=null)phase.check();
            int result = in.read();
            check(result == -1 ? -1 : 1);
            return result;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if(phase!=null)phase.check();
            int result = in.read(bytes, offset, length);
            check(result);
            return result;
        }
        private void check(int count) throws IOException {
            if(phase!=null)phase.check();
            if (count == -1 ? read != expected : (read += count) > expected) {
                close();
                throw new IOException("Invalid immutable object length");
            }
        }
    }
}
