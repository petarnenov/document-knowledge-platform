package org.documentknowledge.browserbff.adapters.outbound;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.application.Readiness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedJdkTransportTest {
    @Test void finiteAdmissionIsRecoverableAndHandoffRequiresActualSourceSettlement() throws Exception {
        var readiness = new Readiness();
        try (var fixture = new Server(); var owner = new OwnedJdkTransport(readiness)) {
            var client = owner.newClient();
            var slots = new ArrayList<OwnedJdkTransport.Exchange>();
            for (int i = 0; i < 64; i++) slots.add(owner.reserve(client, 3000));
            assertEquals(64, owner.snapshot().exchanges());
            assertThrows(IOException.class, () -> owner.reserve(client, 3000));
            assertTrue(readiness.isAcceptingWork());
            slots.forEach(OwnedJdkTransport.Exchange::close);
            assertEquals(0, owner.snapshot().exchanges());
            var exchange = owner.reserve(client, 3000);
            var response = exchange.send(fixture.request("/ok"));
            exchange.requestSourcePending();
            exchange.handoff(); exchange.close();
            assertEquals(1, owner.snapshot().exchanges());
            assertArrayEquals(new byte[]{1, 2, 3}, response.readBounded(exchange, 3, 3000));
            assertEquals(1, owner.snapshot().exchanges(), "Request source remains independently charged");
            exchange.requestSourceSettled();
            await(() -> owner.snapshot().exchanges() == 0);
            assertEquals(HttpClient.Version.HTTP_1_1, client.version());
            owner.newClient(); owner.newClient();
            assertThrows(IllegalStateException.class, owner::newClient);
            owner.close();
            assertTrue(owner.awaitTermination(Duration.ofSeconds(5)));
            assertTrue(client.awaitTermination(Duration.ofSeconds(1)));
            assertThrows(IOException.class, () -> owner.reserve(client, 3000));
            assertThrows(IllegalStateException.class, owner::newClient);
        }
    }

    @Test void lateHeadersAndQueuedExpiryKeepOriginalDeadlineAndNeverDispatchExpiredWork() throws Exception {
        try (var fixture = new Server(); var owner = new OwnedJdkTransport(new Readiness());
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = owner.newClient();
            var entered = new CountDownLatch(8);
            var release = new CountDownLatch(1);
            fixture.server.createContext("/held", incoming -> {
                entered.countDown();
                try { release.await(); incoming.sendResponseHeaders(200, 3); incoming.getResponseBody().write(new byte[]{1, 2, 3}); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { incoming.close(); }
            });
            var active = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 8; i++) active.add(callers.submit(() -> {
                    try (var exchange = owner.reserve(client, 3000)) { exchange.send(fixture.request("/held")); }
                    catch (Exception failure) { throw new CompletionException(failure); }
                }));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                try (var expired = owner.reserve(client, 50)) {
                    assertThrows(IOException.class, () -> expired.send(fixture.request("/expired")));
                    assertEquals(9, owner.snapshot().exchanges(), "Timed out queued task still owns its slot");
                }
                release.countDown();
                for (var task : active) task.get(4, TimeUnit.SECONDS);
                await(() -> owner.snapshot().exchanges() == 0);
                assertEquals(0, fixture.expired.get(), "Expired queue entry must not call HttpClient.send");
                assertFalse(owner.snapshot().failed());
            } finally { release.countDown(); }
        }
    }

    @Test void sixtyFourActualOperationsShareThreeClientsAndRecoverAfterCapacityRefusal() throws Exception {
        var readiness = new Readiness();
        try (var fixture = new Server(); var owner = new OwnedJdkTransport(readiness);
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var clients = List.of(owner.newClient(), owner.newClient(), owner.newClient());
            var entered = new CountDownLatch(8); var release = new CountDownLatch(1);
            var requests = new AtomicInteger();
            fixture.server.createContext("/concurrent", incoming -> {
                requests.incrementAndGet(); entered.countDown();
                try { release.await(); incoming.sendResponseHeaders(200, 3); incoming.getResponseBody().write(new byte[]{1, 2, 3}); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { incoming.close(); }
            });
            var operations = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 64; i++) {
                    var exchange = owner.reserve(clients.get(i % 3), 300_000);
                    operations.add(callers.submit(() -> {
                        try (exchange) { assertArrayEquals(new byte[]{1, 2, 3}, exchange.send(fixture.request("/concurrent")).readBounded(exchange, 3, 30_000)); }
                        catch (Exception error) { throw new CompletionException(error); }
                    }));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(64, owner.snapshot().exchanges());
                assertThrows(IOException.class, () -> owner.reserve(clients.getFirst(), 3000));
                assertTrue(readiness.isAcceptingWork());
                release.countDown();
                for (var operation : operations) operation.get(10, TimeUnit.SECONDS);
                await(() -> owner.snapshot().exchanges() == 0);
                assertEquals(64, requests.get());
                assertFalse(owner.snapshot().failed());
                try (var recovered = owner.reserve(clients.getFirst(), 3000)) {
                    assertArrayEquals(new byte[]{1, 2, 3}, recovered.send(fixture.request("/ok")).readBounded(recovered, 3, 3000));
                }
            } finally { release.countDown(); }
        }
    }

    @Test void bodyLimitsDoubleDispatchAndInterruptedWaiterRetainActualCleanup() throws Exception {
        try (var fixture = new Server(); var owner = new OwnedJdkTransport(new Readiness())) {
            var client = owner.newClient();
            try (var exchange = owner.reserve(client, 3000)) {
                var response = exchange.send(fixture.request("/ok"));
                assertThrows(IllegalStateException.class, () -> exchange.send(fixture.request("/ok")));
                assertThrows(IOException.class, () -> response.readBounded(exchange, 2, 3000));
            }
            try (var exchange = owner.reserve(client, 3000)) {
                Thread.currentThread().interrupt();
                try { assertThrows(InterruptedException.class, () -> exchange.send(fixture.request("/ok"))); }
                finally { Thread.interrupted(); }
            }
            await(() -> owner.snapshot().exchanges() == 0);
            assertFalse(owner.snapshot().failed());
            try (var exchange = owner.reserve(client, 3000)) {
                assertThrows(IOException.class, () -> exchange.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/")).GET().build()));
            }
            await(() -> owner.snapshot().exchanges() == 0);
        }
    }

    @Test void interruptedCallerDoesNotCancelActualSendAndLateBodyIsClosed() throws Exception {
        try (var fixture = new Server(); var owner = new OwnedJdkTransport(new Readiness())) {
            var client = owner.newClient();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var callerExited = new CountDownLatch(1);
            fixture.server.createContext("/late", incoming -> {
                entered.countDown();
                try { release.await(); incoming.sendResponseHeaders(200, 3); incoming.getResponseBody().write(new byte[]{1, 2, 3}); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { incoming.close(); }
            });
            var callerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var caller = Thread.ofPlatform().start(() -> {
                try (var exchange = owner.reserve(client, 3000)) {
                    assertThrows(InterruptedException.class, () -> exchange.send(fixture.request("/late")));
                } catch (Throwable error) { callerFailure.set(error); }
                finally { callerExited.countDown(); }
            });
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                caller.interrupt(); assertTrue(callerExited.await(1, TimeUnit.SECONDS));
                assertNull(callerFailure.get());
                assertEquals(1, owner.snapshot().sendTasks());
                assertEquals(1, owner.snapshot().exchanges(), "Abandonment is not actual send/source exit");
                release.countDown();
                await(() -> owner.snapshot().exchanges() == 0);
                assertFalse(owner.snapshot().failed());
            } finally { release.countDown(); caller.interrupt(); caller.join(4000); }
        }
    }

    @Test void expiredBeforeRequestWrapperStillClosesActualSourceWithoutDispatch() throws Exception {
        try (var owner = new OwnedJdkTransport(new Readiness())) {
            var client = owner.newClient(); var actualCloses = new AtomicInteger();
            var budget = new UploadReadAdmission();
            var source = new java.io.ByteArrayInputStream(new byte[]{1}) {
                @Override public void close() { actualCloses.incrementAndGet(); }
            };
            var lease = budget.tryRetain(source);
            try (var exchange = owner.reserve(client, 1)) {
                Thread.sleep(5);
                try (var wrapped = exchange.requestSource(source, 1, () -> fail("Expired source read"), 3000, lease::release)) {
                    assertThrows(IOException.class, exchange::remainingMillis);
                }
            }
            await(() -> owner.snapshot().exchanges() == 0);
            assertEquals(1, actualCloses.get()); assertEquals(0, budget.retained());
        }
    }

    @Test void rejectedInitialAndJdkSubmissionsLatchUnknownBeforeEscape() throws Exception {
        for (String worker : List.of("sends", "jdk", "closes")) {
            var readiness = new Readiness();
            try (var fixture = new Server(); var owner = new OwnedJdkTransport(readiness)) {
                var client = owner.newClient();
                var exchange = owner.reserve(client, 3000);
                var executor = executor(owner, worker);
                if (worker.equals("closes")) exchange.send(fixture.request("/ok"));
                executor.shutdown();
                if (worker.equals("sends")) assertThrows(IOException.class, () -> exchange.send(fixture.request("/ok")));
                else if (worker.equals("jdk")) assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
                exchange.close();
                assertTrue(owner.snapshot().failed(), worker);
                assertFalse(readiness.isAcceptingWork());
                assertEquals(1, owner.snapshot().unknown());
                assertThrows(IOException.class, () -> owner.reserve(client, 3000));
                owner.close();
                assertTrue(owner.awaitTermination(Duration.ofSeconds(5)));
                assertEquals(1, owner.snapshot().exchanges(), "Unknown work cannot be recycled by shutdown");
            }
        }
    }

    @Test void shutdownDrainsActualSendAndClosesHandedOffSourceRead() throws Exception {
        try (var fixture = new Server(); var owner = new OwnedJdkTransport(new Readiness());
             var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = owner.newClient();
            var release = new CountDownLatch(1); var headers = new CountDownLatch(1);
            fixture.server.createContext("/streaming", incoming -> {
                try { incoming.sendResponseHeaders(200, 100); incoming.getResponseBody().write(42); incoming.getResponseBody().flush(); headers.countDown(); release.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { incoming.close(); }
            });
            try {
                var exchange = owner.reserve(client, 3000);
                var response = exchange.send(fixture.request("/streaming"));
                exchange.handoff(); exchange.close();
                assertEquals(42, response.body().read(), "Genuine wire prefix precedes the blocked read");
                var reading = readers.submit(() -> { try { return response.body().read(); } catch (IOException expected) { return -1; } });
                assertTrue(headers.await(1, TimeUnit.SECONDS));
                await(() -> response.body().snapshot().activeReads() == 1);
                owner.close();
                assertTrue(owner.awaitTermination(Duration.ofSeconds(5)));
                assertEquals(-1, reading.get(1, TimeUnit.SECONDS));
                await(() -> owner.snapshot().exchanges() == 0);
                assertFalse(owner.snapshot().failed(), "Normal shutdown must not create an executor fallback fault");
            } finally { release.countDown(); }
        }
    }

    @Test void thrownJdkTaskErrorIsObservedAtActualRunnableExit() throws Exception {
        var readiness = new Readiness();
        try (var owner = new OwnedJdkTransport(readiness)) {
            owner.newClient();
            executor(owner, "jdk").execute(() -> { throw new AssertionError("synthetic worker failure"); });
            await(() -> owner.snapshot().failed());
            assertFalse(readiness.isAcceptingWork());
            assertTrue(owner.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    private static ThreadPoolExecutor executor(OwnedJdkTransport owner, String name) throws Exception {
        var field = OwnedJdkTransport.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(owner);
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        final ExecutorService executor = Executors.newCachedThreadPool();
        final AtomicInteger expired = new AtomicInteger();
        Server() throws IOException {
            server.setExecutor(executor);
            server.createContext("/ok", incoming -> { incoming.sendResponseHeaders(200, 3); incoming.getResponseBody().write(new byte[]{1, 2, 3}); incoming.close(); });
            server.createContext("/expired", incoming -> { expired.incrementAndGet(); incoming.sendResponseHeaders(200, -1); incoming.close(); });
            server.start();
        }
        HttpRequest request(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path)).GET().build(); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
