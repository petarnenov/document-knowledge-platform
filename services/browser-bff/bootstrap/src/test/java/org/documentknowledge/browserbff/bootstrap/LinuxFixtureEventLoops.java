package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.http.server.netty.NettyEmbeddedServer;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.concurrent.TimeUnit;

/** Exit witness for synchronous test fixtures; never changes a group's shutdown policy. */
@Singleton
final class LinuxFixtureEventLoops implements BeanCreatedEventListener<NettyEmbeddedServer> {
    private static volatile Scope current;

    static synchronized Scope observe() {
        if (current != null) throw new IllegalStateException("Fixture observation already active");
        current = new Scope();
        return current;
    }

    private static void capture(EventLoopGroup group) {
        var scope = current;
        if (scope != null) scope.retain(group);
    }

    @Override public NettyEmbeddedServer onCreated(BeanCreatedEvent<NettyEmbeddedServer> event) {
        if (current != null) event.getBean().register(new NettyServerCustomizer() {
            @Override public NettyServerCustomizer specializeForChannel(Channel channel, ChannelRole role) {
                // The default acceptor group is created directly, outside the bean registry.
                if (role == ChannelRole.LISTENER || role == ChannelRole.CONNECTION)
                    capture(channel.eventLoop().parent());
                return this;
            }
        });
        return event.getBean();
    }

    @Singleton
    static final class RegistryGroups implements BeanCreatedEventListener<EventLoopGroup> {
        @Override public EventLoopGroup onCreated(BeanCreatedEvent<EventLoopGroup> event) {
            capture(event.getBean());
            return event.getBean();
        }
    }

    static final class Scope implements AutoCloseable {
        private final IdentityHashMap<EventLoopGroup, Boolean> groups = new IdentityHashMap<>();

        synchronized void retain(EventLoopGroup group) { groups.put(group, Boolean.TRUE); }
        synchronized int size() { return groups.size(); }

        void await(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            EventLoopGroup[] owned;
            synchronized (this) { owned = groups.keySet().toArray(EventLoopGroup[]::new); }
            for (var group : owned) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !group.terminationFuture().await(remaining, TimeUnit.NANOSECONDS)
                    || !group.terminationFuture().isSuccess() || !group.isTerminated())
                    throw new AssertionError("Previous fixture event-loop group has not actually terminated: "
                        + group.getClass().getName() + " shuttingDown=" + group.isShuttingDown()
                        + " shutdown=" + group.isShutdown() + " terminated=" + group.isTerminated());
            }
        }

        @Override public void close() throws InterruptedException {
            try { await(Duration.ofSeconds(15)); }
            finally {
                synchronized (LinuxFixtureEventLoops.class) {
                    if (current == this) current = null;
                }
            }
        }
    }
}
