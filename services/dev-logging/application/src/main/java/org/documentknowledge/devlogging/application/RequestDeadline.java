package org.documentknowledge.devlogging.application;

import java.util.concurrent.TimeUnit;
import org.documentknowledge.devlogging.domain.LogFailure;

/** Trusted route-admission clock; pass the same object through every logging layer. */
public final class RequestDeadline {
    private static final long LIMIT = TimeUnit.SECONDS.toNanos(5);
    private final long started = System.nanoTime();

    private RequestDeadline() { }
    public static RequestDeadline start() { return new RequestDeadline(); }
    public long remainingNanos() { return Math.max(0, LIMIT - (System.nanoTime() - started)); }
    public boolean expired() { return remainingNanos() == 0; }
    public void requireTime() {
        if (expired()) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
    }
}
