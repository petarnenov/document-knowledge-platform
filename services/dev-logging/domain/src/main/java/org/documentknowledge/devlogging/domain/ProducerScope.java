package org.documentknowledge.devlogging.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable authority snapshot; expiry is evaluated against trusted server time. */
public record ProducerScope(UUID producerId, LogScope scope, ProducerBinding binding, Instant replayUntil) {
    public ProducerScope {
        LogScope.requireId(producerId);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(binding);
        Objects.requireNonNull(replayUntil);
    }

    public void requireActive(Instant now) {
        if (!Objects.requireNonNull(now).isBefore(replayUntil)) throw new LogFailure(LogFailure.Kind.EXPIRED);
    }
}
