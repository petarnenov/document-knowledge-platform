package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import io.netty.channel.WriteBufferWaterMark;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.documentknowledge.browserbff.application.DocumentWire;
import org.documentknowledge.browserbff.application.Readiness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutboundWriteIntegrationTest {
    @Test void realSlowReaderRetainsFiniteWritesAndDisconnectActuallySettles() throws Exception {
        try (var server = start()) {
            var policy = server.getApplicationContext().getBean(BoundedIngressPolicy.class);
            var witness = server.getApplicationContext().getBean(OutboundWriteWitness.class);
            try (var slow = new Socket()) {
                slow.setReceiveBufferSize(1024);
                slow.connect(new InetSocketAddress("127.0.0.1", server.getPort()));
                IngressChannelPolicyTest.send(slow,
                    "GET /dkp222/output/stream HTTP/1.1\r\nHost: localhost\r\n\r\n");
                await(() -> policy.outboundSnapshot().bytes() > 0);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                while (System.nanoTime() < deadline) {
                    var snapshot = policy.outboundSnapshot();
                    assertTrue(snapshot.bytes() <= OutboundAdmission.APPLICATION_BYTES);
                    assertTrue(snapshot.messages() <= OutboundAdmission.APPLICATION_MESSAGES);
                    assertFalse(snapshot.failed());
                    Thread.sleep(10);
                }
                assertTrue(witness.generated.get() < 100_000_000,
                    "The slow reader must backpressure the real producer before consuming the whole object");
                healthy(server);
                assertTrue(server.getApplicationContext().getBean(Readiness.class).isAcceptingWork());
            }
            await(() -> policy.outboundSnapshot().messages() == 0 && witness.closed.get());
            assertEquals(0, policy.outboundSnapshot().bytes());
            assertEquals(0, policy.outboundSnapshot().unresolvedMessages());
            assertFalse(policy.outboundSnapshot().failed());
            healthy(server);
        }
    }

    @Test void actualLargeClosedMetadataResponseExceedsIncomingChunkWithoutTruncation() throws Exception {
        try (var server = start(); var client = HttpClient.newHttpClient()) {
            var witness = server.getApplicationContext().getBean(OutboundWriteWitness.class);
            var json = server.getApplicationContext().getBean(JsonMapper.class);
            var expected = witness.metadata();
            assertTrue(DocumentWire.response("GET", "/api/v1/documents", expected, "tenant-a", 50));
            byte[] expectedBytes = json.writeValueAsBytes(expected);
            assertTrue(expectedBytes.length > 131072);
            assertTrue(expectedBytes.length < 1048576);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.getPort() + "/dkp222/output/metadata")).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(expectedBytes, response.body());
            var policy = server.getApplicationContext().getBean(BoundedIngressPolicy.class);
            await(() -> policy.outboundSnapshot().messages() == 0);
            assertFalse(policy.outboundSnapshot().failed());
        }
    }

    @Test void changedActualWriteWatermarkFailsClosedBeforeDispatch() throws Exception {
        try (var server = start(); var socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            var channel = server.getApplicationContext().getBean(IngressChannelObserver.class)
                .channels.poll(3, TimeUnit.SECONDS);
            assertNotNull(channel);
            channel.eventLoop().submit(() -> channel.config().setWriteBufferWaterMark(
                new WriteBufferWaterMark(16384, 32768))).get(3, TimeUnit.SECONDS);
            IngressChannelPolicyTest.send(socket, "GET /health/liveness HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertEquals(-1, socket.getInputStream().read());
            assertFalse(server.getApplicationContext().getBean(Readiness.class).isAcceptingWork());
        }
    }

    private static EmbeddedServer start() {
        return ApplicationContext.run(EmbeddedServer.class, Map.of("micronaut.server.port", -1,
            "micronaut.server.host", "127.0.0.1", "dkp338.channel-observer", "true",
            "dkp222.output-witness", "true"));
    }

    private static void healthy(EmbeddedServer server) throws Exception {
        try (var socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            IngressChannelPolicyTest.send(socket,
                "GET /health/liveness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            assertTrue(IngressChannelPolicyTest.firstLine(socket).contains("200"));
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Actual outbound owner did not reach its expected state");
    }
}
