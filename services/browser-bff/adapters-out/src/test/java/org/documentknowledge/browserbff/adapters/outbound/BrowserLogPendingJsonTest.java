package org.documentknowledge.browserbff.adapters.outbound;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.documentknowledge.browserbff.adapters.outbound.BrowserLogPendingFixtures.*;

class BrowserLogPendingJsonTest {
    @Test void originalProvenanceIdsAndOptionalTracesRoundTripWithExactCommitIds() {
        var grant = grant(); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var event = event(grant, first); event.put("traceId", "a".repeat(32)); event.put("spanId", "b".repeat(16));
        var ids = BrowserLogPendingJson.events(batch(List.of(event, event(grant, second))), grant);
        assertEquals(List.of(first, second), ids); BrowserLogPendingJson.committed(receipts(List.of(second, first)), ids);
    }
    @Test void wrongOriginalBindingExtraContentAndNonPrimitiveContextCannotRecover() {
        var grant = grant();
        for (var change : Map.<String, Object>of("buildId", UUID.randomUUID().toString(), "runId", UUID.randomUUID().toString(),
                "component", "another", "gitSha", "f".repeat(40), "sourceFingerprint", "e".repeat(64), "message", "secret",
                "eventId", new UUID(0, 0).toString(), "timestamp", "2026-11-03T24:00:00Z", "severity", "INVALID", "eventName", "raw text").entrySet()) {
            var event = event(grant, UUID.randomUUID()); event.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> BrowserLogPendingJson.events(batch(List.of(event)), grant));
        }
        for (var change : Map.<String, Object>of("phase", "raw secret text", "outcome", "INVALID", "durationMs", -1,
                "attempt", 1.2, "attributes", Map.of("message", "secret")).entrySet()) {
            var event = event(grant, UUID.randomUUID());
            @SuppressWarnings("unchecked") var context = (Map<String, Object>) event.get("context"); context.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> BrowserLogPendingJson.events(batch(List.of(event)), grant));
        }
        var event = event(grant, UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> BrowserLogPendingJson.events(batch(List.of(event, event)), grant));
        event.put("spanId", "a".repeat(16));
        assertThrows(IllegalArgumentException.class, () -> BrowserLogPendingJson.events(batch(List.of(event)), grant));
        event.put("traceId", "0".repeat(32));
        assertThrows(IllegalArgumentException.class, () -> BrowserLogPendingJson.events(batch(List.of(event)), grant));
    }
    @Test void malformedWireAndPartialForeignDuplicateOrUnknownReceiptsNeverConfirm() {
        var grant = grant(); UUID id = UUID.randomUUID();
        for (byte[] bytes : Arrays.asList(null, new byte[0], new byte[32769], new byte[]{(byte)0xc3, 0x28},
                "{}{}".getBytes(), "{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes(), "[]".getBytes(),
                batch(List.of()), JSON.writeValueAsBytes(Map.of("schemaVersion", 2, "events", List.of(event(grant, id)))))) {
            assertThrows(IllegalArgumentException.class, () -> BrowserLogPendingJson.events(bytes, grant));
        }
        for (byte[] bytes : List.of(receipts(List.of()), receipts(List.of(UUID.randomUUID())), receipts(List.of(id, id)),
                JSON.writeValueAsBytes(Map.of("receipts", List.of(Map.of("eventId", id.toString(), "state", "UNKNOWN")))),
                JSON.writeValueAsBytes(Map.of("receipts", List.of(Map.of("eventId", id.toString(), "state", "COMMITTED", "receivedAt", "bad"))))))
            assertThrows(IllegalArgumentException.class, () -> BrowserLogPendingJson.committed(bytes, List.of(id)));
        assertThrows(IllegalArgumentException.class, () -> BrowserLogPendingJson.committed(receipts(List.of(id, id)), List.of(id, UUID.randomUUID())));
    }
}
