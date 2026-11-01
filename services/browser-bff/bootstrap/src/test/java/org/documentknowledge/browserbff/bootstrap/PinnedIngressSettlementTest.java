package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.stream.BaseSharedBuffer;
import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.body.stream.BufferConsumer;
import io.micronaut.http.netty.body.NettyByteBodyFactory;
import io.micronaut.http.netty.body.StreamingNettyByteBody;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PinnedIngressSettlementTest {
    @Test void unsupportedBodiesNullRootsAndDeniedAccessFailClosed() throws Exception {
        try (var fixture = new Fixture()) {
            var profile = new PinnedIngressSettlement();
            var unknown = (ByteBody) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {io.micronaut.http.body.CloseableByteBody.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Unknown body must not be consumed");
                });
            assertThrows(IllegalStateException.class, () -> profile.capture(unknown, fixture.channel.eventLoop()));
            try (var body = new StreamingNettyByteBody(fixture.root())) {
                var field = io.micronaut.http.body.stream.BaseStreamingByteBody.class.getDeclaredField("sharedBuffer");
                field.setAccessible(true);
                Object root = field.get(body);
                try {
                    field.set(body, null); // TEST-ONLY malformed pinned object.
                    assertThrows(IllegalStateException.class, () -> profile.capture(body, fixture.channel.eventLoop()));
                } finally { field.set(body, root); }
            }
            var denied = String.class.getDeclaredField("value");
            assertThrows(IllegalAccessException.class, () -> PinnedIngressSettlement.validateField(
                denied, String.class, byte[].class, Modifier.PRIVATE | Modifier.FINAL));
        }
    }

    @Test void wrongArtifactHashDirectoryAndForeignLoaderAreRejected() throws Exception {
        var check = PinnedIngressSettlement.class.getDeclaredMethod("artifactMatches", Class.class, String.class);
        check.setAccessible(true);
        assertFalse((Boolean) check.invoke(null, BaseSharedBuffer.class, "0".repeat(64)));
        assertFalse((Boolean) check.invoke(null, getClass(), PinnedIngressSettlement.HTTP_SHA256));
        assertFalse((Boolean) check.invoke(null, String.class, PinnedIngressSettlement.HTTP_SHA256));
    }

    @Test void thrownQueuedDiscardCannotReleaseTheStillRetainedRoot() throws Exception {
        try (var fixture = new Fixture()) {
            var root = fixture.root();
            var body = new StreamingNettyByteBody(root);
            var settled = new PinnedIngressSettlement().capture(body, fixture.channel.eventLoop());
            var bufferField = BaseSharedBuffer.class.getDeclaredField("buffer");
            bufferField.setAccessible(true);
            fixture.channel.eventLoop().submit(() -> {
                root.add(fixture.factory.readBufferFactory().adapt(new byte[8192]));
                return null;
            }).sync();
            @SuppressWarnings("unchecked")
            var retained = (List<io.micronaut.core.io.buffer.ReadBuffer>) bufferField.get(root);
            var throwing = new java.util.AbstractList<io.micronaut.core.io.buffer.ReadBuffer>() {
                @Override public io.micronaut.core.io.buffer.ReadBuffer get(int index) {
                    throw new IllegalStateException("TEST_QUEUED_DISCARD_FAILURE");
                }
                @Override public int size() { return retained.size(); }
            };
            fixture.channel.eventLoop().submit(() -> {
                bufferField.set(root, throwing);
                return null;
            }).sync();
            body.close(); // Actual pinned subscribe0/discard task throws on its event loop.
            assertFalse(fixture.channel.eventLoop().submit(settled::getAsBoolean).get(3, TimeUnit.SECONDS));
            fixture.channel.eventLoop().submit(() -> {
                bufferField.set(root, retained);
                root.error(new java.io.IOException("TEST_CLEANUP"));
                return null;
            }).sync();
        }
    }

    @Test void primaryTransferSettlesOnlyRootWhileSubscriberStillOwnsBytes() throws Exception {
        try (var fixture = new Fixture()) {
            var root = fixture.root();
            var body = new StreamingNettyByteBody(root);
            var settled = new PinnedIngressSettlement().capture(body, fixture.channel.eventLoop());
            var held = new java.util.concurrent.atomic.AtomicReference<io.micronaut.core.io.buffer.ReadBuffer>();
            fixture.channel.eventLoop().submit(() -> {
                root.add(fixture.factory.readBufferFactory().adapt(new byte[8192]));
                reactor.core.publisher.Flux.from(body.toReadBufferPublisher()).subscribe(held::set);
                body.close();
                assertTrue(settled.getAsBoolean());
                assertNotNull(held.get());
                assertEquals(8192, held.get().readable(), "Subscriber bytes remain separately owned");
                held.get().close();
                root.complete();
            }).sync();
        }
    }

    @Test void reservedRootBytesRemainChargedUntilActualSameLoopDiscard() throws Exception {
        try (var fixture = new Fixture()) {
            var profile = new PinnedIngressSettlement();
            var root = fixture.root();
            var body = new StreamingNettyByteBody(root);
            var settled = profile.capture(body, fixture.channel.eventLoop());
            assertFalse(settled.getAsBoolean(), "Off-loop observation cannot establish settlement");
            fixture.channel.eventLoop().submit(() -> {
                assertFalse(settled.getAsBoolean());
                root.add(fixture.factory.readBufferFactory().adapt(new byte[8192]));
                assertFalse(settled.getAsBoolean());
                body.close();
                assertTrue(settled.getAsBoolean());
            }).sync();
        }
    }

    @Test void queuedOffLoopAndForcedDelaySplitCleanupNeedsTheOrderedBarrier() throws Exception {
        try (var fixture = new Fixture()) {
            var profile = new PinnedIngressSettlement();
            var body = new StreamingNettyByteBody(fixture.root());
            var settled = profile.capture(body, fixture.channel.eventLoop());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var paused = fixture.channel.eventLoop().submit(() -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                var split = body.split(ByteBody.SplitBackpressureMode.SLOWEST);
                body.close();
                // Registration order: reserve, original disposal, still-reserved observation,
                // force-delayed split disposal, final settled observation.
                var stillReserved = fixture.channel.eventLoop().submit(settled::getAsBoolean);
                split.close();
                var afterCleanup = fixture.channel.eventLoop().submit(settled::getAsBoolean);
                assertFalse(afterCleanup.isDone());
                release.countDown();
                paused.sync();
                assertFalse(stillReserved.get(3, TimeUnit.SECONDS));
                assertTrue(afterCleanup.get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    @Test void failedOrUnsettledRootMutationNeverBecomesSuccessfulCleanup() throws Exception {
        try (var fixture = new Fixture()) {
            var profile = new PinnedIngressSettlement();
            for (String name : List.of("reserved", "buffer", "fullSubscribers", "working")) {
                var root = fixture.root();
                var body = new StreamingNettyByteBody(root);
                var settled = profile.capture(body, fixture.channel.eventLoop());
                fixture.channel.eventLoop().submit(() -> {
                    body.close();
                    assertTrue(settled.getAsBoolean());
                    var field = BaseSharedBuffer.class.getDeclaredField(name);
                    field.setAccessible(true);
                    Object prior = field.get(root);
                    Object unsettled = switch (name) {
                        case "reserved" -> 1;
                        case "working" -> true;
                        default -> List.of();
                    };
                    // TEST-ONLY mutation simulates the state left by failed/deferred work.
                    field.set(root, unsettled);
                    assertFalse(settled.getAsBoolean(), name);
                    field.set(root, prior);
                    assertTrue(settled.getAsBoolean());
                    return null;
                }).sync();
            }
        }
    }

    @Test void exactBodyLoopAndMetadataAreMandatory() throws Exception {
        try (var fixture = new Fixture(); var foreign = new Fixture()) {
            var profile = new PinnedIngressSettlement();
            try (var body = new StreamingNettyByteBody(fixture.root())) {
                assertThrows(IllegalStateException.class, () -> profile.capture(body, foreign.channel.eventLoop()));
            }
            try (var available = fixture.factory.adapt(new byte[1])) {
                var settled = profile.capture(available, fixture.channel.eventLoop());
                available.close();
                assertFalse(settled.getAsBoolean());
                assertTrue(fixture.channel.eventLoop().submit(settled::getAsBoolean).get(3, TimeUnit.SECONDS));
            }
            var field = BaseSharedBuffer.class.getDeclaredField("reserved");
            assertThrows(IllegalAccessException.class, () -> PinnedIngressSettlement.validateField(
                field, String.class, int.class, Modifier.PRIVATE));
            assertThrows(IllegalAccessException.class, () -> PinnedIngressSettlement.validateField(
                field, BaseSharedBuffer.class, long.class, Modifier.PRIVATE));
            assertThrows(IllegalAccessException.class, () -> PinnedIngressSettlement.validateField(
                field, BaseSharedBuffer.class, int.class, Modifier.PUBLIC));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final NioEventLoopGroup group = new NioEventLoopGroup(1);
        final io.netty.channel.Channel channel;
        final NettyByteBodyFactory factory;
        Fixture() throws Exception {
            channel = group.register(new NioSocketChannel()).sync().channel();
            factory = new NettyByteBodyFactory(channel);
        }
        StreamingNettyByteBody.SharedBuffer root() {
            return factory.createStreamingBuffer(new BodySizeLimits(100000000, 131072),
                new BufferConsumer.Upstream() {
                    @Override public void onBytesConsumed(long bytes) { }
                });
        }
        @Override public void close() throws Exception {
            channel.close().sync();
            group.shutdownGracefully(0, 3, TimeUnit.SECONDS).sync();
        }
    }
}
