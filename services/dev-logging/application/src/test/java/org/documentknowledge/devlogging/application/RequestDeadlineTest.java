package org.documentknowledge.devlogging.application;

import java.util.concurrent.TimeUnit;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class RequestDeadlineTest {
    @Test @Timeout(10)
    void oneMonotonicDeadlineExpiresWithoutRenewal() throws Exception {
        var deadline = RequestDeadline.start();
        long first = deadline.remainingNanos();
        assertTrue(first > 0 && first <= TimeUnit.SECONDS.toNanos(5));
        assertFalse(deadline.expired());
        deadline.requireTime();
        TimeUnit.NANOSECONDS.sleep(first + TimeUnit.MILLISECONDS.toNanos(1));
        assertEquals(0, deadline.remainingNanos());
        assertTrue(deadline.expired());
        assertEquals(LogFailure.Kind.UNAVAILABLE, assertThrows(LogFailure.class, deadline::requireTime).kind());
        assertEquals(0, deadline.remainingNanos(), "Reading the budget never renews it");
    }
}
