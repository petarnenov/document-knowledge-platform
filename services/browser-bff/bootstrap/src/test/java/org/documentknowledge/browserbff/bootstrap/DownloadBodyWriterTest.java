package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.type.Argument;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.buffer.ReadBuffer;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.netty.body.NettyByteBodyFactory;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.micronaut.http.server.HttpServerConfiguration;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.adapters.inbound.DownloadDelivery;
import org.documentknowledge.browserbff.application.UploadGateway;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import static org.junit.jupiter.api.Assertions.*;

class DownloadBodyWriterTest {
    private static final Argument<DownloadDelivery.Body> TYPE = Argument.of(DownloadDelivery.Body.class);

    @Test void fakeReservationCannotReceiveCancellationOrFallbackWrites() throws Exception {
        try (var fixture = new DownloadBridgeTest.Fixture()) {
            var writer = new DownloadBodyWriter(fixture.bridge);
            var closes = new AtomicInteger();
            DownloadDelivery.Reservation fake = new DownloadDelivery.Reservation() {
                @Override public DownloadDelivery.Body attach(UploadGateway.StreamResult result, InputStream source) {
                    fail("writer cannot attach an arbitrary owner"); return null;
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
            var body = new DownloadDelivery.Body(1, fake);
            var output = new ByteArrayOutputStream();
            assertUnavailable(() -> writer.writePiece(defaultFactory(), HttpRequest.GET("/"),
                HttpResponse.ok(), TYPE, MediaType.APPLICATION_PDF_TYPE, body));
            assertUnavailable(() -> writer.writeTo(TYPE, MediaType.APPLICATION_PDF_TYPE, body,
                HttpResponse.ok().getHeaders(), output));
            assertEquals(0, closes.get(), "a foreign reservation is never ours to close");
            assertEquals(0, output.size());
            assertEquals(0, fixture.bridge.snapshot().producers());
        }
    }

    @Test void foreignBridgeProducerRemainsUsableAfterBothRejectedWriterPaths() throws Exception {
        try (var own = new DownloadBridgeTest.Fixture(); var foreign = new DownloadBridgeTest.Fixture()) {
            var writer = new DownloadBodyWriter(own.bridge);
            var source = new UnreadSource();
            var producer = foreign.bridge.reserve(foreign.channel);
            var body = producer.attach(DownloadBridgeTest.result(source, 1, source.settled), source);
            try {
                assertUnavailable(() -> writer.writePiece(defaultFactory(), HttpRequest.GET("/"),
                    HttpResponse.ok(), TYPE, MediaType.APPLICATION_PDF_TYPE, body));
                var output = new ByteArrayOutputStream();
                assertUnavailable(() -> writer.writeTo(TYPE, MediaType.APPLICATION_PDF_TYPE, body,
                    HttpResponse.ok().getHeaders(), output));
                foreign.channel.eventLoop().submit(() -> {}).sync();
                assertEquals(0, source.closes.get());
                assertEquals(0, output.size());
                assertDoesNotThrow(producer::claim, "rejection must leave the foreign producer uncancelled and unclaimed");
                assertEquals(1, foreign.bridge.snapshot().producers());
                assertEquals(0, own.bridge.snapshot().producers());
            } finally { producer.cancel(); source.settled.complete(null); }
            DownloadBridgeTest.await(() -> foreign.bridge.snapshot().producers() == 0);
        }
    }

    @Test void defaultFactoryRejectionCancelsOwnSourceButDoesNotCertifySourceSettlement() throws Exception {
        try (var fixture = new DownloadBridgeTest.Fixture()) {
            var source = new UnreadSource();
            var producer = fixture.bridge.reserve(fixture.channel);
            var body = producer.attach(DownloadBridgeTest.result(source, 1, source.settled), source);
            assertUnavailable(() -> new DownloadBodyWriter(fixture.bridge).writePiece(defaultFactory(),
                HttpRequest.GET("/"), HttpResponse.ok(), TYPE, MediaType.APPLICATION_PDF_TYPE, body));
            assertCancelledButCharged(fixture, source);
            source.settled.complete(null);
            DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 0);
        }
    }

    @Test void actualNettyFactoryDoesNotAuthorizeUnsupportedRequestOrBorrowItsChannel() throws Exception {
        try (var fixture = new DownloadBridgeTest.Fixture(); var other = new DownloadBridgeTest.Fixture()) {
            var source = new UnreadSource();
            var producer = fixture.bridge.reserve(fixture.channel);
            var body = producer.attach(DownloadBridgeTest.result(source, 1, source.settled), source);
            // Actual pinned implementation and second real NIO loop, never a mocked factory/request.
            var factory = new NettyByteBodyFactory(other.channel);
            assertUnavailable(() -> new DownloadBodyWriter(fixture.bridge).writePiece(factory,
                HttpRequest.GET("/"), HttpResponse.ok(), TYPE, MediaType.APPLICATION_PDF_TYPE, body));
            assertCancelledButCharged(fixture, source);
            assertEquals(0, other.bridge.snapshot().producers());
            assertTrue(other.channel.isActive());
            source.settled.complete(null);
            DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 0);
        }
    }

