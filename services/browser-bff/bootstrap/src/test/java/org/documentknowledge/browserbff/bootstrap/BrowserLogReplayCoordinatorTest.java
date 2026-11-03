package org.documentknowledge.browserbff.bootstrap;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.documentknowledge.browserbff.adapters.inbound.BrowserLogRequests;
import org.documentknowledge.browserbff.adapters.outbound.DurableBrowserLogGateway.Replay;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserLogReplayCoordinatorTest {
    static void until(BooleanSupplier condition, Duration budget) throws Exception {
        long end = System.nanoTime() + budget.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    static void hold(CountDownLatch release) {
        boolean interrupted = false;
        for (;;) try { release.await(); break; } catch (InterruptedException signal) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Test void realFiveSecondTimeoutDoesNotSubmitAnotherReplayBeforeActualWorkerExit() throws Exception {
        var requests = new BrowserLogRequests(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var workerName = new AtomicReference<String>();
        var coordinator = new BrowserLogReplayCoordinator(requests, deadline -> {
            calls.incrementAndGet(); workerName.set(Thread.currentThread().getName());
            assertTrue(deadline.remainingNanos() > 0); entered.countDown(); hold(release); return Replay.COMMITTED;
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(workerName.get().startsWith("bff-log-work-"));
            assertEquals(7, requests.submit(BrowserLogDeadline.start(), deadline -> 7).await());
            until(() -> coordinator.health().waitingForExit(), Duration.ofSeconds(6));
            assertEquals(1, calls.get()); assertEquals(1, coordinator.health().submitted()); assertEquals(1, requests.health().chargedTasks());
            coordinator.close(); coordinator.close(); assertFalse(coordinator.awaitClosed(Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> coordinator.awaitClosed(Duration.ofNanos(-1)));
            release.countDown(); assertTrue(coordinator.awaitClosed(Duration.ofSeconds(2)));
            assertTrue(coordinator.health().stopped()); assertEquals(1, calls.get());
        } finally { release.countDown(); coordinator.close(); requests.close(); assertTrue(coordinator.awaitClosed(Duration.ofSeconds(2))); assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); }
    }
    @Test void closeWhileRunningWaitsForActualExitEvenWhenDispatcherInterruptsTheWorker() throws Exception {
        var requests = new BrowserLogRequests(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var coordinator = new BrowserLogReplayCoordinator(requests, deadline -> { entered.countDown(); hold(release); return Replay.RETRY; });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); coordinator.close(); requests.close();
            assertFalse(coordinator.awaitClosed(Duration.ZERO)); assertFalse(requests.awaitClosed(Duration.ZERO));
            assertEquals(1, requests.health().chargedTasks());
            release.countDown(); assertTrue(coordinator.awaitClosed(Duration.ofSeconds(2))); assertTrue(requests.awaitClosed(Duration.ofSeconds(2)));
        } finally { release.countDown(); coordinator.close(); requests.close(); assertTrue(coordinator.awaitClosed(Duration.ofSeconds(2))); assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); }
    }
    @Test void retryBackoffIsBoundedOneToThirtySecondsAndSuccessfulOrIdleWorkResetsIt() throws Exception {
        var requests = new BrowserLogRequests(); var delays = new CopyOnWriteArrayList<Duration>(); var stopped = new CountDownLatch(1);
        var calls = new AtomicInteger(); var budgets = ConcurrentHashMap.<BrowserLogDeadline>newKeySet();
        var coordinator = new BrowserLogReplayCoordinator(requests, deadline -> {
            assertTrue(budgets.add(deadline)); int call = calls.incrementAndGet();
            if (call == 1) throw new IllegalStateException("synthetic-private-cause");
            return call == 8 ? Replay.COMMITTED : call == 10 ? Replay.IDLE : call == 12 ? Replay.DISCARDED : Replay.RETRY;
        }, duration -> {
            delays.add(duration);
            if (delays.size() == 13) { stopped.countDown(); throw new InterruptedException(); }
        });
        try {
            assertTrue(stopped.await(3, TimeUnit.SECONDS)); assertTrue(coordinator.awaitClosed(Duration.ofSeconds(2)));
            assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L, 1L, 1L, 1L, 1L, 1L, 1L), delays.stream().map(Duration::toSeconds).toList());
            assertTrue(coordinator.health().stopping()); assertEquals(13, coordinator.health().submitted());
        } finally { coordinator.close(); requests.close(); assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); }
    }
}
