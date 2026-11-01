package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.CompletableFuture;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeNettyTransportOwnerTest {
    private static NettyHttpRequest<Object> request(EmbeddedChannel channel){return new NettyHttpRequest<>(new io.netty.handler.codec.http.DefaultHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,io.netty.handler.codec.http.HttpMethod.GET,"/api/v1/kb/collections"),io.micronaut.http.netty.body.NettyByteBodyFactory.empty(),channel.pipeline().firstContext(),io.micronaut.core.convert.ConversionService.SHARED,new io.micronaut.http.server.HttpServerConfiguration());}
    @Test void unsupportedOrAlreadyClosedTransportCannotCreateLiveOwnership(){var owner=new KnowledgeNettyTransportOwner();var channel=new EmbeddedChannel(new ChannelInboundHandlerAdapter());try{assertEquals(503,assertThrows(BffFailure.class,()->owner.attach(HttpRequest.GET("/api/v1/kb/collections"),new KnowledgeDeadline())).status());var incoming=request(channel);channel.close().syncUninterruptibly();var deadline=new KnowledgeDeadline();assertThrows(BffFailure.class,()->owner.attach(incoming,deadline));assertThrows(BffFailure.class,deadline::check);}finally{owner.close();channel.finishAndReleaseAll();}}
    @Test void physicalCloseCancelsOnlyThePendingWorkOfItsOwnRequest(){var owner=new KnowledgeNettyTransportOwner();var channel=new EmbeddedChannel(new ChannelInboundHandlerAdapter());var other=new EmbeddedChannel(new ChannelInboundHandlerAdapter());try{var deadline=new KnowledgeDeadline();var pending=new CompletableFuture<>();deadline.own(pending);var binding=owner.attach(request(channel),deadline);var independent=new KnowledgeDeadline();var detached=owner.attach(request(other),independent);detached.close();detached.close();other.close().syncUninterruptibly();independent.check();assertFalse(pending.isDone());channel.close().syncUninterruptibly();assertTrue(pending.isCancelled());assertThrows(BffFailure.class,deadline::check);binding.close();binding.close();}finally{owner.close();channel.finishAndReleaseAll();other.finishAndReleaseAll();}}
    @Test void shutdownCancelsLiveFuturesAndRejectsNewAttachments(){var owner=new KnowledgeNettyTransportOwner();var channel=new EmbeddedChannel(new ChannelInboundHandlerAdapter());try{var deadline=new KnowledgeDeadline();var pending=new CompletableFuture<>();deadline.own(pending);owner.attach(request(channel),deadline);owner.close();assertTrue(pending.isCancelled());assertThrows(BffFailure.class,deadline::check);assertThrows(BffFailure.class,()->owner.attach(request(channel),new KnowledgeDeadline()));owner.close();}finally{owner.close();channel.finishAndReleaseAll();}}
}
