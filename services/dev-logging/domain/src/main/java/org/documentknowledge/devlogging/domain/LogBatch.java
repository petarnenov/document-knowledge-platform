package org.documentknowledge.devlogging.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Necessary pre-persistence bounds; the HTTP adapter independently bounds the raw envelope. */
public record LogBatch(List<CanonicalLogEvent> events) {
    public LogBatch {
        Objects.requireNonNull(events);
        if (events.isEmpty() || events.size() > 32) throw new LogFailure(LogFailure.Kind.INVALID);
        events = List.copyOf(events);
        int bytes = 0;
        var ids = new HashSet<UUID>();
        for (var event : events) {
            bytes += event.byteCount();
            if (!ids.add(event.metadata().eventId()) || bytes > 32768) throw new LogFailure(LogFailure.Kind.INVALID);
        }
    }

    public void requireBinding(ProducerScope authority) {
        Objects.requireNonNull(authority);
        for (var event : events) {
            if (!authority.binding().matches(event.metadata())) throw new LogFailure(LogFailure.Kind.FORBIDDEN);
        }
    }
}
