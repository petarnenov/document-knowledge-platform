package org.documentknowledge.browserbff.adapters.outbound;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserLogScopeCheckpointTest {
    final BrowserLogContexts.Artifact artifact = new BrowserLogContexts.Artifact(UUID.randomUUID(), "admin-portal",
        "a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64));
    final URI endpoint = URI.create("https://localhost:18443/api/v1/dev-logs");
    final UUID parent = UUID.randomUUID();
    final String ca = "e".repeat(64), token = "p".repeat(64);
    final Instant capture = Instant.parse("2026-11-03T09:00:00.123456Z");
    BrowserLogScopeCheckpoint.Binding binding() {
        return new BrowserLogScopeCheckpoint.Binding(endpoint, parent, ca, Map.of(artifact.buildId(), artifact));
    }
    BrowserLogScopeCheckpoint.Recovered scope(String tenant) {
        var grant = new BrowserLogGateway.Grant(UUID.randomUUID(), artifact, UUID.randomUUID(),
            new BrowserLogContexts.Authority("never-persist-this-session-nonce", "https://issuer.invalid", "verified-subject", tenant, capture), capture);
        return new BrowserLogScopeCheckpoint.Recovered(grant, UUID.randomUUID(), token, capture.plusSeconds(60));
    }
    @Test void exactOriginalScopeAndFiniteAuthorityRoundTripWithoutSessionCredentials() {
        for (String tenant : Arrays.asList(null, "tenant-a")) {
            var original = scope(tenant); byte[] bytes = BrowserLogScopeCheckpoint.encode(binding(), original);
            assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("never-persist-this-session-nonce"));
            var restored = BrowserLogScopeCheckpoint.decode(binding(), bytes);
            assertEquals(original.scopeId(), restored.scopeId()); assertEquals(original.token(), restored.token());
            assertEquals(original.replayUntil(), restored.replayUntil()); assertEquals(capture, restored.grant().captureUntil());
            assertEquals(original.grant().contextEpoch(), restored.grant().contextEpoch());
            assertEquals(original.grant().runId(), restored.grant().runId()); assertEquals(artifact, restored.grant().artifact());
            assertEquals(tenant, restored.grant().authority().tenantId()); assertEquals("verified-subject", restored.grant().authority().subject());
            assertEquals("https://issuer.invalid", restored.grant().authority().issuer());
            assertArrayEquals(bytes, BrowserLogScopeCheckpoint.encode(binding(), restored));
            assertFalse(restored.toString().contains(token)); assertFalse(binding().toString().contains(ca));
        }
    }
    @Test void differentEndpointCaParentOrOriginalArtifactCannotAdoptPendingAuthority() {
        byte[] bytes = BrowserLogScopeCheckpoint.encode(binding(), scope("tenant-a"));
        var modified = new BrowserLogContexts.Artifact(artifact.buildId(), artifact.component(), "f".repeat(40),
            artifact.sourceFingerprint(), artifact.manifestSha256(), artifact.vocabularySha256());
        for (var wrong : List.of(
                new BrowserLogScopeCheckpoint.Binding(URI.create("https://other.invalid/api/v1/dev-logs"), parent, ca, Map.of(artifact.buildId(), artifact)),
                new BrowserLogScopeCheckpoint.Binding(endpoint, UUID.randomUUID(), ca, Map.of(artifact.buildId(), artifact)),
                new BrowserLogScopeCheckpoint.Binding(endpoint, parent, "f".repeat(64), Map.of(artifact.buildId(), artifact)),
                new BrowserLogScopeCheckpoint.Binding(endpoint, parent, ca, Map.of(artifact.buildId(), modified)))) {
            assertThrows(IllegalArgumentException.class, () -> BrowserLogScopeCheckpoint.decode(wrong, bytes));
        }
        var wrongArtifact = new BrowserLogScopeCheckpoint.Binding(endpoint, parent, ca, Map.of(artifact.buildId(), modified));
        assertThrows(IllegalArgumentException.class, () -> BrowserLogScopeCheckpoint.encode(wrongArtifact, scope(null)));
    }
    @Test void corruptExtraTruncatedOversizedAndInvalidAuthorityFailWithFixedMessage() {
        byte[] bytes = BrowserLogScopeCheckpoint.encode(binding(), scope(null));
        byte[] wrongMagic = bytes.clone(); wrongMagic[0] ^= 1;
        for (byte[] invalid : Arrays.asList(null, new byte[0], new byte[8193], wrongMagic,
                Arrays.copyOf(bytes, bytes.length - 1), Arrays.copyOf(bytes, bytes.length + 1))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> BrowserLogScopeCheckpoint.decode(binding(), invalid));
            assertEquals("INVALID_BROWSER_LOG_CHECKPOINT", failure.getMessage()); assertNull(failure.getCause());
        }
        var grant = scope(null).grant();
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Recovered(grant, new UUID(0, 0), token, capture));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Recovered(grant, UUID.randomUUID(), "secret", capture));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Recovered(grant, UUID.randomUUID(), token, capture.minusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Binding(URI.create("http://localhost/api/v1/dev-logs"), parent, ca, Map.of(artifact.buildId(), artifact)));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Binding(endpoint, parent, ca, Map.of()));
    }
    @Test void recoveryConfigurationRejectsUntrustedEndpointsAndChangedInstalledBindings() {
        for (String uri : List.of("https:/api/v1/dev-logs", "https://user:password@localhost/api/v1/dev-logs",
                "https://localhost/api/v1/dev-logs?tenant=a", "https://localhost/api/v1/dev-logs#fragment", "https://localhost/other")) {
            assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Binding(URI.create(uri), parent, ca, Map.of(artifact.buildId(), artifact)));
        }
        for (String digest : Arrays.asList(null, "not-a-ca-digest"))
            assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Binding(endpoint, parent, digest, Map.of(artifact.buildId(), artifact)));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Binding(endpoint, new UUID(0, 0), ca, Map.of(artifact.buildId(), artifact)));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Binding(endpoint, parent, ca, Map.of(UUID.randomUUID(), artifact)));
        var tooMany = new HashMap<UUID, BrowserLogContexts.Artifact>();
        for (int i = 0; i < 65; i++) {
            UUID build = UUID.randomUUID(); tooMany.put(build, new BrowserLogContexts.Artifact(build, artifact.component(), artifact.gitSha(),
                artifact.sourceFingerprint(), artifact.manifestSha256(), artifact.vocabularySha256()));
        }
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogScopeCheckpoint.Binding(endpoint, parent, ca, tooMany));
    }
}
