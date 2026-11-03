package org.documentknowledge.browserbff.adapters.inbound;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.http.HttpRequest;
import java.util.concurrent.*;
import org.documentknowledge.browserbff.application.BffFailure;
import org.junit.jupiter.api.Test;

final class BrowserLogDispatchBoundaryTest {
    @Test void routeBudgetIsPrivateAndNeverRenewedAtDispatch() {
        var request = HttpRequest.POST("/api/v1/dev-logs/context", "{}").header("X-Deadline", "999999999");
        assertEquals(503, assertThrows(BffFailure.class, () -> BrowserLogRouteDeadline.admitted(request)).status());
        var filter = new BrowserLogRouteDeadline(); filter.admit(request);
        var original = BrowserLogRouteDeadline.admitted(request); long remaining = original.remainingNanos();
        filter.admit(request); assertSame(original, BrowserLogRouteDeadline.admitted(request));
        assertTrue(original.remainingNanos() <= remaining);
    }
    @Test void responseWorkersAreSeparateBoundedAndRefuseImmediatelyAtCapacity() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var executor = new BrowserLogResponseExecutor().executor();
        try {
            for (int i = 0; i < 2; i++) executor.execute(() -> {
                assertTrue(Thread.currentThread().getName().startsWith(BrowserLogResponseExecutor.NAME));
                entered.countDown();
                try { release.await(); } catch (InterruptedException signal) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 16; i++) executor.execute(() -> { });
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("No extra response worker")));
            assertEquals(2, ((ThreadPoolExecutor) executor).getActiveCount());
            assertEquals(16, ((ThreadPoolExecutor) executor).getQueue().size());
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }
}
