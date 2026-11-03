package org.documentknowledge.browserbff.adapters.inbound;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.BrowserLogDeadline;
import org.junit.jupiter.api.Test;

final class BrowserLogRequestsTest {
    private static void status(int expected, BrowserLogRequests.Request<?> request) {
        assertEquals(expected, assertThrows(BffFailure.class, request::await).status());
    }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private static void hold(CountDownLatch release) {
        boolean interrupted = false;
        for (;;) try { release.await(); break; } catch (InterruptedException signal) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Test void originalBudgetFiniteAdmissionCancellationAndActualWorkerOwnership() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var requests = new BrowserLogRequests();
        try {
            List<BrowserLogRequests.Request<Integer>> active = new ArrayList<>();
            var original = BrowserLogDeadline.start();
            for (int i = 0; i < 2; i++) {
                var deadline = original;
                active.add(requests.submit(deadline, actual -> { assertSame(deadline, actual); entered.countDown(); hold(release); return 1; }));
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queuedCalls = new AtomicInteger();
            for (int i = 0; i < 16; i++) active.add(requests.submit(BrowserLogDeadline.start(), ignored -> queuedCalls.incrementAndGet()));
            assertEquals(18, requests.health().chargedTasks()); assertEquals(16, requests.health().queuedTasks());
            status(429, requests.submit(BrowserLogDeadline.start(), ignored -> 99));
            active.getFirst().cancel(); assertTrue(active.getFirst().responseDecided()); status(503, active.getFirst());
            assertEquals(18, requests.health().chargedTasks()); assertEquals(2, requests.health().runningWorkers());
            // Real five-second total deadline expires independently while actual workers remain held.
            long started = System.nanoTime(); status(503, active.get(1));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(6));
            assertEquals(18, requests.health().chargedTasks()); assertTrue(requests.health().expiredTasks() >= 2);
            status(503, requests.submit(original, ignored -> fail("Expired work must not dispatch")));
            waitFor(() -> active.stream().allMatch(BrowserLogRequests.Request::responseDecided));
            release.countDown(); waitFor(() -> requests.health().chargedTasks() == 0);
            assertEquals(0, queuedCalls.get());
            for (int i = 2; i < active.size(); i++) status(503, active.get(i));
            assertEquals(7, requests.submit(BrowserLogDeadline.start(), ignored -> 7).await());
            waitFor(() -> requests.health().pendingAlarms() == 0);
        } finally { release.countDown(); requests.close(); assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); }
    }
    @Test void shutdownRetiresQueuedTasksButNeverClaimsRunningExitFromInterruption() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1); var requests = new BrowserLogRequests();
        try {
            for (int i = 0; i < 2; i++) requests.submit(BrowserLogDeadline.start(), ignored -> { entered.countDown(); hold(release); return 1; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = new ArrayList<BrowserLogRequests.Request<Integer>>(); var calls = new AtomicInteger();
            for (int i = 0; i < 16; i++) queued.add(requests.submit(BrowserLogDeadline.start(), ignored -> calls.incrementAndGet()));
            requests.close(); requests.close();
            assertTrue(requests.health().closed()); assertEquals(2, requests.health().chargedTasks());
            assertEquals(0, requests.health().queuedTasks()); assertEquals(0, requests.health().pendingAlarms());
            assertFalse(requests.awaitClosed(Duration.ZERO)); assertEquals(0, calls.get());
            for (var request : queued) status(503, request);
            status(503, requests.submit(BrowserLogDeadline.start(), ignored -> 3));
            assertThrows(IllegalArgumentException.class, () -> requests.awaitClosed(Duration.ofNanos(-1)));
        } finally { release.countDown(); requests.close(); assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); }
        assertEquals(0, requests.health().chargedTasks());
    }
    @Test void fixedFailuresSingleConsumerAndInterruptedResponseDoNotExposeThrowableText() throws Exception {
        var requests = new BrowserLogRequests(); var release = new CountDownLatch(1);
        try {
            var denied = requests.submit(BrowserLogDeadline.start(), ignored -> { throw new BffFailure(403, "LOG_CONTEXT_RETIRED"); });
            status(403, denied); assertThrows(IllegalStateException.class, denied::await);
            var failed = requests.submit(BrowserLogDeadline.start(), ignored -> { throw new IllegalStateException("synthetic-secret"); });
            assertEquals("LOG_STORE_UNAVAILABLE", assertThrows(BffFailure.class, failed::await).getMessage());
            var entered = new CountDownLatch(1);
            var request = requests.submit(BrowserLogDeadline.start(), ignored -> { entered.countDown(); hold(release); return 1; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var observed = new CompletableFuture<Boolean>();
            var consumer = Thread.ofPlatform().start(() -> {
                Thread.currentThread().interrupt();
                try { request.await(); observed.complete(false); }
                catch (BffFailure failure) { observed.complete(failure.status() == 503 && Thread.currentThread().isInterrupted()); }
            });
            consumer.join(2000); assertFalse(consumer.isAlive()); assertTrue(observed.get(2, TimeUnit.SECONDS));
            waitFor(() -> requests.health().chargedTasks() == 1);
            assertTrue(request.responseDecided()); assertEquals(1, requests.health().chargedTasks());
        } finally { release.countDown(); requests.close(); assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); }
    }
}
