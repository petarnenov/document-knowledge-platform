package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.server.netty.*;
import io.netty.channel.*;
import jakarta.inject.Singleton;
import java.util.concurrent.*;

/** Test-only callback observes actual accepted channels; it never substitutes a transport. */
@Singleton
@Requires(property="dkp338.channel-observer",value="true")
final class IngressChannelObserver implements BeanCreatedEventListener<NettyEmbeddedServer>, Ordered {
    private final io.micronaut.context.BeanContext context;
    IngressChannelObserver(io.micronaut.context.BeanContext context) { this.context=context; }
    final BlockingQueue<Channel> channels = new LinkedBlockingQueue<>();
    volatile boolean initialAutoRead;
    volatile int initialGuess;
    volatile int initialMaxMessages;
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public NettyEmbeddedServer onCreated(BeanCreatedEvent<NettyEmbeddedServer> event) {
        event.getBean().register(new NettyServerCustomizer() {
            @Override public NettyServerCustomizer specializeForChannel(Channel channel, ChannelRole role) {
                if (role != ChannelRole.CONNECTION) return this;
                return new NettyServerCustomizer() {
                    @Override public void onInitialPipelineBuilt() {
                        context.findBean(IngressBodyWitness.class).ifPresent(witness -> {
                            channel.pipeline().addAfter(channel.pipeline().context(io.netty.handler.codec.http.HttpServerCodec.class).name(),
                                "dkp338-pipeline-witness",new ChannelInboundHandlerAdapter(){
                                    @Override public void channelRead(ChannelHandlerContext ctx,Object value)throws Exception {
                                        if(value instanceof io.netty.handler.codec.http.HttpRequest)witness.requestsSeen.incrementAndGet();
                                        super.channelRead(ctx,value);
                                    }
                                    @Override public void channelReadComplete(ChannelHandlerContext ctx)throws Exception {
                                        super.channelReadComplete(ctx);witness.sample(channel);
                                    }
                                });
                        });
                        initialAutoRead = channel.config().isAutoRead();
                        initialGuess = channel.unsafe().recvBufAllocHandle().guess();
                        initialMaxMessages = ((MaxMessagesRecvByteBufAllocator) channel.config().getRecvByteBufAllocator()).maxMessagesPerRead();
                    }
                    @Override public void onStreamPipelineBuilt() { channels.add(channel); }
                };
            }
        });
        return event.getBean();
    }
}
