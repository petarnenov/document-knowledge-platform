package org.documentknowledge.browserbff.adapters.outbound;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutboundDeadlineTest {
    @Test void monotonicOriginalBudgetAndExpiry() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new OutboundDeadline(0));
        assertThrows(IllegalArgumentException.class, () -> new OutboundDeadline(300001));
        assertTrue(new OutboundDeadline(300000).nanos() <= TimeUnit.MILLISECONDS.toNanos(300000));
        var deadline = new OutboundDeadline(20);
        assertTrue(deadline.millis() <= 20);
        Thread.sleep(25);
        assertThrows(IOException.class, deadline::nanos);
        assertThrows(IOException.class, deadline::millis);
    }
}
