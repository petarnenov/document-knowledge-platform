package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.core.io.buffer.ReadBuffer;
import io.micronaut.buffer.netty.NettyReadBufferFactory;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.documentknowledge.browserbff.adapters.inbound.DownloadDelivery;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.Readiness;
import org.documentknowledge.browserbff.application.UploadGateway;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/** Owns only presentation producers, actual IO work and arena payload; not JDK/process settlement. */
@Singleton
public final class DownloadBridge implements DownloadDelivery, AutoCloseable {
    static final int PRODUCERS = 64;
    private final IdentityHashMap<Producer, Boolean> retained = new IdentityHashMap<>();
    private final BridgeAllocationPool allocations = new BridgeAllocationPool();
    private final Readiness readiness;
    private final IoWorkers reads = new IoWorkers(8, "dkp-download-read");
    private final IoWorkers closes = new IoWorkers(2, "dkp-download-close");
    private boolean failed;
    private boolean closed;

    public DownloadBridge(Readiness readiness) { this.readiness = Objects.requireNonNull(readiness); }

    @Override public Reservation reserve(HttpRequest<?> request) {
        if (request.getClass() != NettyHttpRequest.class) throw unavailable();
        var netty = (NettyHttpRequest<?>) request;
        var producer = reserve(netty.getChannelHandlerContext().channel());
        try { netty.addDisposalResource(producer::cancel); return producer; }
        catch (RuntimeException failure) { producer.close(); throw failure; }
    }

    // Same admission/ownership path for genuine registered NIO-channel lifecycle tests.
    synchronized Producer reserve(Channel channel) {
        if (closed || failed || allocations.snapshot().failed() || !channel.isOpen()
            || retained.size() >= PRODUCERS) throw unavailable();
        var producer = new Producer(channel);
        retained.put(producer, Boolean.TRUE);
        try { channel.closeFuture().addListener(producer.disconnect); }
        catch (RuntimeException failure) { producer.close(); throw failure; }
        return producer;
    }

    synchronized Snapshot snapshot() {
        int read = 0, close = 0, callbacks = 0;
        for (var producer : retained.keySet()) {
            if (producer.readBusy) read++;
            if (producer.closeBusy) close++;
            if (producer.callbackBusy || producer.subscribing) callbacks++;
        }
        var pool = allocations.snapshot();
        return new Snapshot(retained.size(), read, close, callbacks,
            pool.bytes(), pool.allocations(), failed || pool.failed(), closed);
    }

    record Snapshot(int producers, int readTasks, int closeTasks, int callbacks,
                    long arenaBytes, int arenaAllocations, boolean failed, boolean closed) { }

    private void fault(Producer producer) {
        failed = true;
        producer.unknown = true;
        producer.cancelled = true;
        readiness.markUnavailable();
        producer.channel.close();
    }

    private static BffFailure unavailable() { return new BffFailure(503, "UNAVAILABLE"); }

    final class Producer implements Reservation, Publisher<ReadBuffer> {
        private final Channel channel;
        private final ChannelFutureListener disconnect = ignored -> cancel();
        private final BridgeAllocationPool.Owner owner = allocations.owner(() -> {
            synchronized (DownloadBridge.this) { pump(this); }
        });
        private InputStream source;
        private byte[] scratch;
        private Body body;
        private Subscriber<? super ReadBuffer> subscriber;
        private ByteBuf pending;
        private long length, count, demand;
        private boolean dispatchOpen = true, sourceSettled, unused, cancelled, stopped;
        private boolean subscribed, subscribing, subscriptionReady, claimed, terminalSent, readBusy, closeBusy, callbackBusy;
        private boolean closeAttempted, unknown, pumping;
        private Throwable failure;

        private Producer(Channel channel) { this.channel = Objects.requireNonNull(channel); }

        @Override public Body attach(UploadGateway.StreamResult result, InputStream authorizedSource) {
            Objects.requireNonNull(result); Objects.requireNonNull(authorizedSource);
            synchronized (DownloadBridge.this) {
                if (!dispatchOpen || !retained.containsKey(this)) throw new IllegalStateException("Download source already assigned");
                source = authorizedSource;
                length = result.length();
                scratch = new byte[BridgeAllocationPool.MAX_CAPACITY];
                body = new Body(length, this);
                dispatchOpen = false;
                result.sourceSettlement().whenComplete((ignored, error) -> {
                    synchronized (DownloadBridge.this) {
                        if (error == null) sourceSettled = true;
                        else fault(this);
                        pump(this);
                    }
                });
                pump(this);
                return body;
            }
        }

