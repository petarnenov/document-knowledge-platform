package org.documentknowledge.browserbff.adapters.outbound;

import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.io.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadSourceConstructionTest {
    @Test void rejectedTimerConstructionClosesExactlyOnceAndKeepsUnprovedOwners() {
        for (int rejectedAt : new int[] {1, 2}) {
            for (boolean closeFails : new boolean[] {false, true}) {
                var scheduled = new AtomicInteger();
                int selected = rejectedAt;
                var original = new RejectedExecutionException("synthetic scheduling rejection");
                try (var timer = new ScheduledThreadPoolExecutor(1)) {
                    timer.setRemoveOnCancelPolicy(true);
                    // Actual executor for the accepted task; deterministic rejection at either startup step.
                    ScheduledExecutorService rejecting = new ScheduledThreadPoolExecutor(1) {
                        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                            if (scheduled.incrementAndGet() == selected) throw original;
                            return timer.schedule(command, delay, unit);
                        }
                    };
                    try (rejecting) {
                        var closes = new AtomicInteger();
                        var closeFailure = new IOException("synthetic close rejection");
                        InputStream source = new ByteArrayInputStream(new byte[0]) {
                            @Override public void close() throws IOException {
                                closes.incrementAndGet(); if (closeFails) throw closeFailure;
                            }
                        };
                        var budget = new UploadReadAdmission();
                        var lease = budget.tryRetain(source);
                        assertSame(original, assertThrows(RejectedExecutionException.class,
                            () -> new BoundedStream(source, -1, () -> {}, 300_000, 30_000, lease::release, rejecting)));
                        assertEquals(1, closes.get());
                        assertEquals(closeFails ? 1 : 0, budget.retained());
                        assertEquals(0, timer.getQueue().size(), "accepted startup task must be cancelled");
                        assertEquals(closeFails ? 1 : 0, original.getSuppressed().length);
                        if (closeFails) assertSame(closeFailure, original.getSuppressed()[0]);
                    }
                }
            }
        }
    }

    @Test void invalidUpstreamBeforePublisherSubscriptionClosesSourceWithoutReading() {
        try (var context = ApplicationContext.run()) {
            for (boolean closeFails : new boolean[] {false, true}) {
                var closes = new AtomicInteger();
                var budget = new UploadReadAdmission();
                InputStream source = new InputStream() {
                    @Override public int read() { throw new AssertionError("invalid destination must not read source"); }
                    @Override public void close() throws IOException {
                        closes.incrementAndGet(); if (closeFails) throw new IOException("synthetic close failure");
                    }
                };
                try (var gateway = new FixedUploadGateway("https://upstream.example/forbidden-path",
                        context.getBean(JsonMapper.class), 300_000, 30_000, budget)) {
                    var identity = new BrowserIdentity("https://issuer.example", "user", "Synthetic",
                        List.of("tenant-a"), true, "synthetic-token", Instant.now().plusSeconds(300));
                    var failure = assertThrows(BffFailure.class, () -> gateway.transfer(
                        "/api/v1/documents/uploads/11111111-1111-4111-8111-111111111111/content",
                        "constructor-cleanup", source, -1, identity, "tenant-a", () -> fail("not subscribed")));
                    assertEquals("UNAVAILABLE", failure.code());
                    assertEquals(1, closes.get());
                    assertEquals(closeFails ? 1 : 0, budget.retained());
                }
            }
        }
    }
}
