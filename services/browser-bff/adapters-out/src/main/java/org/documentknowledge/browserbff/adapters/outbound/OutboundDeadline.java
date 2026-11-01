package org.documentknowledge.browserbff.adapters.outbound;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** One monotonic operation deadline, including admission and queue wait. */
final class OutboundDeadline {
    private final long end;
    OutboundDeadline(long milliseconds) {
        if (milliseconds < 1 || milliseconds > 300_000) throw new IllegalArgumentException("Invalid outbound deadline");
        end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
    }
    long nanos() throws IOException {
        long remaining = end - System.nanoTime();
        if (remaining <= 0) throw new IOException("Outbound deadline");
        return remaining;
    }
    long millis() throws IOException { return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos())); }
}
