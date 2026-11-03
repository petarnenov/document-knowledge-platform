package org.documentknowledge.loggingclient;

import java.util.Objects;
import org.documentknowledge.observability.EventMetadata;

/** Initial typed lifecycle payload. No arbitrary text, Throwable or authority fields. */
public record LifecycleEvent(EventMetadata metadata, String phase, Outcome outcome,
                             long durationMs, long attempt, long count, boolean cleanupSettled) {
    public static final long MAX_INTEGER=9_007_199_254_740_991L;
    public enum Outcome { STARTED, SUCCEEDED, FAILED, CANCELLED, RETRYING, REJECTED, UNKNOWN }
    public LifecycleEvent {
        Objects.requireNonNull(metadata);Objects.requireNonNull(outcome);
        if(phase==null || !phase.matches("[A-Za-z0-9_.-]{1,96}")
                || !number(durationMs) || !number(attempt) || !number(count))
            throw new IllegalArgumentException("INVALID_LOG_EVENT");
    }
    private static boolean number(long value) {return value>=0 && value<=MAX_INTEGER;}
}
