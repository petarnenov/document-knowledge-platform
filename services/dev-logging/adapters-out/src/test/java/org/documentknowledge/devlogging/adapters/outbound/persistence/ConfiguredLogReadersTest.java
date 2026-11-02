package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredLogReadersTest {
    final Instant now = Instant.parse("2026-11-02T12:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    LogReader reader(Instant expiry) { return new LogReader(UUID.randomUUID(), true, null, null, expiry); }
    byte[] hash(String value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }

    @Test void onlyExplicitDedicatedCredentialsYieldTheirOriginalReadGrant() throws Exception {
        var reader = reader(now.plusSeconds(3600)); byte[] digest = hash(token);
        var grant = new ConfiguredLogReaders.Grant(reader, digest); digest[0] ^= 1;
        byte[] exposed = grant.tokenSha256(); exposed[0] ^= 1;
        var auth = new ConfiguredLogReaders(List.of(grant), clock);
        assertEquals(reader, auth.authenticate(token));
        for (String bad : new String[]{"", "short", "a".repeat(513), " ".repeat(43), "x".repeat(43)})
            assertEquals(LogFailure.Kind.UNAUTHENTICATED, assertThrows(LogFailure.class, () -> auth.authenticate(bad)).kind());
        assertThrows(LogFailure.class, () -> auth.authenticate(null));
        assertThrows(LogFailure.class, () -> new ConfiguredLogReaders(List.of(), clock).authenticate(token));
        var expired = new ConfiguredLogReaders(List.of(new ConfiguredLogReaders.Grant(reader(now), hash(token))), clock);
        assertEquals(LogFailure.Kind.EXPIRED, assertThrows(LogFailure.class, () -> expired.authenticate(token)).kind());
    }

    @Test void duplicateAndUnboundedRegistriesAreRejectedBeforeServingRequests() throws Exception {
        var reader = reader(now.plusSeconds(3600)); var grant = new ConfiguredLogReaders.Grant(reader, hash(token));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredLogReaders(Collections.nCopies(4097, grant), clock));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredLogReaders(List.of(grant,
            new ConfiguredLogReaders.Grant(reader, hash("b".repeat(43)))), clock));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredLogReaders(List.of(grant,
            new ConfiguredLogReaders.Grant(reader(now.plusSeconds(3600)), hash(token))), clock));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredLogReaders.Grant(reader, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredLogReaders.Grant(reader, new byte[31]));
    }
}
