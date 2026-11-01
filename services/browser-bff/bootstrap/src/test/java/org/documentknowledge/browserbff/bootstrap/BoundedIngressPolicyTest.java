package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.server.netty.configuration.NettyHttpServerConfiguration;
import io.micronaut.http.ssl.ServerSslConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.*;
import java.util.function.Consumer;
import org.documentknowledge.browserbff.application.Readiness;
import org.junit.jupiter.api.Test;

class BoundedIngressPolicyTest {
    static NettyHttpServerConfiguration selected() {
        var c = new NettyHttpServerConfiguration();
        c.setMaxRequestBufferSize(131072);
        c.setMaxRequestSize(100000000);
        c.setMaxChunkSize(8192);
        c.setHttpVersion(HttpVersion.HTTP_1_1);
        c.setRequestDecompressionEnabled(false);
        return c;
    }
    @Test void exactProfileAndPlainTcpListenersAreAccepted() {
        var c = selected(); var ssl = new ServerSslConfiguration(); var ready = new Readiness();
        var policy = new BoundedIngressPolicy(c, ssl, ready);
        assertTrue(policy.configured());
        c.setListeners(List.of(NettyHttpServerConfiguration.NettyListenerConfiguration.createTcp("127.0.0.1", 0, false, null, null)));
        assertTrue(policy.configured());
        c.setListeners(List.of(NettyHttpServerConfiguration.NettyListenerConfiguration.createTcp("127.0.0.1", 0, true, null, null)));
        assertFalse(policy.configured());
        c.setListeners(null); ssl.setEnabled(true); assertFalse(policy.configured());
    }
    @Test void everyMemoryProtocolAndDecompressionMismatchFailsClosed() {
        List<Consumer<NettyHttpServerConfiguration>> mutations = List.of(
            c -> c.setMaxRequestBufferSize(16384), c -> c.setMaxRequestBufferSize(262144),
            c -> c.setMaxChunkSize(8193), c -> c.setMaxRequestSize(100000001),
            c -> c.setHttpVersion(HttpVersion.HTTP_2_0), c -> c.setRequestDecompressionEnabled(true),
            c -> c.setServerType(NettyHttpServerConfiguration.HttpServerType.FULL_CONTENT));
        for (var mutate : mutations) {
            var c = selected(); mutate.accept(c);
            assertFalse(new BoundedIngressPolicy(c, new ServerSslConfiguration(), new Readiness()).configured());
        }
        var c = selected(); var listener = NettyHttpServerConfiguration.NettyListenerConfiguration.createTcp("127.0.0.1", 0, false, null, null);
        listener.setFamily(NettyHttpServerConfiguration.NettyListenerConfiguration.Family.UNIX);
        c.setListeners(List.of(listener));
        assertFalse(new BoundedIngressPolicy(c, new ServerSslConfiguration(), new Readiness()).configured());
    }
    @Test void actualNettyReceiveHandleCachedBeforePolicyCannotInheritNewAllocatorProof() throws Exception {
        var ready = new Readiness();
        var policy = new BoundedIngressPolicy(selected(),new ServerSslConfiguration(),ready);
        var channel = new io.netty.channel.socket.nio.NioSocketChannel();
        var group = new io.netty.channel.nio.NioEventLoopGroup(1);
        try {
            group.register(channel).sync();
            var original = channel.unsafe().recvBufAllocHandle();
            channel.pipeline().addLast(new io.netty.handler.codec.http.HttpServerCodec());
            var connection = policy.customizer().specializeForChannel(channel,
                io.micronaut.http.server.netty.NettyServerCustomizer.ChannelRole.CONNECTION);
            assertSame(original,channel.unsafe().recvBufAllocHandle());
            connection.onInitialPipelineBuilt();
            assertFalse(ready.isAcceptingWork());
        } finally { channel.close().sync(); group.shutdownGracefully(0,1,java.util.concurrent.TimeUnit.SECONDS).sync(); }
    }
    @Test void unknownHttpRequestImplementationCannotInheritChannelProof() {
        var ready = new Readiness();
        var policy = new BoundedIngressPolicy(selected(),new ServerSslConfiguration(),ready);
        assertThrows(io.micronaut.http.exceptions.HttpStatusException.class,
            () -> policy.beforeDispatch(io.micronaut.http.HttpRequest.GET("/api/v1/session")));
        assertFalse(ready.isAcceptingWork());
    }
    @Test void actualServerCannotStartWithUnselectedWindowOrProtocol() {
        for (Map<String,Object> change : List.<Map<String,Object>>of(
            Map.of("micronaut.server.max-request-buffer-size", 16384),
            Map.of("micronaut.server.http-version", "2.0"),
            Map.of("micronaut.server.netty.max-chunk-size", 16384),
            Map.of("micronaut.server.netty.request-decompression-enabled", true))) {
            var properties = new HashMap<String,Object>(change);
            properties.put("micronaut.server.port", -1);
            properties.put("micronaut.server.host", "127.0.0.1");
            assertThrows(RuntimeException.class, () -> {
                try (var ignored = ApplicationContext.run(EmbeddedServer.class, properties)) {
                    fail("Unsupported ingress policy started");
                }
            });
        }
    }
}
