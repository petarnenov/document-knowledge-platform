package org.documentknowledge.devlogging.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogQueryTest {
    final Instant now = Instant.parse("2026-11-02T12:00:00Z");
    final UUID id = UUID.randomUUID();

    @Test void validatesTheClosedQueryWindowAndFilterSet() {
        assertDoesNotThrow(() -> query(null, null, now.minusSeconds(86400), now, 200));
        for (String component : new String[]{"", "UPPER", "a/../b", "a".repeat(97)})
            assertThrows(LogFailure.class, () -> query(component, null, now.minusSeconds(1), now, 50));
        assertDoesNotThrow(() -> query("browser-bff", "a".repeat(32), now.minusSeconds(1), now, 1));
        for (String trace : new String[]{"0".repeat(32), "A".repeat(32), "bad"})
            assertThrows(LogFailure.class, () -> query(null, trace, now.minusSeconds(1), now, 50));
        assertThrows(LogFailure.class, () -> query(null, null, null, now, 50));
        assertThrows(LogFailure.class, () -> query(null, null, now, null, 50));
        assertThrows(LogFailure.class, () -> query(null, null, now, now, 50));
        assertThrows(LogFailure.class, () -> query(null, null, now, now.minusSeconds(1), 50));
        assertThrows(LogFailure.class, () -> query(null, null, now.minusSeconds(86401), now, 50));
        for (int limit : new int[]{0, 201})
            assertThrows(LogFailure.class, () -> query(null, null, now.minusSeconds(1), now, limit));
        UUID zero = new UUID(0, 0);
        assertThrows(LogFailure.class, () -> new LogQuery(zero, null, null, null, null, null, now.minusSeconds(1), now, 50));
        assertThrows(LogFailure.class, () -> new LogQuery(null, zero, null, null, null, null, now.minusSeconds(1), now, 50));
        assertThrows(LogFailure.class, () -> new LogQuery(null, null, null, null, zero, null, now.minusSeconds(1), now, 50));
    }

    LogQuery query(String component, String trace, Instant from, Instant to, int limit) {
        return new LogQuery(null, null, component, null, null, trace, from, to, limit);
    }

    @Test void readersCannotFallThroughToGlobalVisibilityOrOutliveTheirGrant() {
        var tenant = new LogReader(id, false, "https://issuer.invalid", "tenant-a", now);
        tenant.requireActive(now.minusNanos(1));
        assertThrows(LogFailure.class, () -> tenant.requireActive(now));
        assertDoesNotThrow(() -> new LogReader(id, true, null, null, now));
        assertThrows(LogFailure.class, () -> new LogReader(id, true, "issuer", null, now));
        assertThrows(LogFailure.class, () -> new LogReader(id, true, null, "tenant", now));
        assertThrows(LogFailure.class, () -> new LogReader(id, true, null, null, null));
        for (String invalid : new String[]{"", " ", "bad\nvalue", "a".repeat(513)})
            assertThrows(LogFailure.class, () -> new LogReader(id, false, invalid, "tenant", now));
        assertThrows(LogFailure.class, () -> new LogReader(id, false, null, "tenant", now));
        assertThrows(LogFailure.class, () -> new LogReader(id, false, "issuer", null, now));
        assertThrows(LogFailure.class, () -> new LogReader(id, false, "issuer", "a".repeat(257), now));
    }
}
