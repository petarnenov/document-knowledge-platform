package org.documentknowledge.devlogging.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.documentknowledge.observability.BuildIdentity;

/** Inbound validation binds these fields to the complete versioned canonical JSON document. */
public final class VerifiedLogManifest {
    public static final int MAX_BYTES = 1048576;
    private final BuildIdentity build;
    private final String digest;
    private final String vocabularyDigest;
    private final byte[] bytes;

    public VerifiedLogManifest(BuildIdentity build, String digest, String vocabularyDigest, byte[] bytes) {
        this.build = Objects.requireNonNull(build);
        if (digest == null || !digest.matches("[0-9a-f]{64}") || vocabularyDigest == null
                || !vocabularyDigest.matches("[0-9a-f]{64}") || bytes == null
                || bytes.length < 1 || bytes.length > MAX_BYTES) {
            throw new LogFailure(LogFailure.Kind.INVALID);
        }
        this.bytes = bytes.clone();
        if (!digest.equals(sha256(this.bytes))) throw new LogFailure(LogFailure.Kind.INVALID);
        this.digest = digest;
        this.vocabularyDigest = vocabularyDigest;
    }

    public BuildIdentity build() { return build; }
    public String digest() { return digest; }
    public String vocabularyDigest() { return vocabularyDigest; }
    public byte[] bytes() { return bytes.clone(); }
    public long reservedBytes() { return 1024L + bytes.length; }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
}