        /** A controller-only unused close certifies dispatch has finished without a presentation source. */
        @Override public void close() {
            synchronized (DownloadBridge.this) {
                if (dispatchOpen) { dispatchOpen = false; unused = true; }
                cancel();
            }
        }

        void cancel() {
            synchronized (DownloadBridge.this) { cancelled = true; pump(this); }
        }

        boolean belongsTo(DownloadBridge bridge, Body candidate, Channel candidateChannel) {
            synchronized (DownloadBridge.this) { return bridge == DownloadBridge.this && candidate == body && candidateChannel == channel; }
        }

        boolean ownedBy(DownloadBridge bridge) { return bridge == DownloadBridge.this; }

        void claim() {
            synchronized (DownloadBridge.this) {
                if (claimed || cancelled || !retained.containsKey(this)) throw unavailable();
                claimed = true;
            }
        }

        @Override public void subscribe(Subscriber<? super ReadBuffer> candidate) {
            Objects.requireNonNull(candidate);
            synchronized (DownloadBridge.this) {
                if (subscribed) throw new IllegalStateException("Single download subscriber required");
                subscribed = true;
                subscribing = true;
                subscriber = candidate;
            }
            try {
                candidate.onSubscribe(new Subscription() {
                    @Override public void request(long n) {
                        synchronized (DownloadBridge.this) {
                            if (n <= 0) { failure = new IOException("Invalid download demand"); stopped = true; }
                            else if (Long.MAX_VALUE - demand < n) demand = Long.MAX_VALUE;
                            else demand += n;
                            pump(Producer.this);
                        }
                    }
                    @Override public void cancel() { Producer.this.cancel(); }
                });
            } catch (RuntimeException | Error error) {
                synchronized (DownloadBridge.this) { subscribing = false; fault(this); pump(this); }
                throw error;
            }
            synchronized (DownloadBridge.this) {
                subscribing = false; subscriptionReady = true; pump(this);
            }
        }
    }

    /** Called only under this monitor; all IO and downstream calls happen outside it. */
    private void pump(Producer producer) {
        if (!retained.containsKey(producer) || producer.pumping) return;
        producer.pumping = true;
        try {
            if (allocations.snapshot().failed()) fault(producer);
            if (producer.cancelled && producer.pending != null) {
                var pending = producer.pending; producer.pending = null;
                try { pending.release(); }
                catch (RuntimeException | Error error) { fault(producer); }
            }
            if ((producer.cancelled || producer.stopped) && producer.source != null
                && !producer.sourceSettled && !producer.closeAttempted) {
                producer.closeAttempted = true; producer.closeBusy = true;
                submit(closes, producer, false);
            }
            if (!producer.cancelled && producer.subscriptionReady && !producer.subscribing
                && producer.subscriber != null && !producer.callbackBusy && !producer.readBusy) {
                if (producer.pending != null || producer.stopped && !producer.terminalSent) {
                    producer.callbackBusy = true;
                    try { producer.channel.eventLoop().execute(() -> emit(producer)); }
                    catch (RejectedExecutionException rejected) {
                        // No completed callback witness: conservatively retain its owner.
                        fault(producer);
                    }
                } else if (!producer.stopped && producer.source != null && producer.owner.allocations() == 0
                    && (producer.demand > 0 || producer.count > 0 && producer.count == producer.length)) {
                    producer.readBusy = true;
                    submit(reads, producer, true);
                }
            }
            if (!producer.dispatchOpen && (producer.unused || producer.sourceSettled)
                && (producer.cancelled || producer.terminalSent) && !producer.unknown
                && !producer.readBusy && !producer.closeBusy && !producer.callbackBusy && !producer.subscribing
                && producer.pending == null && producer.owner.allocations() == 0) {
                producer.channel.closeFuture().removeListener(producer.disconnect);
                retained.remove(producer);
            }
        } finally {
            producer.pumping = false;
            if (closed) {
                boolean pendingDispatchOrClose = false;
                for (var held : retained.keySet()) {
                    if (held.dispatchOpen || held.source != null && !held.sourceSettled && !held.closeAttempted) {
                        pendingDispatchOrClose = true; break;
                    }
                }
                // Pre-shutdown dispatches may still return a source requiring the owned close workers.
                if (!pendingDispatchOrClose) closes.shutdown();
            }
        }
    }

