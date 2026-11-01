package org.documentknowledge.browserbff.adapters.outbound;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedStreamOwnershipTest {
    @Test void closedFlagDoesNotAcknowledgeABlockedRead() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var closes = new AtomicInteger();
        InputStream source = new InputStream() {
            @Override public int read() throws IOException {
                reads.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                return -1;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException { return read(); }
            @Override public void close() { closes.incrementAndGet(); }
        };
        var budget = new UploadReadAdmission();
        var lease = budget.tryRetain(source);
        try (var stream = new BoundedStream(source, -1, () -> {}, 300_000, 30_000, lease::release);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit((Callable<Integer>) stream::read);
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                stream.close();
                assertEquals(1, budget.retained());
                assertEquals(1, stream.ownershipSnapshot().activeReads());
                assertTrue(stream.ownershipSnapshot().closeSucceeded());
                assertFalse(stream.ownershipSnapshot().settled());
                assertEquals(-1, stream.read());
                assertEquals(1, reads.get());
            } finally { release.countDown(); }
            assertEquals(-1, task.get(2, TimeUnit.SECONDS));
            assertEquals(0, budget.retained());
            assertTrue(stream.ownershipSnapshot().settled());
            assertEquals(1, closes.get());
        } finally { release.countDown(); }
    }

    @Test void closePendingAndCloseFailureCannotFreeAnOwner() throws Exception {
        for (boolean fail : new boolean[] {false, true}) {
            var closeEntered = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            var calls = new AtomicInteger();
            InputStream source = new ByteArrayInputStream(new byte[0]) {
                @Override public void close() throws IOException {
                    calls.incrementAndGet(); closeEntered.countDown();
                    try { assertTrue(finish.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                    if (fail) throw new IOException("synthetic close failure");
                }
            };
            var budget = new UploadReadAdmission();
            var lease = budget.tryRetain(source);
            var stream = new BoundedStream(source, 0, () -> {}, 300_000, 30_000, lease::release);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var task = executor.submit(() -> { stream.close(); return null; });
                try {
                    assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
                    assertEquals(1, budget.retained());
                    assertFalse(stream.ownershipSnapshot().settled());
                    assertEquals(-1, stream.read());
                } finally { finish.countDown(); }
                if (fail) assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> task.get(2, TimeUnit.SECONDS)).getCause());
                else task.get(2, TimeUnit.SECONDS);
                stream.close();
                assertEquals(1, calls.get());
                assertEquals(fail ? 1 : 0, budget.retained());
                assertEquals(fail, stream.ownershipSnapshot().closeFailed());
            } finally { finish.countDown(); stream.close(); }
        }
    }

    @Test void firstByteDenialAndLengthFailureStillSettleOnlyAfterReadExit() throws Exception {
        for (boolean deny : new boolean[] {false, true}) {
            var calls = new AtomicInteger();
            var source = new ByteArrayInputStream(new byte[0]);
            try (var stream = new BoundedStream(source, 1, () -> {
                if (deny) throw new org.documentknowledge.browserbff.application.BffFailure(401, "UNAUTHENTICATED");
            }, 300_000, 30_000, calls::incrementAndGet)) {
                assertThrows(Exception.class, stream::read);
                assertEquals(1, calls.get());
                assertTrue(stream.ownershipSnapshot().settled());
            }
        }
    }
}
