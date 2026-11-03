package org.documentknowledge.browserbff.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.documentknowledge.browserbff.adapters.inbound.BrowserLogRequests;
import org.documentknowledge.browserbff.adapters.outbound.DurableBrowserLogGateway.Replay;
import org.documentknowledge.browserbff.application.*;

/** One lifecycle waiter; all forwarding executes on the existing bounded logging dispatcher. */
final class BrowserLogReplayCoordinator implements AutoCloseable {
    @FunctionalInterface interface Waiter { void pause(Duration delay) throws InterruptedException; }
    record Health(boolean stopping, boolean stopped, boolean waitingForExit, long submitted) { }
    private final BrowserLogRequests requests;
    private final Function<BrowserLogDeadline, Replay> replay;
    private final Waiter waiter;
    private final Runnable cleanup;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<BrowserLogRequests.Request<Replay>> active = new AtomicReference<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicLong submitted = new AtomicLong();
    private volatile boolean waitingForExit;
    private final Thread thread;

    BrowserLogReplayCoordinator(BrowserLogRequests requests, Function<BrowserLogDeadline, Replay> replay) {
        this(requests, replay, Thread::sleep);
    }
    BrowserLogReplayCoordinator(BrowserLogRequests requests, Function<BrowserLogDeadline, Replay> replay, Waiter waiter) {
        this(requests, replay, waiter, () -> { });
    }
    BrowserLogReplayCoordinator(BrowserLogRequests requests, Function<BrowserLogDeadline, Replay> replay, Waiter waiter, Runnable cleanup) {
        this.requests = Objects.requireNonNull(requests); this.replay = Objects.requireNonNull(replay); this.waiter = Objects.requireNonNull(waiter);
        this.cleanup = Objects.requireNonNull(cleanup);
        thread = Thread.ofPlatform().daemon().name("bff-log-replay").start(this::run);
    }
    private void run() {
        int retrySeconds = 1;
        try {
            while (!closed.get()) {
                var request = requests.submit(BrowserLogDeadline.start(), replay); active.set(request); submitted.incrementAndGet();
                if (closed.get()) request.cancel();
                Replay result = Replay.RETRY;
                try { result = request.await(); }
                catch (BffFailure unavailable) { /* Fixed failure; original pending work remains owned. */ }
                finally { awaitActualExit(request); active.compareAndSet(request, null); }
                if (closed.get()) break;
                int delay = result == Replay.RETRY ? retrySeconds : 1;
                retrySeconds = result == Replay.RETRY ? Math.min(30, retrySeconds * 2) : 1;
                waiter.pause(Duration.ofSeconds(delay));
            }
        } catch (InterruptedException interrupted) { closed.set(true); Thread.currentThread().interrupt(); }
        finally { try { cleanup.run(); } finally { stopped.countDown(); } }
    }
    private void awaitActualExit(BrowserLogRequests.Request<?> request) {
        waitingForExit = true; boolean interrupted = false;
        try {
            for (;;) {
                try { if (request.awaitExit(Duration.ofSeconds(1))) return; }
                catch (InterruptedException signal) { interrupted = true; closed.set(true); request.cancel(); }
            }
        } finally { waitingForExit = false; if (interrupted) Thread.currentThread().interrupt(); }
    }
    Health health() { return new Health(closed.get(), stopped.getCount() == 0, waitingForExit, submitted.get()); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        var request = active.get(); if (request != null) request.cancel(); thread.interrupt();
    }
    boolean awaitClosed(Duration budget) throws InterruptedException {
        long nanos = Objects.requireNonNull(budget).toNanos();
        if (nanos < 0) throw new IllegalArgumentException("INVALID_LOG_REPLAY_CLOSE_BUDGET");
        return stopped.await(nanos, TimeUnit.NANOSECONDS);
    }
}
