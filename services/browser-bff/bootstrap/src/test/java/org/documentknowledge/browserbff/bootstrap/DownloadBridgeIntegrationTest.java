package org.documentknowledge.browserbff.bootstrap;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownloadBridgeIntegrationTest {
    static final String CONTENT = UploadHttpTest.BASE + "/" + UploadHttpTest.ID
        + "/versions/" + UploadHttpTest.ID + "/content";

    @Test void authenticatedProductionWriterDeliversExactLargeBytesAndSettlesAllOwners() throws Exception {
        int length = 4 * 1024 * 1024;
        try (var fixture = new Fixture(length, false); var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(fixture.server.getURI().resolve(CONTENT))
                .timeout(Duration.ofSeconds(10)).header("Cookie", fixture.cookie)
                .header("Accept", "application/pdf").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertEquals(Integer.toString(length), response.headers().firstValue("Content-Length").orElseThrow());
            assertEquals("application/pdf", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            var digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (var body = response.body()) {
                byte[] chunk = new byte[8192];
                for (int read; (read = body.read(chunk)) >= 0;) { digest.update(chunk, 0, read); count += read; }
            }
            assertEquals(length, count);
            var expected = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[8192]; Arrays.fill(chunk, (byte) 137);
            for (int written = 0; written < length; written += chunk.length) expected.update(chunk);
            assertArrayEquals(expected.digest(), digest.digest());
            fixture.awaitSettled();
            assertFalse(fixture.bridge.snapshot().failed());
        }
    }

    @Test void slowAuthenticatedReaderBackpressuresAndAbortSettlesActualOwners() throws Exception {
        int length = 32 * 1024 * 1024;
        try (var fixture = new Fixture(length, false); var socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress("127.0.0.1", fixture.server.getPort()));
            send(socket, fixture.cookie);
            DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 1
                && fixture.produced.get() > 0);
            assertTrue(fixture.produced.get() < length, "real socket backpressure must prevent reading the entire object");
            var snapshot = fixture.bridge.snapshot();
            assertTrue(snapshot.arenaBytes() <= BridgeAllocationPool.APPLICATION_BYTES);
            assertTrue(snapshot.arenaAllocations() <= BridgeAllocationPool.APPLICATION_ALLOCATIONS);
            assertTrue(snapshot.readTasks() <= 1);
            assertFalse(snapshot.failed());
            socket.close();
            fixture.awaitSettled();
            assertFalse(fixture.bridge.snapshot().failed(), "routine disconnect must not poison readiness");
        }
    }

    @Test void disconnectBeforeUpstreamHeadersRetainsReservationUntilLateSourceCloses() throws Exception {
        try (var fixture = new Fixture(8192, true)) {
            try (var socket = new Socket("127.0.0.1", fixture.server.getPort())) {
                send(socket, fixture.cookie);
                assertTrue(fixture.upstreamEntered.await(2, TimeUnit.SECONDS));
                assertEquals(1, fixture.bridge.snapshot().producers());
            }
            // Upstream dispatch is still running: root/channel cancellation cannot certify no source.
            assertEquals(1, fixture.bridge.snapshot().producers());
            fixture.headersAllowed.countDown();
            fixture.awaitSettled();
            assertFalse(fixture.bridge.snapshot().failed());
        }
    }

    static void send(Socket socket, String cookie) throws IOException {
        IngressChannelPolicyTest.send(socket, "GET " + CONTENT + " HTTP/1.1\r\nHost: localhost\r\nCookie: "
            + cookie + "\r\nAccept: application/pdf\r\n\r\n");
    }

    @Test void sessionRevokedDuringUpstreamDispatchDeniesContentAndClosesAttachedSource() throws Exception {
        try (var fixture = new Fixture(8192, false); var client = HttpClient.newHttpClient()) {
            fixture.beforeHeaders.set(fixture.session::clear);
            var response = client.send(HttpRequest.newBuilder(fixture.server.getURI().resolve(CONTENT))
                .timeout(Duration.ofSeconds(5)).header("Cookie", fixture.cookie)
                .header("Accept", "application/pdf").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertEquals("{\"code\":\"UNAUTHENTICATED\"}", response.body());
            fixture.awaitSettled();
            assertFalse(fixture.bridge.snapshot().failed());
        }
    }

    @Test void realRouteRefusesFullSharedProducerBudgetBeforeUpstreamAndRecovers() throws Exception {
        try (var fixture = new Fixture(8192, false);
             var channelFixture = new DownloadBridgeTest.Fixture();
             var client = HttpClient.newHttpClient()) {
            // These are genuine reservations in the production singleton, not 64 simultaneous HTTP
            // exchanges: the independent C32 connection bound intentionally remains unchanged.
            var held = new ArrayList<DownloadBridge.Producer>();
            try {
                for (int index = 0; index < 64; index++) held.add(fixture.bridge.reserve(channelFixture.channel));
                assertEquals(64, fixture.bridge.snapshot().producers());
                int before = fixture.requests.get();
                var request = HttpRequest.newBuilder(fixture.server.getURI().resolve(CONTENT))
                    .timeout(Duration.ofSeconds(5)).header("Cookie", fixture.cookie)
                    .header("Accept", "application/pdf").GET().build();
                var refused = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(503, refused.statusCode());
                assertEquals("{\"code\":\"UNAVAILABLE\"}", refused.body());
                assertEquals(before, fixture.requests.get(), "producer admission must precede trusted upstream dispatch");
                assertEquals(64, fixture.bridge.snapshot().producers());
                assertTrue(fixture.server.getApplicationContext()
                    .getBean(org.documentknowledge.browserbff.application.Readiness.class).isAcceptingWork());
                assertFalse(fixture.bridge.snapshot().failed());

                held.removeFirst().close();
                DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 63);
                var recovered = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, recovered.statusCode());
                byte[] expected = new byte[8192]; Arrays.fill(expected, (byte) 137);
                assertArrayEquals(expected, recovered.body());
                assertEquals(before + 1, fixture.requests.get());
                DownloadBridgeTest.await(() -> fixture.bridge.snapshot().producers() == 63
                    && fixture.bridge.snapshot().readTasks() == 0 && fixture.bridge.snapshot().closeTasks() == 0
                    && fixture.bridge.snapshot().callbacks() == 0 && fixture.bridge.snapshot().arenaAllocations() == 0);
                assertFalse(fixture.bridge.snapshot().failed());
            } finally { held.forEach(DownloadBridge.Producer::close); }
            fixture.awaitSettled();
        }
    }

    static final class Fixture implements AutoCloseable {
        final CountDownLatch upstreamEntered = new CountDownLatch(1);
        final CountDownLatch headersAllowed;
        final AtomicLong produced = new AtomicLong();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Runnable> beforeHeaders = new AtomicReference<>(() -> {});
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer upstream;
        final EmbeddedServer server;
        final DownloadBridge bridge;
        final String cookie;
        final io.micronaut.session.Session session;

        Fixture(int length, boolean holdHeaders) throws Exception {
            headersAllowed = new CountDownLatch(holdHeaders ? 1 : 0);
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
            upstream.setExecutor(executor);
            upstream.createContext("/api/v1/documents", exchange -> {
                try {
                    requests.incrementAndGet();
                    assertEquals("Bearer synthetic-access", exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals("tenant-a", exchange.getRequestHeaders().getFirst("X-DKP-Tenant"));
                    assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
                    upstreamEntered.countDown();
                    assertTrue(headersAllowed.await(3, TimeUnit.SECONDS), "missing controlled upstream release");
                    beforeHeaders.get().run();
                    var headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", "application/pdf");
                    headers.set("Content-Disposition", "attachment; filename=\"" + UploadHttpTest.ID + ".pdf\"");
                    headers.set("Cache-Control", "no-store");
                    headers.set("X-Content-Type-Options", "nosniff");
                    headers.set("Accept-Ranges", "bytes");
                    exchange.sendResponseHeaders(200, length);
                    byte[] chunk = new byte[8192]; Arrays.fill(chunk, (byte) 137);
                    try (var output = exchange.getResponseBody()) {
                        for (int written = 0; written < length; written += chunk.length) {
                            int size = Math.min(chunk.length, length - written);
                            output.write(chunk, 0, size); produced.addAndGet(size);
                        }
                    }
                } catch (IOException expectedDisconnect) {
                    // Genuine downstream abort closes only this fixture's upstream exchange.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException(interrupted);
                } finally { exchange.close(); }
            });
            upstream.start();
            server = ApplicationContext.run(EmbeddedServer.class, Map.of("micronaut.server.port", -1,
                "micronaut.server.host", "127.0.0.1", "dkp.bff.portal-origin", UploadHttpTest.ORIGIN,
                "dkp.bff.upstreams.dm", "http://127.0.0.1:" + upstream.getAddress().getPort()));
            bridge = server.getApplicationContext().getBean(DownloadBridge.class);
            session = new UploadHttpTest().seed(server);
            cookie = "__Host-DKP-SESSION=" + session.getId();
        }

        void awaitSettled() throws InterruptedException {
            DownloadBridgeTest.await(() -> bridge.snapshot().producers() == 0
                && bridge.snapshot().readTasks() == 0 && bridge.snapshot().closeTasks() == 0
                && bridge.snapshot().callbacks() == 0 && bridge.snapshot().arenaAllocations() == 0);
            assertEquals(0, bridge.snapshot().arenaBytes());
        }

        @Override public void close() throws Exception {
            headersAllowed.countDown(); server.close(); upstream.stop(0); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
