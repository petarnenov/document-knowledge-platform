package org.documentknowledge.devlogging.adapters.inbound.requests;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.LogFailure;

/** Logging requests only. HTTP serialization/control paths do not run on these workers or timer. */
public final class DeadlineLogRequests implements AutoCloseable {
    private static final int LIMIT = 32;
    private final Semaphore slots = new Semaphore(LIMIT);
    private final Set<Task<?>> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(LIMIT, LIMIT, 0, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(), runnable -> thread("dkp-log-request", runnable), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
        runnable -> thread("dkp-log-deadline", runnable));

    public DeadlineLogRequests() { timer.setRemoveOnCancelPolicy(true); }

    private static Thread thread(String name, Runnable runnable) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Call at route admission, before authentication/parsing; caller cannot supply a new deadline. */
    public <T> Request<T> submit(Function<RequestDeadline, T> action) {
        Objects.requireNonNull(action);
        var request = new Request<T>();
        if (closed.get()) return request.reject(LogFailure.Kind.UNAVAILABLE);
        if (!slots.tryAcquire()) return request.reject(LogFailure.Kind.RATE_LIMITED);
        var task = new Task<>(request, action);
        active.add(task);
        try {
            request.arm();
            workers.execute(task);
        } catch (RejectedExecutionException rejected) {
            // The admitted slot owns its timer before dispatch, including for fast work.
            active.remove(task);
            slots.release();
            request.reject(closed.get() ? LogFailure.Kind.UNAVAILABLE : LogFailure.Kind.RATE_LIMITED);
        }
        return request;
    }

    public record Health(int activeWorkers, int expiredWorkers, int pendingAlarms, boolean closed) { }

    /** Bounded in-memory read; never acquires an ingestion worker or calls a dependency. */
    public Health health() {
        int visited = 0;
        int expired = 0;
        for (var task : active) {
            if (visited++ == LIMIT) break;
            if (task.request.deadline.expired()) expired++;
        }
        int occupied = LIMIT - slots.availablePermits();
        return new Health(occupied, Math.min(occupied, expired), timer.getQueue().size(), closed.get());
    }

    @Override
    public void close() {
        closed.set(true);
        for (var task : active) task.request.reject(LogFailure.Kind.UNAVAILABLE);
        timer.shutdownNow();
        workers.shutdownNow();
        // Interruption is a signal. Occupied slots remain owned until each actual finally exits.
    }

    private final class Task<T> implements Runnable {
        private final Request<T> request;
        private final Function<RequestDeadline, T> action;
        Task(Request<T> request, Function<RequestDeadline, T> action) { this.request = request; this.action = action; }
        @Override public void run() {
            try {
                if (request.outcome.isDone()) return;
                request.deadline.requireTime();
                T result = action.apply(request.deadline);
                request.deadline.requireTime();
                request.succeed(result);
            } catch (Throwable failure) {
                request.reject(failure instanceof LogFailure known ? known.kind() : LogFailure.Kind.UNAVAILABLE);
            } finally {
                request.cancelTimer();
                active.remove(this);
                slots.release();
            }
        }
    }

    /** A single response consumer; the private future never exposes callback registration. */
    public final class Request<T> {
        private final RequestDeadline deadline = RequestDeadline.start();
        private final CompletableFuture<T> outcome = new CompletableFuture<>();
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> alarm = new AtomicReference<>();
        private Request() { }

        private void arm() {
            if (outcome.isDone()) return;
            var scheduled = timer.schedule(() -> reject(LogFailure.Kind.UNAVAILABLE), deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            alarm.set(scheduled);
            if (outcome.isDone()) cancelTimer();
        }
        private Request<T> reject(LogFailure.Kind kind) {
            outcome.completeExceptionally(new LogFailure(kind));
            cancelTimer();
            return this;
        }
        private void succeed(T result) {
            outcome.complete(result);
            cancelTimer();
        }
        private void cancelTimer() {
            var scheduled = alarm.getAndSet(null);
            if (scheduled != null) scheduled.cancel(false);
        }

        /** Disconnect/cancellation affects the response only, never actual worker ownership. */
        public void cancel() { reject(LogFailure.Kind.UNAVAILABLE); }

        /** Passive observation, with no callbacks, waiting or persistence assertion. */
        public boolean responseDecided() { return outcome.isDone(); }

        /** HTTP adapter must wait on its own bounded response path, never on the deadline scheduler. */
        public T await() {
            if (!consumed.compareAndSet(false, true)) throw new IllegalStateException("LOG_RESPONSE_ALREADY_CONSUMED");
            try {
                return outcome.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException elapsed) {
                reject(LogFailure.Kind.UNAVAILABLE);
                throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
            } catch (InterruptedException interrupted) {
                cancel();
                Thread.currentThread().interrupt();
                throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
            } catch (ExecutionException failed) {
                throw new LogFailure(failed.getCause() instanceof LogFailure known ? known.kind() : LogFailure.Kind.UNAVAILABLE);
            }
        }
    }
}
