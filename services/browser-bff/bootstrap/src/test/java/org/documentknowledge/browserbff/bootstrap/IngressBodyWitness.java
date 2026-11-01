package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.netty.channel.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.Lock;

/** Pinned-framework TEST instrumentation only: samples real counters/streams without replacing demand. */
@Controller("/dkp338")
@Requires(property="dkp338.body-witness",value="true")
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.BLOCKING)
final class IngressBodyWitness {
    final CountDownLatch saturated = new CountDownLatch(1), begin = new CountDownLatch(1),
        currentHeld = new CountDownLatch(1), resume = new CountDownLatch(1);
    final AtomicLong queueHigh = new AtomicLong(), rawHigh = new AtomicLong(), decodedHigh = new AtomicLong();
    final AtomicInteger registered = new AtomicInteger(), requestsSeen = new AtomicInteger(), frameworkRetainedHigh = new AtomicInteger();
    final CountDownLatch pipelineHeld = new CountDownLatch(1);
    final AtomicLong availableRetainedBytes = new AtomicLong();
    final BlockingQueue<Object> bodies = new LinkedBlockingQueue<>();
    final ConcurrentMap<Object,AtomicLong> retained = new ConcurrentHashMap<>();
    final BlockingQueue<Channel> channels = new LinkedBlockingQueue<>();
    volatile InputStream stream;
    volatile Throwable witnessFailure;
    volatile long saturatedQueue, saturatedCredit;

    @Post(value="/body",consumes=MediaType.ALL,produces=MediaType.TEXT_PLAIN)
    String body(HttpRequest<?> request,@Body InputStream body) throws Exception {
        assert request instanceof NettyHttpRequest<?>;
        var nativeRequest=(NettyHttpRequest<?>)request;
        Object byteBody=nativeRequest.byteBody();
        if(byteBody instanceof io.micronaut.http.body.AvailableByteBody)availableRetainedBytes.addAndGet(body.available());
        Object shared=fieldOrNull(byteBody,"sharedBuffer");
        if(shared!=null) retained.putIfAbsent(shared,new AtomicLong());
        registered.incrementAndGet();stream=body;
        bodies.add(byteBody);
        var channel=nativeRequest.getChannelHandlerContext().channel();channels.add(channel);
        channel.eventLoop().submit(()->{
            if(channel.pipeline().get("dkp338-counter-witness")==null)
                channel.pipeline().addFirst("dkp338-counter-witness",new ChannelInboundHandlerAdapter(){
                    @Override public void channelRead(ChannelHandlerContext ctx,Object value)throws Exception {
                        if(value instanceof io.netty.buffer.ByteBuf bytes)rawHigh.accumulateAndGet(bytes.readableBytes(),Math::max);
                        super.channelRead(ctx,value);
                    }
                    @Override public void channelReadComplete(ChannelHandlerContext ctx)throws Exception {
                        super.channelReadComplete(ctx);sample(channel);
                    }
                });
            sample(channel);
        }).sync();
        if(!begin.await(5,TimeUnit.SECONDS))throw new IOException("TEST_CONSUMER_NOT_RELEASED");
        long count=0;int one=body.read();if(one!=-1)count++;
        currentHeld.countDown();
        if(!resume.await(5,TimeUnit.SECONDS))throw new IOException("TEST_CURRENT_NOT_RELEASED");
        byte[] bytes=new byte[8192];int n;while((n=body.read(bytes))!=-1)count+=n;
        return Long.toString(count);
    }

    void sample(Channel channel) {
        try {
            Object pipeline=channel.pipeline().get(io.micronaut.http.server.netty.handler.PipeliningServerHandler.class);
            Object inbound=field(pipeline,"inboundHandler");Object dest=fieldOrNull(inbound,"dest");
            if(dest!=null)retained.putIfAbsent(dest,new AtomicLong());
            int count=((Collection<?>)field(pipeline,"outboundQueue")).size();
            if(field(pipeline,"outboundHandler")!=null)count++;
            frameworkRetainedHigh.accumulateAndGet(count,Math::max);
            for(var entry:retained.entrySet()) {
                Object trackers=field(entry.getKey(),"sizeLimitTrackers");
                var method=trackers.getClass().getMethod("bufferedSize");method.setAccessible(true);
                Object tracker=method.invoke(trackers);
                long value=tracker instanceof AtomicLong atomic?atomic.get():((Number)field(tracker,"value")).longValue();
                entry.getValue().accumulateAndGet(value,Math::max);queueHigh.accumulateAndGet(value,Math::max);
            }
            if(count>=2)pipelineHeld.countDown();
            Object credit=fieldOrNull(inbound,"requested");
            if(credit instanceof Long requested && requested<=0 && !(Boolean)field(pipeline,"readCalled") && saturated.getCount()!=0){
                saturatedQueue=queueHigh.get();saturatedCredit=requested;saturated.countDown();
            }
        } catch(Throwable failure){witnessFailure=failure;saturated.countDown();}
    }
    long[] bridge() throws Exception {
        Object current=field(stream,"buffer");Object blocking=field(stream,"publisherAsBlocking");Lock lock=(Lock)field(blocking,"lock");
        lock.lock();try{return new long[]{readable(current),readable(field(blocking,"swap"))};}finally{lock.unlock();}
    }
    static long readable(Object value)throws Exception {
        if(value==null)return 0;return ((io.micronaut.core.io.buffer.ReadBuffer)value).readable();
    }
    static Object fieldOrNull(Object target,String name)throws Exception {
        try{return field(target,name);}catch(NoSuchFieldException absent){return null;}
    }
    static Object field(Object target,String name)throws Exception {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException next){}
        throw new NoSuchFieldException(name);
    }
}
