package org.documentknowledge.devlogging.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.documentknowledge.devlogging.domain.*;

/** Reader-grant query boundary; SQL positions never become public response fields. */
public final class LogQueries {
    public record Position(Instant receivedAt, long sequence, Instant expiresAt) { }
    public record Stored(UUID producerId, LogScope scope, UUID eventId, Instant receivedAt, byte[] payload) {
        public Stored { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    public record Row(Stored event, long sequence) { }
    public record Page(List<Stored> events, String nextCursor) {
        public Page { events = List.copyOf(events); }
    }
    public interface Store {
        /** Return at most limit+1 rows, already grant-filtered, descending receive-time/sequence. */
        List<Row> read(LogReader reader, LogQuery query, Position after, RequestDeadline deadline);
    }
    public interface Cursors {
        String encode(LogReader reader, LogQuery query, Position position);
        Position decode(LogReader reader, LogQuery query, String cursor);
    }

    private final Store store;
    private final Cursors cursors;
    private final Clock clock;

    public LogQueries(Store store, Cursors cursors, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.cursors = Objects.requireNonNull(cursors);
        this.clock = Objects.requireNonNull(clock);
    }

    public Page query(LogReader reader, LogQuery query, String cursor, RequestDeadline deadline) {
        reader.requireActive(clock.instant());
        deadline.requireTime();
        Position after = cursor == null ? null : cursors.decode(reader, query, cursor);
        Instant expiry = after == null ? clock.instant().plusSeconds(900) : after.expiresAt();
        if (!clock.instant().isBefore(expiry)) throw new LogFailure(LogFailure.Kind.INVALID);
        var rows = store.read(reader, query, after, deadline);
        deadline.requireTime();
        reader.requireActive(clock.instant());
        boolean more = rows.size() > query.limit();
        var visible = rows.subList(0, Math.min(query.limit(), rows.size()));
        String next = null;
        if (more) {
            var last = visible.getLast();
            next = cursors.encode(reader, query, new Position(last.event().receivedAt(), last.sequence(), expiry));
        }
        return new Page(visible.stream().map(Row::event).toList(), next);
    }
}
