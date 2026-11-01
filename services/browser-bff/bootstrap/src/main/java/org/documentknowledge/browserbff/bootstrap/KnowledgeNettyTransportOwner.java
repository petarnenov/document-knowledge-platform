package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.browserbff.adapters.inbound.KnowledgeTransportOwner;
import org.documentknowledge.browserbff.application.*;

/** The actual browser connection owns pending body/KB HTTP futures, including a physical TCP reset. */
@Singleton
public final class KnowledgeNettyTransportOwner implements KnowledgeTransportOwner {
    private final Set<Active> bindings=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopping=new AtomicBoolean();
    @Override public Binding attach(HttpRequest<?> request,KnowledgeDeadline deadline){
        if(stopping.get()||!(request instanceof NettyHttpRequest<?> netty))throw new BffFailure(503,"UNAVAILABLE");
        Active binding=new Active(netty.getChannelHandlerContext().channel().closeFuture(),deadline);bindings.add(binding);
        try{binding.connection.addListener(binding.closed);if(stopping.get())deadline.cancel();deadline.check();return binding;}
        catch(RuntimeException failure){deadline.cancel();binding.close();throw new BffFailure(503,"UNAVAILABLE");}
    }
    @PreDestroy public void close(){stopping.set(true);bindings.forEach(binding->binding.deadline.cancel());bindings.forEach(Active::close);}
    private final class Active implements Binding {
        final ChannelFuture connection;final KnowledgeDeadline deadline;final ChannelFutureListener closed;
        final AtomicBoolean disposed=new AtomicBoolean();
        Active(ChannelFuture connection,KnowledgeDeadline deadline){this.connection=connection;this.deadline=deadline;closed=ignored->deadline.cancel();}
        @Override public void close(){if(disposed.compareAndSet(false,true)){connection.removeListener(closed);bindings.remove(this);}}
    }
}
