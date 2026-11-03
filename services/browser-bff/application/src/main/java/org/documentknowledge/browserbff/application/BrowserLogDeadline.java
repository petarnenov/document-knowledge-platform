package org.documentknowledge.browserbff.application;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Trusted route-admission budget, including queue time; never reconstructed by a gateway. */
public final class BrowserLogDeadline {
    private static final long LIMIT = TimeUnit.SECONDS.toNanos(5);
    private final LongSupplier nanos;
    private final long started;
    BrowserLogDeadline(LongSupplier nanos) {
        this.nanos = Objects.requireNonNull(nanos); started = nanos.getAsLong();
    }
    public static BrowserLogDeadline start() { return new BrowserLogDeadline(System::nanoTime); }
    public long remainingNanos() { return Math.max(0, LIMIT - (nanos.getAsLong() - started)); }
    public boolean expired() { return remainingNanos() == 0; }
    public void requireTime() { if (expired()) throw new BffFailure(503, "LOG_STORE_UNAVAILABLE"); }
    /** Positive finite I/O timeout only; an expired operation cannot disable a transport timeout. */
    public long remainingMillis() {
        long remaining = remainingNanos();
        if (remaining == 0) throw new BffFailure(503, "LOG_STORE_UNAVAILABLE");
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}
