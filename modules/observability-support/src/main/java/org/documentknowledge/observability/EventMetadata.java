package org.documentknowledge.observability;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable correlation metadata; no tenant authority, payload or persistence claim. */
public record EventMetadata(UUID eventId, ExecutionIdentity execution, Instant timestamp,
                            Severity severity, EventCode eventCode, UUID correlationId,
                            TraceContext trace) {
    public EventMetadata {
        IdentityChecks.requireId(eventId);
        Objects.requireNonNull(execution);
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(severity);
        Objects.requireNonNull(eventCode);
        IdentityChecks.requireId(correlationId);
        Objects.requireNonNull(trace);
    }
}
