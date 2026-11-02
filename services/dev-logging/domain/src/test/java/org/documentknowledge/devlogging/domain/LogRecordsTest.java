package org.documentknowledge.devlogging.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.documentknowledge.observability.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogRecordsTest {
    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");
    private static final UUID NIL = new UUID(0, 0);
    private static UUID id(int n) { return new UUID(1, n); }
    private static BuildIdentity build(int n) { return new BuildIdentity(id(n), "document-management", "a".repeat(40), "b".repeat(64)); }
    private static CanonicalLogEvent event(int eventId, int buildId, int runId, int bytes) {
        var execution = new ExecutionIdentity(build(buildId), id(runId), id(99));
        var metadata = new EventMetadata(id(eventId), execution, NOW, Severity.DEBUG,
            EventCode.OPERATION_STARTED, id(100), new TraceContext(null, null));
        return new CanonicalLogEvent(metadata, " ".repeat(bytes).getBytes(StandardCharsets.UTF_8));
    }
    private static ProducerScope scope(int n, LogScope.Kind kind, String tenant) {
        var authority = new LogScope(id(n), kind, kind == LogScope.Kind.TENANT ? "https://issuer.invalid" : null,
            kind == LogScope.Kind.TENANT ? "actor" : null, tenant);
        return new ProducerScope(id(10), authority, new ProducerBinding(build(1), id(2)), NOW.plusSeconds(300));
    }

    @Test
    void canonicalByteOwnershipCannotBeChangedAfterValidation() {
        var metadata = event(3, 1, 2, 1).metadata();
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        var value = new CanonicalLogEvent(metadata, bytes);
        String hash = value.payloadHash();
        String scoped = value.scopeBoundHash(id(7));
        assertEquals("b20ef9e9d7121738433561b4884da86fc53841ac84ab8140af618328b825269f", scoped,
            "Scope identity hashes the canonical event/scopeId JSON wrapper, not escaped JSON text");
        bytes[0] = 'x';
        value.payload()[0] = 'y';
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), value.payload());
        assertEquals(hash, value.payloadHash());
        assertEquals(scoped, value.scopeBoundHash(id(7)));
        assertNotEquals(scoped, value.scopeBoundHash(id(8)));
        assertNotEquals(hash, scoped);
        assertThrows(LogFailure.class, () -> value.scopeBoundHash(NIL));
        assertEquals(64, hash.length());
        assertEquals(2, value.byteCount());
        assertSame(metadata, value.metadata());
    }

    @Test
    void byteBoundsAndStrictUtf8ProtectThePersistencePort() {
        var metadata = event(3, 1, 2, 1).metadata();
        assertEquals(8192, event(3, 1, 2, 8192).byteCount());
        assertThrows(LogFailure.class, () -> event(3, 1, 2, 0));
        assertThrows(LogFailure.class, () -> event(3, 1, 2, 8193));
        assertThrows(LogFailure.class, () -> new CanonicalLogEvent(metadata, new byte[] {(byte)0xc3, 0x28}));
        assertThrows(NullPointerException.class, () -> new CanonicalLogEvent(null, new byte[] {1}));
        assertThrows(NullPointerException.class, () -> new CanonicalLogEvent(metadata, null));
    }

    @Test
    void tenantAndPreselectionRemainDifferentTrustedScopes() {
        var tenant = scope(4, LogScope.Kind.TENANT, "tenant-a");
        assertEquals("tenant-a", tenant.scope().tenantId());
        var unselected = new LogScope(id(5), LogScope.Kind.AUTHENTICATED_UNSELECTED, "https://issuer.invalid", "actor", null);
        assertEquals("actor", unselected.subject());
        assertNull(unselected.tenantId());
        assertEquals(LogScope.Kind.AUTHENTICATED_UNSELECTED, unselected.kind());
        assertThrows(LogFailure.class, () -> new LogScope(id(5), unselected.kind(), unselected.issuer(), unselected.subject(), "tenant-b"));
        assertThrows(LogFailure.class, () -> scope(4, LogScope.Kind.TENANT, null));
        assertThrows(LogFailure.class, () -> scope(4, LogScope.Kind.TENANT, " "));
        assertThrows(LogFailure.class, () -> scope(4, LogScope.Kind.TENANT, "tenant\nforged"));
        assertThrows(LogFailure.class, () -> scope(4, LogScope.Kind.TENANT, "a".repeat(257)));
    }

    @Test
    void unattributedAndInfrastructureNeverAcquireActorOrTenant() {
        for (var kind : List.of(LogScope.Kind.UNATTRIBUTED, LogScope.Kind.OPERATOR_ONLY)) {
            assertNull(scope(4, kind, null).scope().issuer());
            assertThrows(LogFailure.class, () -> new LogScope(id(4), kind, "issuer", null, null));
            assertThrows(LogFailure.class, () -> new LogScope(id(4), kind, null, "actor", null));
            assertThrows(LogFailure.class, () -> scope(4, kind, "tenant-a"));
        }
        assertThrows(LogFailure.class, () -> new LogScope(NIL, LogScope.Kind.OPERATOR_ONLY, null, null, null));
        assertThrows(LogFailure.class, () -> new LogScope(null, LogScope.Kind.OPERATOR_ONLY, null, null, null));
        assertThrows(NullPointerException.class, () -> new LogScope(id(1), null, null, null, null));
    }

    @Test
    void expiryUsesServerTimeAndFailsAtEquality() {
        var authority = scope(4, LogScope.Kind.OPERATOR_ONLY, null);
        authority.requireActive(NOW.plusSeconds(299));
        assertThrows(LogFailure.class, () -> authority.requireActive(NOW.plusSeconds(300)));
        assertThrows(LogFailure.class, () -> authority.requireActive(NOW.plusSeconds(301)));
        assertThrows(NullPointerException.class, () -> authority.requireActive(null));
        assertThrows(LogFailure.class, () -> new ProducerScope(NIL, authority.scope(), authority.binding(), authority.replayUntil()));
        assertThrows(NullPointerException.class, () -> new ProducerScope(id(1), null, authority.binding(), NOW));
        assertThrows(NullPointerException.class, () -> new ProducerScope(id(1), authority.scope(), null, NOW));
        assertThrows(NullPointerException.class, () -> new ProducerScope(id(1), authority.scope(), authority.binding(), null));
    }

    @Test
    void batchIsImmutableBoundedAndCannotCrossItsBuildRunBinding() {
        var input = new ArrayList<>(List.of(event(3, 1, 2, 8192), event(4, 1, 2, 8192), event(5, 1, 2, 8192), event(6, 1, 2, 8192)));
        var batch = new LogBatch(input);
        input.clear();
        assertEquals(4, batch.events().size());
        batch.requireBinding(scope(7, LogScope.Kind.OPERATOR_ONLY, null));
        assertThrows(UnsupportedOperationException.class, () -> batch.events().clear());
        assertThrows(LogFailure.class, () -> new LogBatch(List.of(event(3, 1, 2, 1), event(3, 1, 2, 2))));
        assertThrows(LogFailure.class, () -> new LogBatch(List.of()));
        assertThrows(LogFailure.class, () -> new LogBatch(java.util.Collections.nCopies(33, event(3, 1, 2, 1))));
        assertThrows(LogFailure.class, () -> new LogBatch(List.of(event(3, 1, 2, 8192), event(4, 1, 2, 8192), event(5, 1, 2, 8192), event(6, 1, 2, 8192), event(7, 1, 2, 1))));
        assertThrows(LogFailure.class, () -> new LogBatch(List.of(event(3, 8, 2, 1))).requireBinding(scope(7, LogScope.Kind.OPERATOR_ONLY, null)));
        assertThrows(LogFailure.class, () -> new LogBatch(List.of(event(3, 1, 8, 1))).requireBinding(scope(7, LogScope.Kind.OPERATOR_ONLY, null)));
        assertThrows(LogFailure.class, () -> new ProducerBinding(build(1), id(1)));
        assertThrows(LogFailure.class, () -> new ProducerBinding(build(1), NIL));
        var failure = new LogFailure(LogFailure.Kind.CONFLICT);
        assertEquals(LogFailure.Kind.CONFLICT, failure.kind());
        assertEquals("CONFLICT", failure.getMessage());
    }
}
