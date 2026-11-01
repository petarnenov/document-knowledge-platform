package org.documentknowledge.knowledgebase.bootstrap;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeRequestOwner;
import org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeSetupBudget;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;

/** Close events and the original timer invalidate the SAME application deadline before SQL admission. */
@Singleton
public final class KnowledgeNettyRequestOwner implements KnowledgeRequestOwner {
    private static final String ATTRIBUTE="dkp.kb.transport-owner";
    private final ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon().name("kb-deadline-",0).factory());
    private final Set<Active> active=ConcurrentHashMap.newKeySet();
    public KnowledgeNettyRequestOwner(){timer.setRemoveOnCancelPolicy(true);}
    public void begin(HttpRequest<?> request,Deadline deadline){
        if(!(request instanceof NettyHttpRequest<?> netty))throw KbFailure.unavailable();
        Active owner=new Active(deadline,netty.getChannelHandlerContext().channel().closeFuture(),new KnowledgeSetupBudget(timer,deadline));
        request.setAttribute(KnowledgeSetupBudget.ATTRIBUTE,owner.setup);
        active.add(owner);request.setAttribute(ATTRIBUTE,owner);
        try{owner.connection.addListener(owner.closed);owner.alarm=timer.schedule(owner::cancel,deadline.remainingNanos(),TimeUnit.NANOSECONDS);}
        catch(RuntimeException unavailable){owner.cancel();owner.dispose();active.remove(owner);throw KbFailure.unavailable();}
    }
    public org.reactivestreams.Publisher<io.micronaut.http.MutableHttpResponse<?>> supervise(HttpRequest<?> request,Deadline deadline,org.reactivestreams.Publisher<io.micronaut.http.MutableHttpResponse<?>> downstream){
        Active owner=request.getAttribute(ATTRIBUTE,Active.class).orElseThrow(KbFailure::unavailable);
        AtomicBoolean emitted=new AtomicBoolean();
        return reactor.core.publisher.Mono.firstWithSignal(reactor.core.publisher.Mono.from(downstream),reactor.core.publisher.Mono.fromFuture(owner.setup.expiration()).then(reactor.core.publisher.Mono.fromSupplier(()->io.micronaut.http.HttpResponse.status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("code","UNAVAILABLE")).contentType(io.micronaut.http.MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store"))))
            .timeout(java.time.Duration.ofNanos(deadline.remainingNanos()),reactor.core.publisher.Mono.defer(()->{owner.cancel();return reactor.core.publisher.Mono.just(io.micronaut.http.HttpResponse.status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("code","UNAVAILABLE")).contentType(io.micronaut.http.MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store"));}))
            .doOnNext(response->emitted.set(true)).doOnCancel(()->{if(!emitted.get())owner.cancel();}).doFinally(ignored->finish(request));
    }
    public Lease enter(HttpRequest<?> request,Deadline deadline){
        Active owner=request.getAttribute(ATTRIBUTE,Active.class).orElseThrow(KbFailure::unavailable);
        owner.enter(deadline);
        return owner::leave;
    }
    public void finish(HttpRequest<?> request){request.getAttribute(ATTRIBUTE,Active.class).ifPresent(owner->{owner.dispose();active.remove(owner);});}
    @PreDestroy public void close(){active.forEach(Active::cancel);active.forEach(Active::dispose);active.clear();timer.shutdownNow();}
    private static final class Active {
        final Deadline deadline;final ChannelFuture connection;final KnowledgeSetupBudget setup;private Thread worker;
        final ChannelFutureListener closed=ignored->cancel();final AtomicBoolean disposed=new AtomicBoolean();volatile ScheduledFuture<?> alarm;
        Active(Deadline deadline,ChannelFuture connection,KnowledgeSetupBudget setup){this.deadline=deadline;this.connection=connection;this.setup=setup;}
        synchronized void enter(Deadline supplied){if(deadline!=supplied||worker!=null)throw KbFailure.unavailable();deadline.check();worker=Thread.currentThread();}
        synchronized void leave(){if(worker==Thread.currentThread())worker=null;}
        synchronized void cancel(){deadline.cancel();if(worker!=null)worker.interrupt();}
        synchronized void dispose(){if(disposed.compareAndSet(false,true)){setup.close();connection.removeListener(closed);ScheduledFuture<?> task=alarm;if(task!=null)task.cancel(false);worker=null;}}
    }
}
