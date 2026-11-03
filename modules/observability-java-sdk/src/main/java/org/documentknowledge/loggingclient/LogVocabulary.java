package org.documentknowledge.loggingclient;

import java.util.Objects;
import java.util.Set;
import org.documentknowledge.observability.EventCode;

/** Immutable identifiers from the original installed manifest, never from captured text. */
public record LogVocabulary(Set<EventCode> events, Set<String> phases) {
    public LogVocabulary {
        events=Set.copyOf(Objects.requireNonNull(events));
        phases=Set.copyOf(Objects.requireNonNull(phases));
        if(events.isEmpty() || phases.size()>256 || phases.stream().anyMatch(value->!value.matches("[A-Za-z0-9_.-]{1,96}")))
            throw new IllegalArgumentException("INVALID_LOG_VOCABULARY");
    }
}
