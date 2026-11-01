package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.micronaut.http.server.netty.NettyEmbeddedServer;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.micronaut.http.server.netty.handler.PipeliningServerHandler;
import io.micronaut.http.server.netty.configuration.NettyHttpServerConfiguration;
import io.micronaut.http.ssl.ServerSslConfiguration;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.AttributeKey;
import jakarta.inject.Singleton;
import org.documentknowledge.browserbff.application.Readiness;

/** Pinned plaintext HTTP/1 ingress policy. The allowance is per retained request, not per process. */
@Singleton
@ServerFilter("/**")
public final class BoundedIngressPolicy implements BeanCreatedEventListener<NettyEmbeddedServer>, Ordered {
    static final int QUEUE_BYTES = 131072;
    static final int RECEIVE_BYTES = 8192;
    private static final AttributeKey<java.util.function.BooleanSupplier> VALIDATOR =
        AttributeKey.valueOf(BoundedIngressPolicy.class, "validator");
    private final NettyHttpServerConfiguration configuration;
    private final ServerSslConfiguration ssl;
    private final Readiness readiness;
    private PinnedIngressReadback readback;
    private boolean channelAttested;
    private boolean failed;

    public BoundedIngressPolicy(NettyHttpServerConfiguration configuration, ServerSslConfiguration ssl,
                                Readiness readiness) {
        this.configuration = configuration;
        this.ssl = ssl;
        this.readiness = readiness;
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @RequestFilter
    public void beforeDispatch(HttpRequest<?> request) {
        if (request instanceof NettyHttpRequest<?> netty) {
            var channel = netty.getChannelHandlerContext().channel();
            var validator = channel.attr(VALIDATOR).get();
            if (validator != null && validator.getAsBoolean()) return;
            reject(channel);
        } else unavailable();
        throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BFF_INGRESS_POLICY_UNAVAILABLE");
    }

    @Override public NettyEmbeddedServer onCreated(BeanCreatedEvent<NettyEmbeddedServer> event) {
        try { readback = new PinnedIngressReadback(); }
        catch (RuntimeException unavailable) { readiness.markUnavailable(); throw unavailable; }
        readiness.markUnavailable();
        if (!configured()) {
            readiness.markUnavailable();
            throw new IllegalStateException("BFF_INGRESS_POLICY_UNAVAILABLE");
        }
        event.getBean().register(customizer());
        return event.getBean();
    }

    NettyServerCustomizer customizer() {
        return new NettyServerCustomizer() {
            @Override public NettyServerCustomizer specializeForChannel(Channel channel, ChannelRole role) {
                if (role == ChannelRole.LISTENER) return this;
                if (role != ChannelRole.CONNECTION || !(channel instanceof SocketChannel)) {
                    reject(channel);
                    return this;
                }
                // This callback runs during channel initialization, before its first receive handle/read.
                var allocator = new PolicyAllocator();
                allocator.maxMessagesPerRead(1);
                channel.config().setRecvByteBufAllocator(allocator);
                channel.config().setAutoRead(false);
                return new Connection(channel, allocator);
            }
        };
    }

    boolean configured() {
        var listeners = configuration.getListeners();
        return configuration.getMaxRequestBufferSize() == QUEUE_BYTES
            && configuration.getMaxChunkSize() == RECEIVE_BYTES
            && configuration.getMaxRequestSize() == 100000000
            && configuration.getHttpVersion() == HttpVersion.HTTP_1_1
            && configuration.getServerType() == NettyHttpServerConfiguration.HttpServerType.STREAMED
            && !configuration.isRequestDecompressionEnabled() && !ssl.isEnabled()
            && (listeners == null || listeners.stream().allMatch(listener ->
                listener.getFamily() == NettyHttpServerConfiguration.NettyListenerConfiguration.Family.TCP && !listener.isSsl()));
    }

    private synchronized void unavailable() {
        failed = true;
        readiness.markUnavailable();
    }

    private void reject(Channel channel) {
        unavailable();
        channel.close();
    }

    private synchronized void acceptedChannel() {
        if (!channelAttested) {
            channelAttested = true;
            if (!failed) readiness.markAvailable();
        }
    }

    /** A channel with a receive handle cached before this allocator is installed is unsupported. */
    private static final class PolicyAllocator extends FixedRecvByteBufAllocator {
        private Handle issued;
        PolicyAllocator() { super(RECEIVE_BYTES); }
        @Override public synchronized Handle newHandle() {
            Handle handle = super.newHandle();
            if (issued == null) issued = handle;
            return handle;
        }
        synchronized boolean owns(Handle handle) { return issued != null && issued == handle; }
    }

    private final class Connection implements NettyServerCustomizer {
        private final Channel channel;
        private final PolicyAllocator allocator;
        private HttpServerCodec codec;
        private PipeliningServerHandler embedded;
        private boolean initial;
        private boolean guardsInstalled;
        private Guard rawGuard;
        private Guard decodedGuard;

        Connection(Channel channel, PolicyAllocator allocator) {
            this.channel = channel;
            this.allocator = allocator;
        }

        @Override public void onInitialPipelineBuilt() {
            codec = channel.pipeline().get(HttpServerCodec.class);
            embedded = channel.pipeline().get(PipeliningServerHandler.class);
            initial = true;
            if (!valid()) { reject(channel); return; }
            rawGuard = new Guard(false);
            decodedGuard = new Guard(true);
            channel.pipeline().addFirst("dkp-ingress-receive-policy", rawGuard);
            channel.pipeline().addBefore(channel.pipeline().context(embedded).name(),
                "dkp-ingress-decoded-policy", decodedGuard);
            guardsInstalled = true;
            channel.attr(VALIDATOR).set(this::valid);
        }

        @Override public void onStreamPipelineBuilt() {
            if (!initial || !valid()) reject(channel);
            else acceptedChannel();
        }

        private boolean valid() {
            return channel.eventLoop().inEventLoop() && configured() && codec != null
                && embedded != null && readback != null && readback.matches(embedded)
                && channel.pipeline().get(PipeliningServerHandler.class) == embedded
                && channel.pipeline().get(HttpServerCodec.class) == codec
                && channel.pipeline().get(SslHandler.class) == null
                && channel.pipeline().get(HttpObjectAggregator.class) == null
                && channel.pipeline().get(HttpContentDecompressor.class) == null
                && (!guardsInstalled || (channel.pipeline().context(rawGuard) != null
                    && channel.pipeline().context(decodedGuard) != null && adjacent()))
                && channel.config().getRecvByteBufAllocator() == allocator
                && allocator.maxMessagesPerRead() == 1
                && allocator.owns(channel.unsafe().recvBufAllocHandle())
                && channel.unsafe().recvBufAllocHandle().guess() == RECEIVE_BYTES
                && !channel.config().isAutoRead();
        }

        private boolean adjacent() {
            var pipeline = channel.pipeline();
            var names = pipeline.names();
            int raw = names.indexOf(pipeline.context(rawGuard).name());
            int decode = names.indexOf(pipeline.context(codec).name());
            int guard = names.indexOf(pipeline.context(decodedGuard).name());
            int target = names.indexOf(pipeline.context(embedded).name());
            if (!(raw >= 0 && raw < decode && decode < guard && guard + 1 == target)) return false;
            for (int i = raw; i <= target; i++) {
                if (pipeline.context(names.get(i)).executor() != channel.eventLoop()) return false;
            }
            return true;
        }

        private final class Guard extends ChannelInboundHandlerAdapter {
            private final boolean decoded;
            Guard(boolean decoded) { this.decoded = decoded; }

            @Override public void channelReadComplete(ChannelHandlerContext context) {
                if (valid()) context.fireChannelReadComplete();
                else reject(context.channel());
            }

            @Override public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                if (valid()) context.fireExceptionCaught(failure);
                else reject(context.channel());
            }

            @Override public void channelRead(ChannelHandlerContext context, Object message) {
                boolean accepted = valid();
                if (decoded && message instanceof io.netty.handler.codec.http.HttpRequest request) {
                    accepted &= request.protocolVersion().equals(io.netty.handler.codec.http.HttpVersion.HTTP_1_1);
                }
                if (decoded && message instanceof HttpContent content) {
                    accepted &= content.content().readableBytes() <= RECEIVE_BYTES;
                }
                if (!decoded && message instanceof ByteBuf bytes) accepted &= bytes.readableBytes() <= RECEIVE_BYTES;
                if (!accepted) {
                    ReferenceCountUtil.release(message);
                    reject(context.channel());
                } else context.fireChannelRead(message);
            }
        }
    }
}
