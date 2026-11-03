package org.documentknowledge.browserbff.adapters.inbound;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.BrowserLogDeadline;

/** Logging-only dependency workers; HTTP serialization belongs to a separately bounded response path. */
public final class BrowserLogRequests implements AutoCloseable {
    private static final int LIMIT = 18;
    private final Semaphore slots = new Semaphore(LIMIT);
    private final Set<Task<?>> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("bff-log-work-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon().name("bff-log-deadline-", 0).factory());

    public BrowserLogRequests() { timer.setRemoveOnCancelPolicy(true); }
    public <T> Request<T> submit(BrowserLogDeadline deadline, Function<BrowserLogDeadline, T> action) {
        Objects.requireNonNull(action);
        var request = new Request<T>(Objects.requireNonNull(deadline));
        if (closed.get() || deadline.expired()) return request.reject(503, "LOG_STORE_UNAVAILABLE");
        if (!slots.tryAcquire()) return request.reject(429, "LOG_RATE_LIMITED");
        var task = new Task<>(request, action); active.add(task);
        try { request.arm(); workers.execute(task); }
        catch (RejectedExecutionException unavailable) {
            task.finish(); request.reject(closed.get() ? 503 : 429,
                closed.get() ? "LOG_STORE_UNAVAILABLE" : "LOG_RATE_LIMITED");
        }
        return request;
    }
    public record Health(int chargedTasks, int runningWorkers, int queuedTasks, int expiredTasks,
                         int pendingAlarms, boolean closed) { }
    public Health health() {
        int visited = 0, expired = 0;
        for (var task : active) { if (visited++ == LIMIT) break; if (task.request.deadline.expired()) expired++; }
        int charged = LIMIT - slots.availablePermits();
        return new Health(charged, workers.getActiveCount(), workers.getQueue().size(), Math.min(charged, expired),
            timer.getQueue().size(), closed.get());
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (var task : active) task.request.reject(503, "LOG_STORE_UNAVAILABLE");
        // Returned queued tasks will never run their finally; retire their exact owned charges here.
        for (Runnable pending : workers.shutdownNow()) ((Task<?>) pending).finish();
        timer.shutdownNow();
    }
    public boolean awaitClosed(Duration budget) throws InterruptedException {
        long remaining = Objects.requireNonNull(budget).toNanos();
        if (remaining < 0) throw new IllegalArgumentException("INVALID_LOG_CLOSE_BUDGET");
        long started = System.nanoTime();
        if (!workers.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return false;
        return timer.awaitTermination(Math.max(0, remaining - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
    }
    private final class Task<T> implements Runnable {
        final Request<T> request;
        final Function<BrowserLogDeadline, T> action;
        final AtomicBoolean released = new AtomicBoolean();
        Task(Request<T> request, Function<BrowserLogDeadline, T> action) { this.request = request; this.action = action; }
        @Override public void run() {
            try {
                if (request.outcome.isDone()) return;
                request.deadline.requireTime();
                T value = action.apply(request.deadline);
                request.deadline.requireTime(); request.outcome.complete(value);
            } catch (Throwable failure) {
                if (failure instanceof BffFailure known) request.reject(known.status(), known.code());
                else request.reject(503, "LOG_STORE_UNAVAILABLE");
            } finally { finish(); }
        }
        void finish() {
            if (!released.compareAndSet(false, true)) return;
            request.cancelTimer(); active.remove(this); slots.release();
        }
    }
    /** Private future prevents callbacks or HTTP serialization from executing on the deadline scheduler. */
    public final class Request<T> {
        private final BrowserLogDeadline deadline;
        private final CompletableFuture<T> outcome = new CompletableFuture<>();
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> alarm = new AtomicReference<>();
        private Request(BrowserLogDeadline deadline) { this.deadline = deadline; }
        private void arm() {
            var scheduled = timer.schedule(() -> reject(503, "LOG_STORE_UNAVAILABLE"), deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            alarm.set(scheduled); if (outcome.isDone()) cancelTimer();
        }
        private Request<T> reject(int status, String code) {
            outcome.completeExceptionally(new BffFailure(status, code)); cancelTimer(); return this;
        }
        private void cancelTimer() {
            var scheduled = alarm.getAndSet(null); if (scheduled != null) scheduled.cancel(false);
        }
        public void cancel() { reject(503, "LOG_STORE_UNAVAILABLE"); }
        public boolean responseDecided() { return outcome.isDone(); }
        public T await() {
            if (!consumed.compareAndSet(false, true)) throw new IllegalStateException("LOG_RESPONSE_ALREADY_CONSUMED");
            try { return outcome.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS); }
            catch (TimeoutException elapsed) { cancel(); throw new BffFailure(503, "LOG_STORE_UNAVAILABLE"); }
            catch (InterruptedException interrupted) {
                cancel(); Thread.currentThread().interrupt(); throw new BffFailure(503, "LOG_STORE_UNAVAILABLE");
            } catch (ExecutionException failed) {
                var known = (BffFailure) failed.getCause(); throw new BffFailure(known.status(), known.code());
            }
        }
    }
}
