package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.time.Instant;
import java.util.*;
import org.documentknowledge.devlogging.application.LogQueries;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.Severity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EncryptedLogCursorsTest {
    final Instant now = Instant.parse("2026-11-02T12:00:00Z");
    final LogReader reader = new LogReader(UUID.randomUUID(), false, "issuer", "tenant-a", now.plusSeconds(3600));
    final LogQuery query = new LogQuery(UUID.randomUUID(), UUID.randomUUID(), "browser-bff", Severity.DEBUG,
        UUID.randomUUID(), "1".repeat(32), now.minusSeconds(60), now, 50);
    final LogQueries.Position position = new LogQueries.Position(now.minusSeconds(1), 87654321, now.plusSeconds(900));
    byte[] key() { byte[] result = new byte[32]; new java.security.SecureRandom().nextBytes(result); return result; }

    @Test void encryptionHidesPositionsAndAuthenticatesReaderGrantAndEveryFilter() {
        byte[] key = key(); var codec = new EncryptedLogCursors(key); Arrays.fill(key, (byte) 0);
        String token = codec.encode(reader, query, position);
        assertEquals(position, codec.decode(reader, query, token));
        assertNotEquals(token, codec.encode(reader, query, position));
        assertFalse(new String(Base64.getUrlDecoder().decode(token), java.nio.charset.StandardCharsets.ISO_8859_1).contains("87654321"));
        for (var other : List.of(new LogReader(UUID.randomUUID(), false, "issuer", "tenant-a", reader.expiresAt()),
                new LogReader(reader.id(), false, "other", "tenant-a", reader.expiresAt()),
                new LogReader(reader.id(), false, "issuer", "tenant-b", reader.expiresAt()),
                new LogReader(reader.id(), true, null, null, reader.expiresAt()),
                new LogReader(reader.id(), false, "issuer", "tenant-a", reader.expiresAt().plusSeconds(1))))
            assertThrows(LogFailure.class, () -> codec.decode(other, query, token));
        for (var other : List.of(
            new LogQuery(null, query.runId(), query.component(), query.minSeverity(), query.correlationId(), query.traceId(), query.from(), query.to(), 50),
            new LogQuery(query.buildId(), null, query.component(), query.minSeverity(), query.correlationId(), query.traceId(), query.from(), query.to(), 50),
            new LogQuery(query.buildId(), query.runId(), null, query.minSeverity(), query.correlationId(), query.traceId(), query.from(), query.to(), 50),
            new LogQuery(query.buildId(), query.runId(), query.component(), null, query.correlationId(), query.traceId(), query.from(), query.to(), 50),
            new LogQuery(query.buildId(), query.runId(), query.component(), query.minSeverity(), null, query.traceId(), query.from(), query.to(), 50),
            new LogQuery(query.buildId(), query.runId(), query.component(), query.minSeverity(), query.correlationId(), null, query.from(), query.to(), 50),
            new LogQuery(query.buildId(), query.runId(), query.component(), query.minSeverity(), query.correlationId(), query.traceId(), query.from().minusSeconds(1), query.to(), 50),
            new LogQuery(query.buildId(), query.runId(), query.component(), query.minSeverity(), query.correlationId(), query.traceId(), query.from(), query.to().plusSeconds(1), 50),
            new LogQuery(query.buildId(), query.runId(), query.component(), query.minSeverity(), query.correlationId(), query.traceId(), query.from(), query.to(), 51)))
            assertThrows(LogFailure.class, () -> codec.decode(reader, other, token));
        assertThrows(LogFailure.class, () -> new EncryptedLogCursors(key()).decode(reader, query, token));
        byte[] damaged = Base64.getUrlDecoder().decode(token); damaged[20] ^= 1;
        assertThrows(LogFailure.class, () -> codec.decode(reader, query, Base64.getUrlEncoder().withoutPadding().encodeToString(damaged)));
    }

    @Test void malformedOrOutOfWindowPositionsAreRejectedWithoutPayloadEcho() {
        var codec = new EncryptedLogCursors(key());
        for (String token : new String[]{"", "a".repeat(2049), "!".repeat(82), "A".repeat(82)})
            assertEquals(LogFailure.Kind.INVALID, assertThrows(LogFailure.class, () -> codec.decode(reader, query, token)).kind());
        assertThrows(LogFailure.class, () -> codec.decode(reader, query, null));
        for (var invalid : List.of(new LogQueries.Position(position.receivedAt(), 0, position.expiresAt()),
                new LogQueries.Position(query.from().minusSeconds(1), 1, position.expiresAt()),
                new LogQueries.Position(query.to(), 1, position.expiresAt()))) {
            String token = codec.encode(reader, query, invalid);
            assertThrows(LogFailure.class, () -> codec.decode(reader, query, token));
        }
        assertThrows(IllegalArgumentException.class, () -> new EncryptedLogCursors(null));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedLogCursors(new byte[16]));
    }
}
