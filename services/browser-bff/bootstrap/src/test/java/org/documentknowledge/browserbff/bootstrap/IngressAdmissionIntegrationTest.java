package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.runtime.server.EmbeddedServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.documentknowledge.browserbff.application.Readiness;
import org.junit.jupiter.api.Test;

class IngressAdmissionIntegrationTest {
    @Test void retainedRequestCapacityCountsRealPipelinedBodiesAcrossConnections() throws Exception {
        try (var server = io.micronaut.context.ApplicationContext.run(EmbeddedServer.class, java.util.Map.of(
            "micronaut.server.port", -1, "micronaut.server.host", "127.0.0.1",
            "dkp222.admission-witness", "true"));
             var first = connect(server); var second = connect(server)) {
            var policy = server.getApplicationContext().getBean(BoundedIngressPolicy.class);
            var witness = server.getApplicationContext().getBean(IngressAdmissionWitness.class);
            String request = "POST /dkp222/hold HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/octet-stream\r\nContent-Length: 1\r\n\r\nx";
            var responses = new ArrayList<java.util.concurrent.CompletableFuture<String>>();
            try {
                IngressChannelPolicyTest.send(first, request.repeat(32));
                IngressChannelPolicyTest.send(second, request.repeat(32));
                await(() -> policy.admissionSnapshot().requests() == 64);
                for (int i = 0; i < 64; i++) {
                    var response = witness.held.poll(3, TimeUnit.SECONDS);
                    assertNotNull(response);
                    responses.add(response);
                }
                try (var rejected = connect(server)) {
                    IngressChannelPolicyTest.send(rejected, request);
                    assertEquals(-1, rejected.getInputStream().read());
                }
                assertEquals(64, policy.admissionSnapshot().requests());
                assertTrue(witness.held.isEmpty(), "An unadmitted request reached the controller");
                assertTrue(server.getApplicationContext().getBean(Readiness.class).isAcceptingWork());
                responses.forEach(response -> response.complete("ok"));
                await(() -> policy.admissionSnapshot().requests() == 0);
                try (var replacement = connect(server)) {
                    IngressChannelPolicyTest.send(replacement, request);
                    var response = witness.held.poll(3, TimeUnit.SECONDS);
                    assertNotNull(response);
                    assertEquals(1, policy.admissionSnapshot().requests());
                    response.complete("ok");
                    assertTrue(IngressChannelPolicyTest.firstLine(replacement).contains("200"));
                    await(() -> policy.admissionSnapshot().requests() == 0);
                }
            } finally {
                responses.forEach(response -> response.complete("ok"));
                witness.held.forEach(response -> response.complete("ok"));
            }
        }
    }

    @Test void preAuthConnectionsAreBoundedAndConfirmedTeardownReusesCapacity() throws Exception {
        try (var server = IngressChannelPolicyTest.start()) {
            var policy = server.getApplicationContext().getBean(BoundedIngressPolicy.class);
            var sockets = new ArrayList<Socket>();
            try {
                for (int i = 0; i < 32; i++) sockets.add(connect(server));
                await(() -> policy.admissionSnapshot().connections() == 32);
                assertTrue(server.getApplicationContext().getBean(Readiness.class).isAcceptingWork());
                try (var rejected = connect(server)) {
                    assertEquals(-1, rejected.getInputStream().read());
                }
                assertEquals(new IngressAdmission.Snapshot(32, 0), policy.admissionSnapshot());
                assertTrue(server.getApplicationContext().getBean(Readiness.class).isAcceptingWork());
                sockets.getFirst().close();
                await(() -> policy.admissionSnapshot().connections() == 31);
                try (var replacement = connect(server)) {
                    IngressChannelPolicyTest.send(replacement,
                        "GET /health/liveness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                    assertTrue(IngressChannelPolicyTest.firstLine(replacement).contains("200"));
                }
            } finally {
                for (var socket : sockets) socket.close();
            }
            await(() -> policy.admissionSnapshot().equals(new IngressAdmission.Snapshot(0, 0)));
        }
    }

    @Test void incompleteBodyDisconnectSettlesTheActualFrameworkOwner() throws Exception {
        try (var server = io.micronaut.context.ApplicationContext.run(EmbeddedServer.class, java.util.Map.of(
            "micronaut.server.port", -1, "micronaut.server.host", "127.0.0.1",
            "dkp222.admission-witness", "true"))) {
            var policy = server.getApplicationContext().getBean(BoundedIngressPolicy.class);
            try (var socket = connect(server)) {
                IngressChannelPolicyTest.send(socket,
                    "POST /dkp222/hold HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream\r\nContent-Length: 1000\r\n\r\nx");
                await(() -> policy.admissionSnapshot().requests() == 1);
            }
            await(() -> policy.admissionSnapshot().equals(new IngressAdmission.Snapshot(0, 0)));
        }
    }

    @Test void missingOrFailedOrderedDiscardNeverFreesUnprovedCapacity() throws Exception {
        for (String mutation : new String[] {"raw", "framework", "throwing-removal", "reentrant-removal"}) {
            try (var server = IngressChannelPolicyTest.start(); var socket = connect(server)) {
                var policy = server.getApplicationContext().getBean(BoundedIngressPolicy.class);
                var observer = server.getApplicationContext().getBean(IngressChannelObserver.class);
                Channel channel = observer.channels.poll(3, TimeUnit.SECONDS);
                assertNotNull(channel);
                channel.eventLoop().submit(() -> {
                    switch (mutation) {
                        case "raw" -> channel.pipeline().remove("dkp-ingress-receive-policy");
                        case "framework" -> channel.pipeline().remove(
                            io.micronaut.http.server.netty.handler.PipeliningServerHandler.class);
                        case "throwing-removal" -> channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override public void handlerRemoved(ChannelHandlerContext context) {
                                throw new IllegalStateException("TEST_DISCARD_FAILURE");
                            }
                        });
                        case "reentrant-removal" -> channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override public void channelUnregistered(ChannelHandlerContext context) {
                                context.pipeline().remove("dkp-ingress-receive-policy");
                                context.fireChannelUnregistered();
                            }
                        });
                        default -> throw new AssertionError(mutation);
                    }
                    channel.close();
                }).sync();
                channel.closeFuture().sync();
                // A same-loop barrier after unregistration/removal, not a close-request observation.
                channel.eventLoop().submit(() -> { }).sync();
                assertEquals(1, policy.admissionSnapshot().connections(), mutation);
            }
        }
    }

    private static Socket connect(EmbeddedServer server) throws Exception {
        var socket = new Socket("127.0.0.1", server.getPort());
        socket.setSoTimeout(3000);
        return socket;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Admission ownership did not reach its expected state");
    }
}
