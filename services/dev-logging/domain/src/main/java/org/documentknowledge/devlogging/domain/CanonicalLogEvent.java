package org.documentknowledge.devlogging.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.documentknowledge.observability.EventMetadata;

/** Owned immutable bytes. The validation adapter must bind canonical JSON to this metadata. */
public final class CanonicalLogEvent {
    public static final int MAX_BYTES = 8192;
    private final EventMetadata metadata;
    private final byte[] payload;
    private final String payloadHash;

    public CanonicalLogEvent(EventMetadata metadata, byte[] canonicalUtf8) {
        this.metadata = Objects.requireNonNull(metadata);
        Objects.requireNonNull(canonicalUtf8);
        if (canonicalUtf8.length == 0 || canonicalUtf8.length > MAX_BYTES) throw new LogFailure(LogFailure.Kind.INVALID);
        this.payload = canonicalUtf8.clone();
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload));
        } catch (CharacterCodingException invalid) {
            throw new LogFailure(LogFailure.Kind.INVALID);
        }
        this.payloadHash = HexFormat.of().formatHex(sha256().digest(payload));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    public EventMetadata metadata() { return metadata; }
    public byte[] payload() { return payload.clone(); }
    public String payloadHash() { return payloadHash; }
    /** RFC8785 wrapper of the already canonical event and the verified immutable scope ID. */
    public String scopeBoundHash(UUID scopeId) {
        LogScope.requireId(scopeId);
        var digest = sha256();
        digest.update("{\"event\":".getBytes(StandardCharsets.UTF_8));
        digest.update(payload);
        digest.update((",\"scopeId\":\"" + scopeId + "\"}").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }
    public int byteCount() { return payload.length; }
}
