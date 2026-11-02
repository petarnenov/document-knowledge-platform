package org.documentknowledge.devlogging.application;

import java.time.*;
import java.util.*;
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogQueriesTest {
    final Instant now = Instant.parse("2026-11-02T12:00:00Z");
    final LogReader reader = new LogReader(UUID.randomUUID(), true, null, null, now.plusSeconds(3600));
    final LogQuery query = new LogQuery(null, null, null, null, null, null, now.minusSeconds(60), now, 1);
    final LogScope scope = new LogScope(UUID.randomUUID(), LogScope.Kind.OPERATOR_ONLY, null, null, null);
    LogQueries.Stored event() { return new LogQueries.Stored(UUID.randomUUID(), scope, UUID.randomUUID(), now.minusSeconds(1), new byte[]{1}); }
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test void onlyMoreVisibleRowsCreateAnOpaqueCursorAndExpiryDoesNotSlide() {
        var positions = new ArrayList<LogQueries.Position>();
        var first = event(); var second = event();
        var original = new LogQueries.Position(now.minusSeconds(1), 12, now.plusSeconds(100));
        LogQueries.Cursors cursors = new LogQueries.Cursors() {
            public String encode(LogReader r, LogQuery q, LogQueries.Position p) { positions.add(p); return "opaque"; }
            public LogQueries.Position decode(LogReader r, LogQuery q, String c) { return original; }
        };
        var service = new LogQueries((r,q,p,d) -> List.of(new LogQueries.Row(first, 9), new LogQueries.Row(second, 8)), cursors, clock);
        var page = service.query(reader, query, null, RequestDeadline.start());
        assertEquals(List.of(first), page.events()); assertEquals("opaque", page.nextCursor());
        assertEquals(now.plusSeconds(900), positions.getFirst().expiresAt());
        service.query(reader, query, "existing", RequestDeadline.start());
        assertEquals(original.expiresAt(), positions.getLast().expiresAt());
        assertThrows(UnsupportedOperationException.class, () -> page.events().clear());
        byte[] copy = first.payload(); copy[0] = 0; assertEquals(1, first.payload()[0]);
    }

    @Test void emptyAndLastPagesHaveNoNextCursorAndExpiredCursorNeverQueries() {
        var calls = new int[1];
        var cursors = new LogQueries.Cursors() {
            public String encode(LogReader r, LogQuery q, LogQueries.Position p) { fail(); return null; }
            public LogQueries.Position decode(LogReader r, LogQuery q, String c) { return new LogQueries.Position(now, 1, now); }
        };
        var service = new LogQueries((r,q,p,d) -> { calls[0]++; return List.of(); }, cursors, clock);
        assertNull(service.query(reader, query, null, RequestDeadline.start()).nextCursor());
        assertThrows(LogFailure.class, () -> service.query(reader, query, "expired", RequestDeadline.start()));
        assertEquals(1, calls[0]);
        var last = new LogQueries((r,q,p,d) -> List.of(new LogQueries.Row(event(), 1)), cursors, clock);
        assertNull(last.query(reader, query, null, RequestDeadline.start()).nextCursor());
    }
}
