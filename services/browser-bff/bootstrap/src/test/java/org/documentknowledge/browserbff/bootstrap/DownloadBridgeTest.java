package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.core.io.buffer.ReadBuffer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.documentknowledge.browserbff.application.Readiness;
import org.documentknowledge.browserbff.application.UploadGateway;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import static org.junit.jupiter.api.Assertions.*;

class DownloadBridgeTest {
    @Test void finiteReservationsRetainCancelledDispatchUntilExplicitUnusedClose() throws Exception {
        try (var fixture = new Fixture()) {
            var held = new ArrayList<DownloadBridge.Producer>();
            for (int i = 0; i < 64; i++) held.add(fixture.bridge.reserve(fixture.channel));
            assertEquals(64, fixture.bridge.snapshot().producers());
            var refused = assertThrows(org.documentknowledge.browserbff.application.BffFailure.class,
                () -> fixture.bridge.reserve(fixture.channel));
            assertEquals(503, refused.status()); assertEquals("UNAVAILABLE", refused.code());
            assertTrue(fixture.readiness.isAcceptingWork());
            held.getFirst().cancel();
            assertEquals(64, fixture.bridge.snapshot().producers(), "cancel cannot certify upstream dispatch finished");
            held.removeFirst().close();
            await(() -> fixture.bridge.snapshot().producers() == 63);
            var replacement = fixture.bridge.reserve(fixture.channel);
            replacement.close();
            held.forEach(DownloadBridge.Producer::close);
            await(() -> fixture.bridge.snapshot().producers() == 0);
        }
    }