    private void submit(IoWorkers workers, Producer producer, boolean read) {
        try { workers.execute(new IoTask(producer, read)); }
        catch (RejectedExecutionException rejected) {
            // The fixed AbortPolicy rejected this unaccepted task; no Future/cancel inference.
            if (read) producer.readBusy = false;
            else producer.closeBusy = false;
            producer.stopped = true;
            producer.failure = new IOException("Download worker unavailable");
            fault(producer);
        }
    }

    private void read(Producer producer) {
        synchronized (this) { if (producer.cancelled || producer.stopped) return; }
        ByteBuf item = null;
        try {
            int n = producer.source.read(producer.scratch, 0, producer.scratch.length);
            synchronized (this) {
                if (n < 0) {
                    if (producer.count != producer.length) throw new IOException("Truncated download");
                    producer.stopped = true; return;
                }
                if (n == 0 || n > producer.length - producer.count) throw new IOException("Invalid download length");
                producer.count += n;
            }
            item = producer.owner.allocator().directBuffer(n, n);
            item.writeBytes(producer.scratch, 0, n);
            synchronized (this) {
                if (!producer.cancelled) { producer.pending = item; item = null; }
            }
        } catch (IOException | RuntimeException error) {
            synchronized (this) {
                producer.failure = new IOException("Download unavailable"); producer.stopped = true;
            }
        } finally {
            if (item != null) item.release();
        }
    }

    /** All reactive data/terminal work starts and finishes on the captured loop, never nested. */
    private void emit(Producer producer) {
        Throwable escaped = null;
        try { emitWork(producer); }
        catch (RuntimeException | Error failure) { escaped = failure; }
        // emitWork and its finally have actually returned before callback ownership is cleared.
        synchronized (this) {
            if (escaped != null) fault(producer);
            producer.callbackBusy = false;
            pump(producer);
        }
    }

    private void emitWork(Producer producer) {
        if (!producer.channel.eventLoop().inEventLoop()) throw new IllegalStateException("Download loop required");
        ByteBuf item;
        boolean cancel;
        Throwable failure;
        synchronized (this) {
            item = producer.pending; producer.pending = null;
            cancel = producer.cancelled;
            failure = producer.failure;
            if (item == null && !cancel) producer.terminalSent = true;
            if (item != null && !cancel) {
                if (producer.demand <= 0) throw new IllegalStateException("Download demand required");
                producer.demand--;
            }
        }
        if (item != null) {
            if (cancel) item.release();
            else {
                ReadBuffer buffer;
                try { buffer = NettyReadBufferFactory.of(producer.owner.allocator()).adapt(item); }
                catch (RuntimeException | Error error) { item.release(); throw error; }
                // Invocation transfers this reference; a throwing subscriber is not a release witness.
                producer.subscriber.onNext(buffer);
            }
        } else if (!cancel) {
            if (failure == null) producer.subscriber.onComplete();
            else producer.subscriber.onError(failure);
        }
    }

    private final class IoTask implements Runnable {
        private final Producer producer;
        private final boolean read;
        private IoTask(Producer producer, boolean read) { this.producer = producer; this.read = read; }
        @Override public void run() {
            if (read) DownloadBridge.this.read(producer);
            else {
                try { producer.source.close(); }
                catch (IOException | RuntimeException error) {
                    synchronized (DownloadBridge.this) { fault(producer); }
                }
            }
        }
        void exited(Throwable escaped) {
            synchronized (DownloadBridge.this) {
                if (escaped != null) fault(producer);
                if (read) producer.readBusy = false;
                else producer.closeBusy = false;
                pump(producer);
            }
        }
    }

    private final class IoWorkers extends ThreadPoolExecutor {
        private IoWorkers(int workers, String name) {
            super(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(PRODUCERS),
                runnable -> { var thread = new Thread(runnable, name); thread.setDaemon(true); return thread; },
                new AbortPolicy());
        }
        @Override protected void afterExecute(Runnable task, Throwable escaped) {
            super.afterExecute(task, escaped);
            ((IoTask) task).exited(escaped);
        }
    }

    @jakarta.annotation.PreDestroy
    @Override public void close() {
        synchronized (this) {
            closed = true;
            for (var producer : retained.keySet().toArray(Producer[]::new)) producer.cancel();
            reads.shutdown();
            // pump shuts close workers only after every already-owned late source has a close task.
            if (retained.isEmpty()) closes.shutdown();
        }
    }
}
