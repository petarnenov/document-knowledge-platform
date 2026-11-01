package org.documentknowledge.browserbff.adapters.outbound;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.io.*;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadReadAdmissionIntegrationTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String PATH = "/api/v1/documents/uploads/" + ID + "/content";
    private static final BrowserIdentity IDENTITY = new BrowserIdentity("https://issuer.example", "user",
        "Synthetic", List.of("tenant-a"), true, "synthetic-token", Instant.now().plusSeconds(300));

    @Test void realHttpTimeoutRetainsSourceUntilBlockedReadActuallyExits() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var received = new CountDownLatch(1);
        var budget = new UploadReadAdmission();
        var prefixBytes = new AtomicInteger();
        InputStream source = new InputStream() {
            @Override public int read() {
                // Arrange a genuine wire prefix before blocking; JDK read-ahead need not follow socket writes.
                if (received.getCount() != 0) {
                    assertTrue(prefixBytes.incrementAndGet() <= 65_536, "HTTP prefix was not received within bounded source data");
                    return 42;
                }
                entered.countDown();
                boolean interrupted = false;
                try {
                    while (true) {
                        try { if (release.await(10, TimeUnit.SECONDS)) return -1; throw new AssertionError("read cleanup missing"); }
                        catch (InterruptedException cancelled) { interrupted = true; }
                    }
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (length == 0) return 0;
                int value = read();
                if (value < 0) return -1;
                bytes[offset] = (byte) value;
                return 1;
            }
            @Override public void close() { closed.countDown(); }
        };
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(PATH, exchange -> {
            try {
                assertEquals(42, exchange.getRequestBody().read());
                received.countDown();
                exchange.getRequestBody().readAllBytes();
            } catch (IOException disconnected) { }
            finally { exchange.close(); }
        });
        server.start();
        try (var context = ApplicationContext.run();
             var gateway = new FixedUploadGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                 context.getBean(JsonMapper.class), 2000, 1500, budget)) {
            var command = executor.submit(() -> gateway.transfer(PATH, "blocked-source", source, -1,
                IDENTITY, "tenant-a", () -> {}));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertTrue(received.await(1, TimeUnit.SECONDS));
                assertTrue(prefixBytes.get() > 0);
                var failure = assertThrows(ExecutionException.class, () -> command.get(4, TimeUnit.SECONDS));
                assertEquals("UNAVAILABLE", assertInstanceOf(BffFailure.class, failure.getCause()).code());
                assertTrue(closed.await(1, TimeUnit.SECONDS));
                assertEquals(1, budget.retained(), "caller failure and close do not prove read exit");
            } finally { release.countDown(); }
            awaitReleased(budget);
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void applicationSharedCapacityRejectsBeforeReadAndRecoversForRealHttp() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(PATH, exchange -> {
            try {
                requests.incrementAndGet();
                assertArrayEquals(new byte[] {42}, exchange.getRequestBody().readAllBytes());
                byte[] response = ("{\"uploadId\":\"" + ID + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try (var context = ApplicationContext.run(Map.of("dkp.bff.upstreams.dm",
                "http://127.0.0.1:" + server.getAddress().getPort()))) {
            var budget = context.getBean(UploadReadAdmission.class);
            var gateway = context.getBean(FixedUploadGateway.class);
            var leases = new ArrayList<UploadReadAdmission.Lease>();
            try {
                for (int index = 0; index < 64; index++) leases.add(Objects.requireNonNull(budget.tryRetain(new ByteArrayInputStream(new byte[0]))));
                var reads = new AtomicInteger();
                var authorizations = new AtomicInteger();
                var refused = new InputStream() { @Override public int read() { reads.incrementAndGet(); throw new AssertionError("overload read"); } };
                var failure = assertThrows(BffFailure.class, () -> gateway.transfer(PATH, "capacity", refused, -1,
                    IDENTITY, "tenant-a", authorizations::incrementAndGet));
                assertEquals("UNAVAILABLE", failure.code());
                assertEquals(64, budget.retained());
                assertEquals(0, reads.get()); assertEquals(0, authorizations.get()); assertEquals(0, requests.get());
                leases.removeFirst().release();
                var result = gateway.transfer(PATH, "recovered", new ByteArrayInputStream(new byte[] {42}), 1,
                    IDENTITY, "tenant-a", authorizations::incrementAndGet);
                assertEquals(200, result.status()); assertEquals(1, requests.get()); assertEquals(1, authorizations.get());
                assertEquals(63, budget.retained());
            } finally { leases.forEach(UploadReadAdmission.Lease::release); }
            assertEquals(0, budget.retained());
        } finally { server.stop(0); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private static void awaitReleased(UploadReadAdmission budget) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (budget.retained() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, budget.retained());
    }
}
