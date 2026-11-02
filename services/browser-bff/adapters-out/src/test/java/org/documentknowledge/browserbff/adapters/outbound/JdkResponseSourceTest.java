package org.documentknowledge.browserbff.adapters.outbound;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdkResponseSourceTest {
    @Test void eofSuccessfulCloseAndDuplicateCloseSettleExactlyOnce() throws Exception {
        var settled = new AtomicInteger(); var failed = new AtomicInteger();
        var source = new JdkResponseSource(new ByteArrayInputStream(new byte[]{(byte) 255}), settled::incrementAndGet, failed::incrementAndGet, JdkResponseSource::closeActual);
        assertEquals(0, source.read(new byte[0]));
        assertThrows(IndexOutOfBoundsException.class, () -> source.read(new byte[1], 2, 1));
        assertEquals(255, source.read());
        assertEquals(-1, source.read());
        source.close(); assertEquals(-1, source.read());
        assertEquals(1, settled.get()); assertEquals(0, failed.get());
        assertTrue(source.snapshot().settled());
    }
    @Test void pendingReadSurvivesSuccessfulCloseUntilActualReadExit() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var settled = new AtomicInteger();
        var raw = new InputStream() {
            @Override public int read() throws IOException { entered.countDown(); try { release.await(); } catch (InterruptedException error) { throw new IOException(error); } return -1; }
        };
        var source = new JdkResponseSource(raw, settled::incrementAndGet, () -> fail("Close should succeed"), JdkResponseSource::closeActual);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = workers.submit((Callable<Integer>) source::read);
            try { assertTrue(entered.await(1, TimeUnit.SECONDS)); source.close(); assertEquals(0, settled.get()); }
            finally { release.countDown(); }
            assertEquals(-1, read.get(1, TimeUnit.SECONDS)); assertEquals(1, settled.get());
        }
    }
    @Test void duplicateOuterCloseCannotSettleWhileActualInnerCloseIsBlocked() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var source = new JdkResponseSource(new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Missing test release"); }
                catch (InterruptedException error) { throw new IOException(error); }
            }
        }, () -> {}, () -> fail("Close should succeed"), JdkResponseSource::closeActual);
        var outerSettled = new CompletableFuture<Void>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
             var bounded = new BoundedStream(source, -1, () -> {}, 3000, 3000, () -> outerSettled.complete(null))) {
            var exported = source.settlementAfter(outerSettled);
            var closing = workers.submit(() -> { source.close(); return null; });
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                bounded.close();
                assertTrue(outerSettled.isDone(), "Duplicate wrapper close alone has returned");
                assertFalse(exported.toCompletableFuture().isDone(), "Actual inner close is still blocked");
            } finally { release.countDown(); }
            closing.get(1, TimeUnit.SECONDS);
            exported.toCompletableFuture().get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void failedCloseAndZeroProgressCannotClaimSettlement() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            var failures = new AtomicInteger(); final int selected = kind;
            var source = new JdkResponseSource(new InputStream() {
                @Override public int read() { return 0; }
                @Override public int read(byte[] bytes, int offset, int length) { return 0; }
                @Override public void close() throws IOException {
                    if (selected == 0) throw new IOException("Synthetic close");
                    if (selected == 1) throw new IllegalStateException("Synthetic close");
                    throw new AssertionError("Synthetic close");
                }
            }, () -> fail("Failed close cannot settle"), failures::incrementAndGet, JdkResponseSource::closeActual);
            assertThrows(IOException.class, () -> source.read(new byte[1]));
            assertThrows(Throwable.class, source::close);
            source.close(); assertEquals(1, failures.get()); assertFalse(source.snapshot().settled());
        }
    }
}
