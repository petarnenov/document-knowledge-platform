package org.documentknowledge.browserbff.adapters.outbound;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.documentknowledge.browserbff.application.Readiness;

/** Finite public-client work/source ownership; never a universal JDK/native settlement claim. */
@Singleton
public final class OwnedJdkTransport implements AutoCloseable {
    static final int EXCHANGES = 64;
    private final Readiness readiness;
    private final IdentityHashMap<HttpClient, Boolean> clients = new IdentityHashMap<>();
    private final IdentityHashMap<Exchange, Boolean> exchanges = new IdentityHashMap<>();
    private final Workers sends = new Workers(8, 64, "dkp-upstream-send", false);
    private final Workers jdk = new Workers(8, 128, "dkp-upstream-jdk", true);
    private final Workers closes = new Workers(2, 64, "dkp-upstream-close", false);
    private boolean closed, failed, monitorStarted, workersTerminated;
    private Runnable firstUnknownSubmission;
    private int clientAttempts, sendTasks, closeTasks, jdkTasks;

    public OwnedJdkTransport(Readiness readiness) {
        this.readiness = Objects.requireNonNull(readiness);
        requireProfile();
    }

    static void requireProfile() {
        var selected = Map.of("java.util.concurrent.ForkJoinPool.common.parallelism", "2",
            "java.util.concurrent.ForkJoinPool.common.maximumSpares", "0",
            "jdk.httpclient.connectionPoolSize", "32", "jdk.httpclient.bufsize", "16384",
            "jdk.httpclient.redirects.retrylimit", "5", "jdk.httpclient.disableRetryConnect", "false",
            "jdk.httpclient.enableAllMethodRetry", "false");
        for (var entry : selected.entrySet())
            if (!entry.getValue().equals(System.getProperty(entry.getKey())))
                throw new IllegalStateException("BFF_JDK_PROFILE_UNAVAILABLE");
        if (System.getProperty("java.util.concurrent.ForkJoinPool.common.threadFactory") != null
            || System.getProperty("java.util.concurrent.ForkJoinPool.common.exceptionHandler") != null
            || ForkJoinPool.commonPool().getParallelism() != 2
            || ForkJoinPool.commonPool().getFactory() != ForkJoinPool.defaultForkJoinWorkerThreadFactory
            || ForkJoinPool.commonPool().getUncaughtExceptionHandler() != null)
            throw new IllegalStateException("BFF_JDK_PROFILE_UNAVAILABLE");
    }