    @Test void retainedActualItemBlocksNextReadAndProducerSettlement() throws Exception {
        try (var fixture = new Fixture()) {
            var stage = new CompletableFuture<Void>();
            var reads = new AtomicInteger();
            var closed = new CountDownLatch(1);
            var source = new ByteArrayInputStream(new byte[16_384]) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    reads.incrementAndGet(); return super.read(bytes, offset, length);
                }
                @Override public void close() { closed.countDown(); stage.complete(null); }
            };
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.attach(result(source, 16_384, stage), source);
            var subscriber = new HoldingSubscriber();
            producer.subscribe(subscriber);
            subscriber.subscription.get(2, TimeUnit.SECONDS).request(2);
            var item = subscriber.items.poll(2, TimeUnit.SECONDS);
            assertNotNull(item);
            assertTrue(fixture.bridge.snapshot().arenaAllocations() > 0);
            fixture.channel.eventLoop().submit(() -> {}).sync();
            assertEquals(1, reads.get(), "demand cannot bypass retained allocation ownership");
            producer.cancel();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            await(() -> fixture.bridge.snapshot().readTasks() == 0 && fixture.bridge.snapshot().closeTasks() == 0);
            assertEquals(1, fixture.bridge.snapshot().producers());
            item.close();
            await(() -> fixture.bridge.snapshot().producers() == 0);
            assertEquals(0, fixture.bridge.snapshot().arenaAllocations());
            assertEquals(0, fixture.bridge.snapshot().arenaBytes());
            assertFalse(fixture.bridge.snapshot().failed());
        }
    }

    @Test void cancelledBeforeAttachClosesLateSourceButRequiresIndependentSettlement() throws Exception {
        try (var fixture = new Fixture()) {
            var stage = new CompletableFuture<Void>();
            var closes = new CountDownLatch(1);
            var source = new InputStream() {
                @Override public int read() { throw new AssertionError("cancelled source must not be read"); }
                @Override public void close() { closes.countDown(); }
            };
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.cancel();
            producer.attach(result(source, 1, stage), source);
            assertTrue(closes.await(2, TimeUnit.SECONDS));
            await(() -> fixture.bridge.snapshot().closeTasks() == 0);
            assertEquals(1, fixture.bridge.snapshot().producers());
            stage.complete(null);
            await(() -> fixture.bridge.snapshot().producers() == 0);
        }
    }

    @Test void blockedReadAndFailedCloseRemainChargedAfterCancellation() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1);
            var exit = new CountDownLatch(1);
            var source = new InputStream() {
                @Override public int read() throws IOException {
                    entered.countDown();
                    try { if (!exit.await(3, TimeUnit.SECONDS)) throw new AssertionError("missing test cleanup"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                    return -1;
                }
                @Override public void close() throws IOException { throw new IOException("synthetic close failure"); }
            };
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.attach(result(source, 1, new CompletableFuture<>()), source);
            var subscriber = new HoldingSubscriber(); producer.subscribe(subscriber);
            subscriber.subscription.get(2, TimeUnit.SECONDS).request(1);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                producer.cancel();
                await(() -> fixture.bridge.snapshot().closeTasks() == 0);
                assertEquals(1, fixture.bridge.snapshot().readTasks());
                assertEquals(1, fixture.bridge.snapshot().producers());
            } finally { exit.countDown(); }
            await(() -> fixture.bridge.snapshot().readTasks() == 0);
            assertEquals(1, fixture.bridge.snapshot().producers(), "failed close and unknown source cannot become reusable");
        }
    }

    @Test void queuedAndActiveReadTasksRequireTheirActualExitEvenAfterSourceWitnessCompletes() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(8);
            var exit = new CountDownLatch(1);
            var actualReads = new AtomicInteger();
            var producers = new ArrayList<DownloadBridge.Producer>();
            for (int index = 0; index < 9; index++) {
                var stage = new CompletableFuture<Void>();
                var source = new InputStream() {
                    @Override public int read() throws IOException {
                        actualReads.incrementAndGet(); entered.countDown();
                        try { if (!exit.await(3, TimeUnit.SECONDS)) throw new AssertionError("missing IO cleanup"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                        return -1;
                    }
                    @Override public void close() { stage.complete(null); }
                };
                var producer = fixture.bridge.reserve(fixture.channel);
                producer.attach(result(source, 1, stage), source);
                var subscriber = new HoldingSubscriber(); producer.subscribe(subscriber);
                subscriber.subscription.get(2, TimeUnit.SECONDS).request(1);
                producers.add(producer);
            }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                await(() -> fixture.bridge.snapshot().readTasks() == 9);
                producers.forEach(DownloadBridge.Producer::cancel);
                await(() -> fixture.bridge.snapshot().closeTasks() == 0);
                assertEquals(9, fixture.bridge.snapshot().producers());
                assertEquals(9, fixture.bridge.snapshot().readTasks(), "queued task cancellation is not Runnable exit");
            } finally { exit.countDown(); }
            await(() -> fixture.bridge.snapshot().producers() == 0);
            assertEquals(8, actualReads.get(), "cancelled queued task must exit without entering its source");
        }
    }

    @Test void callbacksUseCapturedEventLoopAndReentrantDemandNeverNestsEmission() throws Exception {
        try (var fixture = new Fixture()) {
            var stage = new CompletableFuture<Void>();
            var source = new ByteArrayInputStream(new byte[16_384]) {
                @Override public void close() { stage.complete(null); }
            };
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.attach(result(source, 16_384, stage), source);
            var complete = new CompletableFuture<Void>();
            var depth = new AtomicInteger(); var maximumDepth = new AtomicInteger(); var delivered = new AtomicInteger();
            producer.subscribe(new Subscriber<ReadBuffer>() {
                Subscription subscription;
                @Override public void onSubscribe(Subscription value) {
                    subscription = value; subscription.request(1);
                }
                @Override public void onNext(ReadBuffer item) {
                    assertTrue(fixture.channel.eventLoop().inEventLoop());
                    maximumDepth.accumulateAndGet(depth.incrementAndGet(), Math::max);
                    try { delivered.addAndGet(item.toArray().length); subscription.request(1); }
                    finally { depth.decrementAndGet(); }
                }
                @Override public void onError(Throwable failure) {
                    assertTrue(fixture.channel.eventLoop().inEventLoop()); complete.completeExceptionally(failure);
                }
                @Override public void onComplete() {
                    assertTrue(fixture.channel.eventLoop().inEventLoop()); complete.complete(null);
                }
            });
            complete.get(3, TimeUnit.SECONDS);
            await(() -> fixture.bridge.snapshot().producers() == 0);
            assertEquals(16_384, delivered.get()); assertEquals(1, maximumDepth.get());
            assertEquals(0, fixture.bridge.snapshot().callbacks());
        }
    }

    @Test void invalidDemandClosesWithoutReadingAndDuplicateAttachCannotReplaceSource() throws Exception {
        try (var fixture = new Fixture()) {
            var settled = new CompletableFuture<Void>(); var closes = new AtomicInteger();
            var source = new InputStream() {
                @Override public int read() { throw new AssertionError("invalid demand cannot authorize a source read"); }
                @Override public void close() { closes.incrementAndGet(); settled.complete(null); }
            };
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.attach(result(source, 1, settled), source);
            assertThrows(IllegalStateException.class, () -> producer.attach(result(source, 1, settled), source));
            var subscriber = new HoldingSubscriber(); producer.subscribe(subscriber);
            subscriber.subscription.get(2, TimeUnit.SECONDS).request(0);
            assertThrows(ExecutionException.class, () -> subscriber.terminal.get(2, TimeUnit.SECONDS));
            await(() -> fixture.bridge.snapshot().producers() == 0);
            assertEquals(1, closes.get());
            assertTrue(subscriber.items.isEmpty());
            assertFalse(fixture.bridge.snapshot().failed());
        }
    }

    @Test void sourceReadFailureZeroAndOverrunDeliverErrorAndSettleOnlyKnownOwners() throws Exception {
        try (var fixture = new Fixture()) {
            for (int mode = 0; mode < 3; mode++) {
                int selected = mode; var settled = new CompletableFuture<Void>();
                var source = new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("synthetic source failure"); }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        if (selected == 0) throw new IOException("synthetic source failure");
                        return selected == 1 ? 0 : 2;
                    }
                    @Override public void close() { settled.complete(null); }
                };
                var producer = fixture.bridge.reserve(fixture.channel);
                producer.attach(result(source, 1, settled), source);
                var subscriber = new HoldingSubscriber(); producer.subscribe(subscriber);
                subscriber.subscription.get(2, TimeUnit.SECONDS).request(1);
                assertThrows(ExecutionException.class, () -> subscriber.terminal.get(2, TimeUnit.SECONDS));
                await(() -> fixture.bridge.snapshot().producers() == 0);
                assertTrue(subscriber.items.isEmpty());
                assertEquals(0, fixture.bridge.snapshot().arenaAllocations());
                assertFalse(fixture.bridge.snapshot().failed());
            }
        }
    }

    @Test void throwingSubscriberCannotBeMistakenForItemConsumption() throws Exception {
        try (var fixture = new Fixture()) {
            var stage = new CompletableFuture<Void>();
            var source = new ByteArrayInputStream(new byte[] {1}) {
                @Override public void close() { stage.complete(null); }
            };
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.attach(result(source, 1, stage), source);
            var retained = new CompletableFuture<ReadBuffer>();
            producer.subscribe(new Subscriber<ReadBuffer>() {
                @Override public void onSubscribe(Subscription value) { value.request(1); }
                @Override public void onNext(ReadBuffer item) {
                    retained.complete(item); throw new IllegalStateException("synthetic downstream failure");
                }
                @Override public void onError(Throwable failure) { }
                @Override public void onComplete() { fail("no success after subscriber failure"); }
            });
            var item = retained.get(2, TimeUnit.SECONDS);
            try {
                await(() -> fixture.bridge.snapshot().failed());
                assertEquals(1, fixture.bridge.snapshot().producers());
                assertTrue(fixture.bridge.snapshot().arenaAllocations() > 0);
                assertFalse(fixture.readiness.isAcceptingWork());
            } finally { item.close(); }
            await(() -> fixture.bridge.snapshot().arenaAllocations() == 0);
            assertEquals(1, fixture.bridge.snapshot().producers(), "unknown callback cannot be repaired by later item cleanup");
        }
    }

    @Test void requestInsideBlockedOnSubscribeCannotStartReadOrOverlapDataCallbacks() throws Exception {
        try (var fixture = new Fixture(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var subscribed = new CountDownLatch(1); var release = new CountDownLatch(1);
            var stage = new CompletableFuture<Void>(); var reads = new AtomicInteger();
            var deliveries = new AtomicInteger(); var overlaps = new AtomicInteger();
            var terminal = new CompletableFuture<Void>();
            var source = new ByteArrayInputStream(new byte[] {42}) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    reads.incrementAndGet(); return super.read(bytes, offset, length);
                }
                @Override public void close() { stage.complete(null); }
            };
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.attach(result(source, 1, stage), source);
            var task = executor.submit(() -> producer.subscribe(new Subscriber<ReadBuffer>() {
                @Override public void onSubscribe(Subscription subscription) {
                    subscription.request(1); subscribed.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS), "missing subscription release"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                }
                @Override public void onNext(ReadBuffer item) {
                    if (release.getCount() != 0) overlaps.incrementAndGet();
                    assertTrue(fixture.channel.eventLoop().inEventLoop());
                    assertArrayEquals(new byte[] {42}, item.toArray()); deliveries.incrementAndGet();
                }
                @Override public void onError(Throwable failure) { terminal.completeExceptionally(failure); }
                @Override public void onComplete() {
                    if (release.getCount() != 0) overlaps.incrementAndGet();
                    terminal.complete(null);
                }
            }));
            try {
                assertTrue(subscribed.await(2, TimeUnit.SECONDS));
                fixture.channel.eventLoop().submit(() -> {}).sync();
                assertEquals(0, reads.get(), "demand cannot enter the source before onSubscribe returns");
                assertEquals(0, deliveries.get());
                assertEquals(0, fixture.bridge.snapshot().readTasks());
                assertEquals(1, fixture.bridge.snapshot().callbacks(), "the active subscription callback remains owned");
                assertFalse(terminal.isDone());
            } finally { release.countDown(); }
            task.get(2, TimeUnit.SECONDS); terminal.get(2, TimeUnit.SECONDS);
            await(() -> fixture.bridge.snapshot().producers() == 0);
            assertEquals(1, deliveries.get()); assertEquals(0, overlaps.get());
        }
    }

    @Test void bridgeShutdownStillClosesItsAlreadyReservedLateAttachedSource() throws Exception {
        try (var fixture = new Fixture()) {
            var producer = fixture.bridge.reserve(fixture.channel);
            fixture.bridge.close();
            assertTrue(fixture.bridge.snapshot().closed());
            assertEquals(1, fixture.bridge.snapshot().producers());
            assertThrows(org.documentknowledge.browserbff.application.BffFailure.class,
                () -> fixture.bridge.reserve(fixture.channel));
            var stage = new CompletableFuture<Void>(); var closeEntered = new CountDownLatch(1);
            var closes = new AtomicInteger();
            var source = new InputStream() {
                @Override public int read() { throw new AssertionError("shutdown cannot start a late source read"); }
                @Override public void close() { closes.incrementAndGet(); closeEntered.countDown(); }
            };
            producer.attach(result(source, 1, stage), source);
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS), "owned late attachment still requires asynchronous close");
            await(() -> fixture.bridge.snapshot().closeTasks() == 0);
            assertEquals(1, closes.get());
            assertEquals(1, fixture.bridge.snapshot().producers(), "close return is not the independent source witness");
            stage.complete(null);
            await(() -> fixture.bridge.snapshot().producers() == 0);
            assertFalse(fixture.bridge.snapshot().failed());
            assertEquals(0, fixture.bridge.snapshot().readTasks());
            assertEquals(0, fixture.bridge.snapshot().closeTasks());
        }
    }

    static UploadGateway.StreamResult result(InputStream source, long length, CompletionStage<Void> stage) {
        return new UploadGateway.StreamResult(200, "application/pdf", "synthetic.pdf", length, null, source, stage);
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Actual bridge ownership did not settle as expected");
    }

    static final class HoldingSubscriber implements Subscriber<ReadBuffer> {
        final CompletableFuture<Subscription> subscription = new CompletableFuture<>();
        final LinkedBlockingQueue<ReadBuffer> items = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> terminal = new CompletableFuture<>();
        @Override public void onSubscribe(Subscription value) { subscription.complete(value); }
        @Override public void onNext(ReadBuffer value) { items.add(value); }
        @Override public void onError(Throwable failure) { terminal.completeExceptionally(failure); }
        @Override public void onComplete() { terminal.complete(null); }
    }

    static final class Fixture implements AutoCloseable {
        final Readiness readiness = new Readiness();
        final DownloadBridge bridge = new DownloadBridge(readiness);
        final NioEventLoopGroup group = new NioEventLoopGroup(1);
        final ServerSocket listener;
        final Socket peer;
        final Channel channel;
        Fixture() throws Exception {
            listener = new ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
            channel = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter()).connect(listener.getLocalSocketAddress()).sync().channel();
            peer = listener.accept();
        }
        @Override public void close() throws Exception {
            bridge.close(); channel.close().sync(); peer.close(); listener.close();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }
    }
}
