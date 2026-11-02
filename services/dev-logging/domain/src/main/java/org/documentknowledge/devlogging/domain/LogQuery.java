package org.documentknowledge.devlogging.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.documentknowledge.observability.Severity;

/** Closed filter set. Time bounds apply to trusted server receive time, [from,to). */
public record LogQuery(UUID buildId, UUID runId, String component, Severity minSeverity,
                       UUID correlationId, String traceId, Instant from, Instant to, int limit) {
    public LogQuery {
        optionalId(buildId); optionalId(runId); optionalId(correlationId);
        if (component != null && !component.matches("[a-z][a-z0-9.-]{0,95}")) invalid();
        if (traceId != null && (!traceId.matches("[0-9a-f]{32}") || traceId.equals("0".repeat(32)))) invalid();
        if (from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0
                || limit < 1 || limit > 200) invalid();
    }

    private static void optionalId(UUID id) { if (id != null) LogScope.requireId(id); }
    private static void invalid() { throw new LogFailure(LogFailure.Kind.INVALID); }
}
