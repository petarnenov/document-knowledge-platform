package org.documentknowledge.browserbff.adapters.outbound;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamDeadlineTimerTest {
    @Test void cancelledIdleAndTotalDeadlinesDoNotAccumulateUntilTheirOriginalExpiry() {
        try (var timer = StreamDeadlineTimer.create()) {
            var live = timer.schedule(() -> fail("The original total deadline has not elapsed"), 300, TimeUnit.SECONDS);
            try {
                for (int read = 0; read < 1000; read++) {
                    var idle = timer.schedule(() -> fail("Cancelled idle deadline executed"), 30, TimeUnit.SECONDS);
                    assertTrue(idle.cancel(false));
                }
                assertEquals(1, timer.getQueue().size(), "Only the live total deadline may remain queued");
            } finally {
                live.cancel(false);
            }
            assertTrue(timer.getQueue().isEmpty(), "Closed streams must not retain their deadline tasks");
        }
    }

    @Test void uncancelledDeadlinesStillExecuteOnTheDaemonWorker() throws Exception {
        try (var timer = StreamDeadlineTimer.create()) {
            var executed = new CountDownLatch(1);
            var worker = timer.submit(() -> {
                assertTrue(Thread.currentThread().isDaemon());
                assertEquals("dkp-bff-stream-deadlines", Thread.currentThread().getName());
                executed.countDown();
            });
            worker.get(2, TimeUnit.SECONDS);
            assertTrue(executed.await(2, TimeUnit.SECONDS));
        }
    }
}
