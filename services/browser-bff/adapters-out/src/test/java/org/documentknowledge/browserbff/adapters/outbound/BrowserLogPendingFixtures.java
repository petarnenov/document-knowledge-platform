package org.documentknowledge.browserbff.adapters.outbound;

import java.time.Instant;
import java.util.*;
import org.documentknowledge.browserbff.application.*;
import tools.jackson.databind.json.JsonMapper;

final class BrowserLogPendingFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BrowserLogContexts.Artifact ARTIFACT = new BrowserLogContexts.Artifact(UUID.randomUUID(), "admin-portal",
        "a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64));
    static BrowserLogGateway.Grant grant() {
        Instant until = Instant.now().plusSeconds(120);
        return new BrowserLogGateway.Grant(UUID.randomUUID(), ARTIFACT, UUID.randomUUID(),
            new BrowserLogContexts.Authority("private-session-never-on-disk", "https://issuer.invalid", "verified-subject", "tenant-a", until), until);
    }
    static Map<String, Object> event(BrowserLogGateway.Grant grant, UUID id) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", id.toString()); event.put("buildId", grant.artifact().buildId().toString());
        event.put("runId", grant.runId().toString()); event.put("instanceId", UUID.randomUUID().toString());
        event.put("component", grant.artifact().component()); event.put("gitSha", grant.artifact().gitSha());
        event.put("sourceFingerprint", grant.artifact().sourceFingerprint()); event.put("timestamp", Instant.now().toString());
        event.put("severity", "DEBUG"); event.put("eventName", "operation.started"); event.put("correlationId", UUID.randomUUID().toString());
        event.put("context", new HashMap<>(Map.of("phase", "HTTP", "outcome", "STARTED", "durationMs", 0,
            "attempt", 1, "attributes", new HashMap<>(Map.of("count", 1, "cleanupSettled", true)))));
        return event;
    }
    static byte[] batch(List<Map<String, Object>> events) { return JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "events", events)); }
    static byte[] batch(BrowserLogGateway.Grant grant, UUID id) { return batch(List.of(event(grant, id))); }
    static byte[] receipts(List<UUID> ids) {
        return JSON.writeValueAsBytes(Map.of("receipts", ids.stream().map(id -> Map.of("eventId", id.toString(),
            "state", "COMMITTED", "receivedAt", Instant.now().toString())).toList()));
    }
    private BrowserLogPendingFixtures() { }
}
