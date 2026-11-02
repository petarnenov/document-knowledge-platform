package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.http.server.netty.handler.PipeliningServerHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.application.Readiness;
import org.junit.jupiter.api.Test;

class IngressLiveHandoffTest {
    @Test void actualAcceptedChannelIsRequiredBeforeReadinessAndLaterSuccessCannotResetFailure() throws Exception {
        try (var server = IngressChannelPolicyTest.start()) {
            var context = server.getApplicationContext();
            assertFalse(context.getBean(Readiness.class).isAcceptingWork());
            try (var socket = new Socket("127.0.0.1", server.getPort())) {
                socket.setSoTimeout(3000);
                var channel = context.getBean(IngressChannelObserver.class).channels.poll(3, TimeUnit.SECONDS);
                assertNotNull(channel);
                // The observer publishes during a composite callback, before every member returns.
                channel.eventLoop().submit(() -> {}).get(3, TimeUnit.SECONDS);
                assertTrue(context.getBean(Readiness.class).isAcceptingWork());
                channel.eventLoop().submit(() -> channel.pipeline().get(PipeliningServerHandler.class)
                    .setRequestDecompressionEnabled(true)).sync();
                IngressChannelPolicyTest.send(socket, "GET /health/liveness HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertEquals("", IngressChannelPolicyTest.firstLine(socket));
                assertFalse(context.getBean(Readiness.class).isAcceptingWork());
            }
            try (var healthy = new Socket("127.0.0.1", server.getPort())) {
                healthy.setSoTimeout(3000);
                IngressChannelPolicyTest.send(healthy, "GET /health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(IngressChannelPolicyTest.firstLine(healthy).contains("503"));
                assertFalse(context.getBean(Readiness.class).isAcceptingWork());
            }
        }
    }

    @Test void mutationAfterRawCheckIsDetectedAtEveryActualDownstreamCallback() throws Exception {
        for (String callback : List.of("read", "complete", "exception")) {
            for (boolean decompression : List.of(false, true)) {
                try (var server = IngressChannelPolicyTest.start();
                     var socket = new Socket("127.0.0.1", server.getPort())) {
                    socket.setSoTimeout(3000);
                    var context = server.getApplicationContext();
                    var channel = context.getBean(IngressChannelObserver.class).channels.poll(3, TimeUnit.SECONDS);
                    assertNotNull(channel);
                    var mutations = new AtomicInteger();
                    channel.eventLoop().submit(() -> {
                        var actual = channel.pipeline().get(PipeliningServerHandler.class);
                        channel.pipeline().addAfter("dkp-ingress-receive-policy", "test-ordered-policy-mutation", new ChannelInboundHandlerAdapter() {
                            void mutate() {
                                assertSame(actual, channel.pipeline().get(PipeliningServerHandler.class));
                                if (decompression) actual.setRequestDecompressionEnabled(true);
                                else actual.setBodySizeLimits(new io.micronaut.http.body.stream.BodySizeLimits(100000000, 262144));
                                mutations.incrementAndGet();
                            }
                            @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                                if (callback.equals("read")) mutate();
                                ctx.fireChannelRead(message);
                            }
                            @Override public void channelReadComplete(ChannelHandlerContext ctx) {
                                if (callback.equals("complete")) mutate();
                                ctx.fireChannelReadComplete();
                            }
                            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) {
                                if (callback.equals("exception")) mutate();
                                ctx.fireExceptionCaught(failure);
                            }
                        });
                        if (callback.equals("exception")) {
                            // Explicit controlled error injected into a REAL accepted Netty pipeline, not a transport-error claim.
                            channel.pipeline().fireExceptionCaught(new IOException("TEST_CONTROLLED_ERROR"));
                        }
                    }).sync();
                    if (callback.equals("read")) {
                        IngressChannelPolicyTest.send(socket, "GET /api/v1/session HTTP/1.1\r\nHost: localhost\r\n\r\n");
                    } else if (callback.equals("complete")) {
                        // An incomplete header exercises readComplete before any protected route can be constructed.
                        IngressChannelPolicyTest.send(socket, "G");
                    }
                    assertEquals("", IngressChannelPolicyTest.firstLine(socket), callback);
                    assertEquals(1, mutations.get(), callback);
                    assertFalse(context.getBean(Readiness.class).isAcceptingWork(), callback);
                }
            }
        }
    }

    @Test void insertingAnythingBetweenFinalGuardAndActualHandlerClosesBeforeInsertedCode() throws Exception {
        try (var server = IngressChannelPolicyTest.start(); var socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            var context = server.getApplicationContext();
            var channel = context.getBean(IngressChannelObserver.class).channels.poll(3, TimeUnit.SECONDS);
            assertNotNull(channel);
            var reached = new AtomicInteger();
            channel.eventLoop().submit(() -> {
                var handler = channel.pipeline().get(PipeliningServerHandler.class);
                channel.pipeline().addBefore(channel.pipeline().context(handler).name(), "test-forbidden-gap", new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                        reached.incrementAndGet();
                        handler.setRequestDecompressionEnabled(true);
                        ctx.fireChannelRead(message);
                    }
                });
            }).sync();
            IngressChannelPolicyTest.send(socket, "GET /api/v1/session HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertEquals("", IngressChannelPolicyTest.firstLine(socket));
            assertEquals(0, reached.get());
            assertFalse(context.getBean(Readiness.class).isAcceptingWork());
        }
    }
    @Test void mutationAfterPriorSuccessfulRequestStillUsesFreshSameInstanceReadback() throws Exception {
        try (var server = IngressChannelPolicyTest.start(); var socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            var context = server.getApplicationContext();
            var channel = context.getBean(IngressChannelObserver.class).channels.poll(3, TimeUnit.SECONDS);
            assertNotNull(channel);
            var input = new java.io.BufferedInputStream(socket.getInputStream());
            IngressChannelPolicyTest.send(socket, "GET /health/liveness HTTP/1.1\r\nHost: localhost\r\n\r\n");
            var first = IngressLateBytesTest.response(input);
            assertNotNull(first); assertEquals(200, first.status());
            channel.eventLoop().submit(() -> channel.pipeline().get(PipeliningServerHandler.class)
                .setBodySizeLimits(new io.micronaut.http.body.stream.BodySizeLimits(100000000, 262144))).sync();
            IngressChannelPolicyTest.send(socket, "GET /api/v1/session HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertNull(IngressLateBytesTest.response(input));
            assertFalse(context.getBean(Readiness.class).isAcceptingWork());
        }
    }

    @Test void competingExecutorHopIsRejectedBeforeAnyOffLoopCodeReceivesBody() throws Exception {
        var executor = new io.netty.util.concurrent.DefaultEventExecutorGroup(1);
        try (var server = IngressChannelPolicyTest.start(); var socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            var context = server.getApplicationContext();
            var channel = context.getBean(IngressChannelObserver.class).channels.poll(3, TimeUnit.SECONDS);
            assertNotNull(channel);
            var reached = new AtomicInteger();
            channel.eventLoop().submit(() -> channel.pipeline().addAfter(executor,
                channel.pipeline().context(io.netty.handler.codec.http.HttpServerCodec.class).name(),
                "test-unselected-executor", new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                        reached.incrementAndGet(); ctx.fireChannelRead(message);
                    }
                })).sync();
            IngressChannelPolicyTest.send(socket, "GET /api/v1/session HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertEquals("", IngressChannelPolicyTest.firstLine(socket));
            assertEquals(0, reached.get());
            assertFalse(context.getBean(Readiness.class).isAcceptingWork());
        } finally {
            executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

}