    synchronized HttpClient newClient() {
        if (closed || failed || clientAttempts >= 3) throw new IllegalStateException("BFF_JDK_CLIENT_UNAVAILABLE");
        clientAttempts++;
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).executor(jdk).build();
            clients.put(client, Boolean.TRUE);
            if (client.version() != HttpClient.Version.HTTP_1_1 || client.followRedirects() != HttpClient.Redirect.NEVER
                || client.executor().orElseThrow() != jdk) throw new IllegalStateException("BFF_JDK_PROFILE_UNAVAILABLE");
            return client;
        } catch (RuntimeException | Error error) { fault(null); throw error; }
    }

    synchronized Exchange reserve(HttpClient client, long totalMillis) throws IOException {
        var deadline = new OutboundDeadline(totalMillis);
        if (closed || failed || !clients.containsKey(client) || exchanges.size() >= EXCHANGES)
            throw new IOException("Outbound capacity unavailable");
        var exchange = new Exchange(client, deadline);
        exchanges.put(exchange, Boolean.TRUE);
        return exchange;
    }

    public synchronized Snapshot snapshot() {
        int unknown = 0;
        for (var exchange : exchanges.keySet()) if (exchange.unknown) unknown++;
        return new Snapshot(exchanges.size(), sendTasks, jdkTasks, closeTasks, clients.size(), unknown,
            closed, failed, workersTerminated);
    }
    public record Snapshot(int exchanges, int sendTasks, int jdkTasks, int closeTasks, int clients,
                           int unknown, boolean closed, boolean failed, boolean workersTerminated) { }

    private synchronized void fault(Runnable command) {
        failed = true;
        if (firstUnknownSubmission == null) firstUnknownSubmission = command;
        for (var exchange : exchanges.keySet()) exchange.unknown = true;
        readiness.markUnavailable();
        close();
    }

    final class Exchange implements AutoCloseable {
        private final HttpClient client;
        private final OutboundDeadline deadline;
        private final CompletableFuture<Response> result = new CompletableFuture<>();
        private Response response;
        private boolean dispatched, sendBusy, sourceSettled = true, requestSettled = true;
        private boolean finished, abandoned, handedOff, closeBusy, closeAttempted, unknown;

        private Exchange(HttpClient client, OutboundDeadline deadline) { this.client = client; this.deadline = deadline; }
        long remainingMillis() throws IOException { return deadline.millis(); }
        BoundedStream requestSource(java.io.InputStream source, long length, Runnable authorize,
                                    long idleMillis, Runnable settled) {
            requestSourcePending();
            long budget;
            try { budget = remainingMillis(); }
            catch (IOException expired) { budget = 0; }
            // Even an expired operation first establishes the actual source close/read witness.
            return new BoundedStream(source, length, authorize, budget, idleMillis,
                () -> { settled.run(); requestSourceSettled(); });
        }
        void requestSourcePending() { synchronized (OwnedJdkTransport.this) { requestSettled = false; } }
        void requestSourceSettled() { synchronized (OwnedJdkTransport.this) { requestSettled = true; pump(this); } }

        Response send(HttpRequest request) throws IOException, InterruptedException {
            synchronized (OwnedJdkTransport.this) {
                if (dispatched || finished) throw new IllegalStateException("Single outbound send required");
                dispatched = true; sendBusy = true; sendTasks++;
                var task = new Task() {
                    @Override public void run() {
                        try {
                            synchronized (OwnedJdkTransport.this) {
                                if (closed || abandoned) throw new IOException("Outbound dispatch unavailable");
                            }
                            var actual = HttpRequest.newBuilder(request, (name, value) -> true)
                                .timeout(Duration.ofMillis(deadline.millis())).version(HttpClient.Version.HTTP_1_1).build();
                            var raw = client.send(actual, HttpResponse.BodyHandlers.ofInputStream());
                            synchronized (OwnedJdkTransport.this) {
                                sourceSettled = false;
                                var source = new JdkResponseSource(raw.body(), () -> {
                                    synchronized (OwnedJdkTransport.this) { sourceSettled = true; pump(Exchange.this); }
                                }, () -> fault(null), closingSource -> {
                                    synchronized (OwnedJdkTransport.this) { pump(Exchange.this); }
                                });
                                response = new Response(raw.statusCode(), raw.headers(), source);
                                result.complete(response);
                                pump(Exchange.this);
                            }
                        } catch (IOException | InterruptedException | RuntimeException error) {
                            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                            result.completeExceptionally(error);
                        }
                    }
                    @Override void exited(Throwable error) {
                        synchronized (OwnedJdkTransport.this) {
                            if (error != null) { fault(this); result.completeExceptionally(error); }
                            sendBusy = false; sendTasks--; pump(Exchange.this);
                        }
                    }
                };
                try { sends.execute(task); }
                catch (RuntimeException | Error error) {
                    // A rejected initial task has no upstream source; uncertainty remains globally sticky.
                    fault(task); result.completeExceptionally(error);
                    throw new IOException("Outbound dispatch unavailable", error);
                }
            }
            try { return result.get(deadline.nanos(), TimeUnit.NANOSECONDS); }
            catch (ExecutionException | TimeoutException error) { abandon(); throw new IOException("Outbound unavailable", error); }
            catch (IOException | InterruptedException error) { abandon(); throw error; }
        }

        private void abandon() {
            synchronized (OwnedJdkTransport.this) { abandoned = true; pump(this); }
        }
        void handoff() { synchronized (OwnedJdkTransport.this) { handedOff = true; } }
        @Override public void close() {
            synchronized (OwnedJdkTransport.this) { finished = true; if (!handedOff) abandoned = true; pump(this); }
        }
    }

    record Response(int statusCode, HttpHeaders headers, JdkResponseSource body) {
        byte[] readBounded(Exchange exchange, int limit, long idleMillis) throws IOException {
            try (var bounded = new BoundedStream(body, -1, () -> {}, exchange.remainingMillis(), idleMillis)) {
                byte[] bytes = bounded.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException("Outbound response limit");
                return bytes;
            }
        }
    }

    private void pump(Exchange exchange) {
        if (!exchanges.containsKey(exchange)) return;
        if ((exchange.abandoned || closed) && exchange.response != null && !exchange.response.body().closing()) {
            try { exchange.response.body().close(); }
            catch (IOException | RuntimeException | Error error) { fault(null); }
        }
        if (exchange.response != null && exchange.response.body().closing()
            && !exchange.sourceSettled && !exchange.closeAttempted) {
            exchange.closeAttempted = true; exchange.closeBusy = true; closeTasks++;
            var task = new Task() {
                @Override public void run() {
                    try { exchange.response.body().closeActual(); }
                    catch (IOException | RuntimeException error) { fault(this); }
                }
                @Override void exited(Throwable error) {
                    synchronized (OwnedJdkTransport.this) {
                        if (error != null) fault(this);
                        exchange.closeBusy = false; closeTasks--; pump(exchange);
                    }
                }
            };
            try { closes.execute(task); }
            catch (RuntimeException | Error error) { fault(task); }
        }
        if (exchange.finished && !exchange.sendBusy && !exchange.closeBusy
            && exchange.requestSettled && exchange.sourceSettled && !exchange.unknown)
            exchanges.remove(exchange);
    }

    private abstract static class Task implements Runnable { abstract void exited(Throwable error); }

    private final class Workers extends ThreadPoolExecutor {
        private final boolean jdkWork;
        Workers(int count, int queue, String name, boolean jdkWork) {
            super(count, count, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue),
                command -> { var thread = new Thread(command, name); thread.setDaemon(true); return thread; }, new AbortPolicy());
            this.jdkWork = jdkWork;
        }
        @Override public void execute(Runnable command) {
            Runnable accepted = command;
            if (jdkWork) {
                synchronized (OwnedJdkTransport.this) { jdkTasks++; }
                accepted = new Task() {
                    @Override public void run() { command.run(); }
                    @Override void exited(Throwable error) {
                        synchronized (OwnedJdkTransport.this) {
                            if (error != null) fault(command);
                            jdkTasks--;
                        }
                    }
                };
            }
            try { super.execute(accepted); }
            catch (RuntimeException | Error error) {
                // May escape to the pinned JDK fallback: retain unknown before it can be scheduled.
                fault(command);
                throw error;
            }
        }
        @Override protected void afterExecute(Runnable command, Throwable error) {
            super.afterExecute(command, error);
            ((Task) command).exited(error);
        }
    }

    @jakarta.annotation.PreDestroy
    @Override public synchronized void close() {
        closed = true;
        for (var exchange : exchanges.keySet().toArray(Exchange[]::new)) {
            exchange.abandoned = true;
            pump(exchange);
        }
        if (monitorStarted) return;
        monitorStarted = true;
        var monitor = new Thread(this::drain, "dkp-upstream-drain");
        monitor.setDaemon(true);
        try { monitor.start(); }
        catch (RuntimeException | Error error) { failed = true; readiness.markUnavailable(); }
    }

    private void drain() {
        try {
            HttpClient[] owned;
            synchronized (this) { owned = clients.keySet().toArray(HttpClient[]::new); }
            for (var client : owned) client.shutdownNow();
            sends.shutdown();
            for (var client : owned) while (!client.awaitTermination(Duration.ofMillis(50))) { /* actual selector join */ }
            while (!sends.awaitTermination(50, TimeUnit.MILLISECONDS)) { /* accepted sends must actually exit */ }
            jdk.shutdown();
            closes.shutdown();
            while (!jdk.awaitTermination(50, TimeUnit.MILLISECONDS)) { /* actual worker return */ }
            while (!closes.awaitTermination(50, TimeUnit.MILLISECONDS)) { /* actual close return */ }
            synchronized (this) { workersTerminated = true; notifyAll(); }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fault(null); }
        catch (RuntimeException | Error error) { fault(null); }
    }

    public synchronized boolean awaitTermination(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (!workersTerminated) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }
}
