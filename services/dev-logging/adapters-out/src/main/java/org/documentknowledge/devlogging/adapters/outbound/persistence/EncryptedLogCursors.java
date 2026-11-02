package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.documentknowledge.devlogging.application.LogQueries;
import org.documentknowledge.devlogging.domain.*;

/** AES-GCM hides global SQL positions and authenticates the exact reader/grant/filter binding. */
public final class EncryptedLogCursors implements LogQueries.Cursors {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptedLogCursors(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("32-byte cursor key required");
        this.key = new SecretKeySpec(key.clone(), "AES");
    }

    @Override
    public String encode(LogReader reader, LogQuery query, LogQueries.Position position) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, reader, query);
            var clear = ByteBuffer.allocate(33).put((byte) 1);
            time(clear, position.receivedAt());
            clear.putLong(position.sequence());
            time(clear, position.expiresAt());
            byte[] encrypted = cipher.doFinal(clear.array());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException | IOException failure) {
            throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
        }
    }

    @Override
    public LogQueries.Position decode(LogReader reader, LogQuery query, String cursor) {
        try {
            if (cursor == null || cursor.length() != 82 || !cursor.matches("[A-Za-z0-9_-]+")) invalid();
            byte[] packed = Base64.getUrlDecoder().decode(cursor);
            // Reject alternate encodings of the same ciphertext, including nonzero unused bits.
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(packed).equals(cursor)) invalid();
            var buffer = ByteBuffer.wrap(packed);
            byte[] nonce = new byte[12]; buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            var clear = ByteBuffer.wrap(cipher(Cipher.DECRYPT_MODE, nonce, reader, query).doFinal(encrypted));
            if (clear.get() != 1) invalid();
            Instant received = time(clear);
            long sequence = clear.getLong();
            Instant expires = time(clear);
            if (sequence <= 0 || received.isBefore(query.from()) || !received.isBefore(query.to())) invalid();
            return new LogQueries.Position(received, sequence, expires);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException failure) {
            throw new LogFailure(LogFailure.Kind.INVALID);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, LogReader reader, LogQuery query)
            throws GeneralSecurityException, IOException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeUTF("dkp-dev-log-cursor-v1");
            out.writeUTF(reader.id().toString()); out.writeBoolean(reader.operator());
            value(out, reader.issuer()); value(out, reader.tenantId()); value(out, reader.expiresAt());
            value(out, query.buildId()); value(out, query.runId()); value(out, query.component());
            value(out, query.minSeverity()); value(out, query.correlationId()); value(out, query.traceId());
            value(out, query.from()); value(out, query.to()); out.writeInt(query.limit());
        }
        cipher.updateAAD(bytes.toByteArray());
        return cipher;
    }

    private static void value(DataOutputStream out, Object value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value.toString());
    }
    private static void time(ByteBuffer buffer, Instant value) { buffer.putLong(value.getEpochSecond()).putInt(value.getNano()); }
    private static Instant time(ByteBuffer buffer) { return Instant.ofEpochSecond(buffer.getLong(), buffer.getInt()); }
    private static void invalid() { throw new LogFailure(LogFailure.Kind.INVALID); }
}
