package org.documentknowledge.browserbff.adapters.outbound;

import java.util.Objects;

/** Local source ownership: a close request cannot acknowledge an admitted reader's exit. */
final class StreamReadLifecycle {
    private final Runnable onSettled;
    private int active;
    private boolean closing;
    private boolean closeReported;
    private boolean closeSucceeded;
    private boolean settled;

    StreamReadLifecycle(Runnable onSettled) { this.onSettled = Objects.requireNonNull(onSettled); }

    synchronized boolean beginRead() {
        if (closing) return false;
        active++;
        return true;
    }

    void endRead() {
        boolean notify;
        synchronized (this) {
            if (active == 0) throw new IllegalStateException("Unbalanced source read exit");
            active--;
            notify = settle();
        }
        if (notify) onSettled.run();
    }

    synchronized boolean beginClose() {
        if (closing) return false;
        closing = true;
        return true;
    }

    void finishClose(boolean succeeded) {
        boolean notify;
        synchronized (this) {
            if (!closing) throw new IllegalStateException("Source close not started");
            if (closeReported) return;
            closeReported = true;
            closeSucceeded = succeeded;
            notify = settle();
        }
        if (notify) onSettled.run();
    }

    synchronized boolean closing() { return closing; }

    synchronized Snapshot snapshot() {
        return new Snapshot(active, closing, closeReported && closeSucceeded,
            closeReported && !closeSucceeded, settled);
    }

    private boolean settle() {
        if (settled || !closeReported || !closeSucceeded || active != 0) return false;
        settled = true;
        return true;
    }

    record Snapshot(int activeReads, boolean closing, boolean closeSucceeded,
                    boolean closeFailed, boolean settled) { }
}