    @Test void outputStreamFallbackCancelsOnlyOwnedProducerWithoutWritingOrReading() throws Exception {
        try (var fixture = new DownloadBridgeTest.Fixture()) {
            var source = new UnreadSource();
            var producer = fixture.bridge.reserve(fixture.channel);
            var body = producer.attach(DownloadBridgeTest.result(source, 1, source.settled), source);
            var output = new ByteArrayOutputStream();
            assertUnavailable(() -> new DownloadBodyWriter(fixture.bridge).writeTo(TYPE,
                MediaType.APPLICATION_PDF_TYPE, body, HttpResponse.ok().getHeaders(), output));
            assertEquals(0, output.size());
            assertCancelledButCharged(fixture, source);
            source.settled.complete(null);
            DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 0);
        }
    }

    @Test void actualNettyAdapterDiscardBeforeFirstSubscriptionCancelsOwnedProducerWithoutReading() throws Exception {
        try (var fixture = new DownloadBridgeTest.Fixture()) {
            var source = new UnreadSource();
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.attach(DownloadBridgeTest.result(source, 1, source.settled), source);
            producer.claim();
            var discards = new AtomicInteger();
            // Direct pinned adapter contract proof; this does not simulate a successful HTTP writer request.
            try (var body = new NettyByteBodyFactory(fixture.channel).adapt(producer,
                    new BodySizeLimits(100_000_000, 65536), HttpResponse.ok().getHeaders(),
                    () -> { discards.incrementAndGet(); producer.cancel(); })) {
                assertEquals(0, fixture.bridge.snapshot().readTasks());
                assertEquals(0, source.closes.get());
                body.allowDiscard();
                assertCancelledButCharged(fixture, source);
                assertTrue(discards.get() > 0, "the actual adapter must invoke its pre-subscription discard callback");
            }
            source.settled.complete(null);
            DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 0);
            assertEquals(1, source.closes.get());
        }
    }

    @Test void differentLoopPassedFactoryIsNormalizedToActualRequestLoopAndDeliversExactContent() throws Exception {
        try (var fixture = new DownloadBridgeTest.Fixture(); var other = new DownloadBridgeTest.Fixture();
                var requestBody = NettyByteBodyFactory.empty()) {
            byte[] expected = {3, 1, 4, 1, 5};
            var settled = new CompletableFuture<Void>();
            var source = new ByteArrayInputStream(expected) {
                @Override public void close() { settled.complete(null); }
            };
            var producer = fixture.bridge.reserve(fixture.channel);
            var body = producer.attach(DownloadBridgeTest.result(source, expected.length, settled), source);
            var request = request(fixture, requestBody);
            var response = HttpResponse.ok().header("Content-Length", Integer.toString(expected.length));
            var received = new ByteArrayOutputStream();
            var terminal = new CompletableFuture<Void>();
            var callbacks = new AtomicInteger();
            assertNotSame(fixture.channel.eventLoop(), other.channel.eventLoop());
            try (var adapted = new DownloadBodyWriter(fixture.bridge).writePiece(
                    new NettyByteBodyFactory(other.channel), request, response, TYPE,
                    MediaType.APPLICATION_PDF_TYPE, body)) {
                adapted.toReadBufferPublisher().subscribe(new Subscriber<ReadBuffer>() {
                    @Override public void onSubscribe(Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                    @Override public void onNext(ReadBuffer item) {
                        try {
                            assertTrue(fixture.channel.eventLoop().inEventLoop(), "delivery must use the actual request loop");
                            assertFalse(other.channel.eventLoop().inEventLoop());
                            received.writeBytes(item.toArray()); callbacks.incrementAndGet();
                        } catch (Throwable failure) { terminal.completeExceptionally(failure); }
                        finally { item.close(); }
                    }
                    @Override public void onError(Throwable failure) { terminal.completeExceptionally(failure); }
                    @Override public void onComplete() {
                        try {
                            assertTrue(fixture.channel.eventLoop().inEventLoop());
                            terminal.complete(null);
                        } catch (Throwable failure) { terminal.completeExceptionally(failure); }
                    }
                });
                terminal.get(3, TimeUnit.SECONDS);
                assertArrayEquals(expected, received.toByteArray());
                assertTrue(callbacks.get() > 0);
            } finally { producer.cancel(); }
            DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 0);
            assertTrue(settled.isDone());
            assertEquals(0, fixture.bridge.snapshot().arenaAllocations());
            assertEquals(0, fixture.bridge.snapshot().readTasks());
            assertEquals(0, fixture.bridge.snapshot().callbacks());
            assertEquals(0, other.bridge.snapshot().producers());
            assertFalse(fixture.bridge.snapshot().failed());
        }
    }

    @Test void realRequestRejectsForgedBodyIdentityAndClosesOnlyTheActualOwner() throws Exception {
        try (var fixture = new DownloadBridgeTest.Fixture(); var requestBody = NettyByteBodyFactory.empty()) {
            var source = new UnreadSource();
            var producer = fixture.bridge.reserve(fixture.channel);
            producer.attach(DownloadBridgeTest.result(source, 1, source.settled), source);
            var forged = new DownloadDelivery.Body(1, producer);
            assertUnavailable(() -> new DownloadBodyWriter(fixture.bridge).writePiece(
                new NettyByteBodyFactory(fixture.channel), request(fixture, requestBody),
                HttpResponse.ok(), TYPE, MediaType.APPLICATION_PDF_TYPE, forged));
            assertCancelledButCharged(fixture, source);
            source.settled.complete(null);
            DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 0);
        }
    }

    @Test void realWriterUnsubscribedBodyDiscardCancelsSourceAndDuplicateClaimCannotCreateAnotherBody() throws Exception {
        try (var fixture = new DownloadBridgeTest.Fixture(); var requestBody = NettyByteBodyFactory.empty()) {
            var writer = new DownloadBodyWriter(fixture.bridge);
            var source = new UnreadSource();
            var producer = fixture.bridge.reserve(fixture.channel);
            var body = producer.attach(DownloadBridgeTest.result(source, 1, source.settled), source);
            var request = request(fixture, requestBody);
            var factory = new NettyByteBodyFactory(fixture.channel);
            try (var adapted = writer.writePiece(factory, request, HttpResponse.ok(), TYPE,
                    MediaType.APPLICATION_PDF_TYPE, body)) {
                assertEquals(0, source.reads.get());
                adapted.allowDiscard();
                assertCancelledButCharged(fixture, source);
                assertUnavailable(() -> writer.writePiece(factory, request, HttpResponse.ok(), TYPE,
                    MediaType.APPLICATION_PDF_TYPE, body));
            }
            source.settled.complete(null);
            DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 0);
            assertEquals(1, source.closes.get());
        }
    }

    private static NettyHttpRequest<?> request(DownloadBridgeTest.Fixture fixture,
            io.micronaut.http.body.CloseableByteBody body) {
        return new NettyHttpRequest<>(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"),
            body, fixture.channel.pipeline().firstContext(), ConversionService.SHARED, new HttpServerConfiguration());
    }

    private static ByteBodyFactory defaultFactory() {
        return ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE);
    }

    private static void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(CodecException.class, action);
        assertEquals("BFF_DOWNLOAD_PROFILE_UNAVAILABLE", failure.getMessage());
    }

    private static void assertCancelledButCharged(DownloadBridgeTest.Fixture fixture, UnreadSource source)
            throws Exception {
        assertTrue(source.closed.await(2, TimeUnit.SECONDS), "owned cancellation must actually close the source");
        DownloadBridgeTest.await(() -> fixture.bridge.snapshot().closeTasks() == 0);
        assertEquals(1, source.closes.get());
        assertEquals(0, source.reads.get());
        assertEquals(1, fixture.bridge.snapshot().producers(), "close is not the independent source settlement witness");
        assertEquals(0, fixture.bridge.snapshot().readTasks());
        assertEquals(0, fixture.bridge.snapshot().arenaAllocations());
        assertFalse(fixture.bridge.snapshot().failed());
    }

    private static final class UnreadSource extends InputStream {
        final CompletableFuture<Void> settled = new CompletableFuture<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        @Override public int read() {
            reads.incrementAndGet(); throw new AssertionError("rejected or discarded body cannot start source IO");
        }
        @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
    }
}
