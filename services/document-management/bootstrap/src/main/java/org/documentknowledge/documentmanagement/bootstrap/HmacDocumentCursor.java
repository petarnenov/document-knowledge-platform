package org.documentknowledge.documentmanagement.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.DocumentCursorPort;
import org.documentknowledge.documentmanagement.application.PagePosition;
import org.documentknowledge.identity.Actor;

/** Authenticated pagination position, bound to this actor, tenant and fixed ordering. */
public final class HmacDocumentCursor implements DocumentCursorPort {
    private static final int PAYLOAD_SIZE = 61;
    private static final int SIGNATURE_SIZE = 32;
    private final SecretKeySpec key;

    public HmacDocumentCursor(byte[] secret) {
        if (secret == null || secret.length < 32 || secret.length > 64) {
            throw new IllegalArgumentException("Cursor key must contain 32 to 64 bytes");
        }
        key = new SecretKeySpec(secret.clone(), "HmacSHA256");
    }

    @Override
    public String encode(PagePosition position, Actor actor, String tenant) {
        Objects.requireNonNull(position);
        Objects.requireNonNull(position.createdAt());
        Objects.requireNonNull(position.documentId());
        ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_SIZE);
        payload.put((byte) 1).put(binding(actor, tenant));
        payload.putLong(position.createdAt().getEpochSecond()).putInt(position.createdAt().getNano());
        payload.putLong(position.documentId().getMostSignificantBits());
        payload.putLong(position.documentId().getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(PAYLOAD_SIZE + SIGNATURE_SIZE)
                        .put(payload.array()).put(sign(payload.array())).array());
    }

    @Override
    public PagePosition decode(String cursor, Actor actor, String tenant) {
        if (cursor == null) return null;
        if (cursor.isEmpty() || cursor.length() > 2048 || !cursor.matches("[A-Za-z0-9_-]+")) {
            throw invalid();
        }
        final byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        if (bytes.length != PAYLOAD_SIZE + SIGNATURE_SIZE) throw invalid();
        byte[] payload = Arrays.copyOf(bytes, PAYLOAD_SIZE);
        if (!MessageDigest.isEqual(sign(payload), Arrays.copyOfRange(bytes, PAYLOAD_SIZE, bytes.length))) {
            throw invalid();
        }
        ByteBuffer input = ByteBuffer.wrap(payload);
        if (input.get() != 1) throw invalid();
        byte[] observedBinding = new byte[32];
        input.get(observedBinding);
        if (!MessageDigest.isEqual(binding(actor, tenant), observedBinding)) throw invalid();
        long seconds = input.getLong();
        int nanos = input.getInt();
        if (nanos < 0 || nanos > 999_999_999) throw invalid();
        try {
            return new PagePosition(Instant.ofEpochSecond(seconds, nanos),
                    new UUID(input.getLong(), input.getLong()));
        } catch (DateTimeException malformed) {
            throw invalid();
        }
    }

    private byte[] binding(Actor actor, String tenant) {
        Objects.requireNonNull(actor);
        if (tenant == null || tenant.isBlank() || tenant.length() > 256) throw invalid();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            for (String part : new String[] {"dkp-document-cursor-v1:createdAt,id", actor.issuer(), actor.subject(), tenant}) {
                byte[] encoded = part.getBytes(StandardCharsets.UTF_8);
                output.writeInt(encoded.length);
                output.write(encoded);
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (java.io.IOException | GeneralSecurityException unavailable) {
            throw new IllegalStateException("Cursor cryptography unavailable", unavailable);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Cursor cryptography unavailable", unavailable);
        }
    }

    private static DmFailure invalid() { return new DmFailure(400, "INVALID_CURSOR"); }
}
