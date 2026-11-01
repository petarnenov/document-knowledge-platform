package org.documentknowledge.knowledgebase.bootstrap;

import io.micronaut.http.HttpRequest;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeNettyRequestOwnerTest {
    @Test void unsupportedTransportCannotSilentlyRemoveCancellationOwnership(){
        var owner=new KnowledgeNettyRequestOwner();try{
            var request=HttpRequest.GET("/api/v1/knowledge/collections");
            assertEquals(503,assertThrows(KbFailure.class,()->owner.begin(request,Deadline.request())).status());
            assertEquals(503,assertThrows(KbFailure.class,()->owner.enter(request,Deadline.request())).status());
            owner.finish(request);
        }finally{owner.close();}
    }
    static io.micronaut.http.server.netty.NettyHttpRequest<Object> request(io.netty.channel.embedded.EmbeddedChannel channel){
        return new io.micronaut.http.server.netty.NettyHttpRequest<>(new io.netty.handler.codec.http.DefaultHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,io.netty.handler.codec.http.HttpMethod.GET,"/api/v1/knowledge/collections"),io.micronaut.http.netty.body.NettyByteBodyFactory.empty(),channel.pipeline().firstContext(),io.micronaut.core.convert.ConversionService.SHARED,new io.micronaut.http.server.HttpServerConfiguration());
    }
    @Test void alreadyClosedChannelCannotLeaveAnUnownedTimerOrAdmitWork(){
        var owner=new KnowledgeNettyRequestOwner();var channel=new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        try{var request=request(channel);channel.close().syncUninterruptibly();var deadline=Deadline.request();assertEquals(503,assertThrows(KbFailure.class,()->owner.begin(request,deadline)).status());assertThrows(KbFailure.class,deadline::check);owner.finish(request);}finally{owner.close();channel.finishAndReleaseAll();}
    }
    @Test void onlyOneMatchingCoordinatorCanOwnTheLiveRequest(){
        var owner=new KnowledgeNettyRequestOwner();var channel=new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        try{var request=request(channel);var deadline=Deadline.request();owner.begin(request,deadline);try(var lease=owner.enter(request,deadline)){assertEquals(503,assertThrows(KbFailure.class,()->owner.enter(request,deadline)).status());assertEquals(503,assertThrows(KbFailure.class,()->owner.enter(request,Deadline.request())).status());}owner.finish(request);owner.finish(request);channel.close().syncUninterruptibly();deadline.check();assertFalse(Thread.currentThread().isInterrupted(),"Detached close listener must not interrupt an unrelated later task");}finally{owner.close();channel.finishAndReleaseAll();}
    }

}
