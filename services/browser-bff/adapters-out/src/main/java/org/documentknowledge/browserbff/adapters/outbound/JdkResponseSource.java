package org.documentknowledge.browserbff.adapters.outbound;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Objects;

/** Actual source close and read exit, independent of header/waiter/task completion. */
final class JdkResponseSource extends FilterInputStream {
    private final StreamReadLifecycle lifecycle;
    private final Runnable failed;
    private final CloseRequest requestClose;
    @FunctionalInterface interface CloseRequest { void request(JdkResponseSource source) throws IOException; }
    private final java.util.concurrent.CompletableFuture<Void> settlement = new java.util.concurrent.CompletableFuture<>();
    JdkResponseSource(InputStream source, Runnable settled, Runnable failed, CloseRequest requestClose) {
        super(Objects.requireNonNull(source));
        lifecycle = new StreamReadLifecycle(() -> { settled.run(); settlement.complete(null); });
        this.failed = Objects.requireNonNull(failed);
        this.requestClose = Objects.requireNonNull(requestClose);
    }
    @Override public int read() throws IOException {
        byte[] single = new byte[1];
        int count = read(single, 0, 1);
        return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (!lifecycle.beginRead()) return -1;
        try {
            int count = in.read(bytes, offset, length);
            if (count < 0) close();
            if (count == 0) throw new IOException("Response source made no progress");
            return count;
        } finally { lifecycle.endRead(); }
    }
    @Override public void close() throws IOException {
        if (!lifecycle.beginClose()) return;
        requestClose.request(this);
    }
    boolean closing() { return lifecycle.closing(); }
    void closeActual() throws IOException {
        boolean success = false;
        try { in.close(); success = true; }
        catch (IOException | RuntimeException | Error error) { failed.run(); throw error; }
        finally { lifecycle.finishClose(success); }
    }
    java.util.concurrent.CompletionStage<Void> settlementAfter(java.util.concurrent.CompletionStage<Void> outer) {
        return settlement.thenCombine(outer, (source, wrapper) -> (Void) null).minimalCompletionStage();
    }
    StreamReadLifecycle.Snapshot snapshot() { return lifecycle.snapshot(); }
}
