package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinuxFixtureEventLoopsTest {
    @Test void shutdownRequestCannotReplaceActualOwnedGroupExit() throws Exception {
        var scope = new LinuxFixtureEventLoops.Scope();
        var group = new NioEventLoopGroup(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        scope.retain(group);
        group.execute(() -> {
            entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS), "missing controlled worker release"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            assertTrue(group.isShuttingDown());
            assertThrows(AssertionError.class, () -> scope.await(Duration.ofMillis(25)));
            assertEquals(1, scope.size(), "unproved owner must stay identified after the wait fails");
            assertFalse(group.isTerminated());
        } finally { release.countDown(); }
        scope.await(Duration.ofSeconds(3));
        assertTrue(group.isTerminated());
    }

    @Test void actualServerCapturesDirectAcceptorAndRegistryWorkerGroupsAndWaitsAfterClose() throws Exception {
        try (var scope = LinuxFixtureEventLoops.observe()) {
            assertThrows(IllegalStateException.class, LinuxFixtureEventLoops::observe);
            try (var server = ApplicationContext.run(EmbeddedServer.class,
                    Map.of("micronaut.server.port", -1, "micronaut.server.host", "127.0.0.1"));
                 var socket = new Socket("127.0.0.1", server.getPort())) {
                socket.setSoTimeout(3000);
                IngressChannelPolicyTest.send(socket,
                    "GET /health/liveness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertTrue(IngressChannelPolicyTest.firstLine(socket).contains("200"));
                assertEquals(2, scope.size(), "capture both the direct parent and the registry worker group");
                assertThrows(AssertionError.class, () -> scope.await(Duration.ofMillis(25)),
                    "an active server must not satisfy fixture exit");
            }
            scope.await(Duration.ofSeconds(15));
        }
        try (var next = LinuxFixtureEventLoops.observe()) { assertEquals(0, next.size()); }
    }
}
