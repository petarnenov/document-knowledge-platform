package org.documentknowledge.browserbff.application;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class BrowserLogDeadlineTest {
    @Test void elapsedQueueAndTransportTimeConsumeTheSameOriginalBudget() {
        var nanos = new AtomicLong(1000); var deadline = new BrowserLogDeadline(nanos::get);
        assertEquals(TimeUnit.SECONDS.toNanos(5), deadline.remainingNanos());
        assertEquals(5000, deadline.remainingMillis()); assertFalse(deadline.expired()); deadline.requireTime();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(3)); assertEquals(2000, deadline.remainingMillis());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(2) - 1); assertEquals(1, deadline.remainingMillis());
        nanos.incrementAndGet(); assertTrue(deadline.expired()); assertEquals(0, deadline.remainingNanos());
        var failure = assertThrows(BffFailure.class, deadline::requireTime); assertEquals(503, failure.status());
        assertEquals("LOG_STORE_UNAVAILABLE", failure.code());
        assertThrows(BffFailure.class, deadline::remainingMillis);
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(100)); assertEquals(0, deadline.remainingNanos());
    }
    @Test void productionAdmissionUsesMonotonicClockAndNoCallerBudget() {
        var deadline = BrowserLogDeadline.start();
        assertTrue(deadline.remainingNanos() > 0); assertTrue(deadline.remainingNanos() <= TimeUnit.SECONDS.toNanos(5));
    }
}
