package org.documentknowledge.browserbff.bootstrap;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Encoded pending payload ownership only; allocator and producer memory are separate pools. */
final class OutboundAdmission {
    static final int CONNECTION_BYTES = 8 * 1024 * 1024;
    static final long APPLICATION_BYTES = 64L * 1024 * 1024;
    static final int CONNECTION_MESSAGES = 128;
    static final int APPLICATION_MESSAGES = 4096;

    private long bytes;
    private int messages;
    private boolean failed;
    private long unresolvedBytes;
    private int unresolvedUnknownSizes;
    private final IdentityHashMap<Object, Unresolved> unresolved = new IdentityHashMap<>();

    Connection connection() { return new Connection(); }

    synchronized boolean failed() { return failed; }

    synchronized void fail() { failed = true; }

    synchronized Snapshot snapshot() {
        return new Snapshot(bytes, messages, unresolvedBytes, unresolved.size(), unresolvedUnknownSizes, failed);
    }

    void unresolved(Object owner, int size) {
        if (size < 0) throw new IllegalArgumentException("Negative encoded size");
        recordUnresolved(owner, new Unresolved(size, true));
    }

    void unresolvedUnknown(Object owner) { recordUnresolved(owner, new Unresolved(0, false)); }

    private synchronized void recordUnresolved(Object owner, Unresolved value) {
        Objects.requireNonNull(owner);
        failed = true;
        // Preserve the first observation: a retry cannot repair an unknown owner or double count it.
        if (!unresolved.containsKey(owner)) {
            long next = Math.addExact(unresolvedBytes, value.bytes());
            unresolved.put(owner, value);
            unresolvedBytes = next;
            if (!value.known()) unresolvedUnknownSizes++;
        }
    }

    private record Unresolved(int bytes, boolean known) { }

    /** unresolvedBytes is only the known-size subtotal; unknown-size owners remain explicit. */
    record Snapshot(long bytes, int messages, long unresolvedBytes, int unresolvedMessages,
                    int unresolvedUnknownSizeMessages, boolean failed) { }

    final class Connection {
        private long bytes;
        private int messages;

        Charge tryRetain(int size) {
            if (size < 0) throw new IllegalArgumentException("Negative encoded size");
            synchronized (OutboundAdmission.this) {
                if (failed || size > CONNECTION_BYTES - bytes
                    || size > APPLICATION_BYTES - OutboundAdmission.this.bytes
                    || messages >= CONNECTION_MESSAGES
                    || OutboundAdmission.this.messages >= APPLICATION_MESSAGES) return null;
                bytes += size;
                messages++;
                OutboundAdmission.this.bytes += size;
                OutboundAdmission.this.messages++;
                return new Charge(this, size);
            }
        }
    }

    final class Charge {
        private final Connection owner;
        private final int size;
        private boolean released;
        private boolean completed;
        private boolean unknown;
        private boolean settled;

        private Charge(Connection owner, int size) { this.owner = owner; this.size = size; }

        int size() { return size; }

        void unknown(Object owner) {
            unknown();
            unresolved(owner, size);
        }

        void released() {
            synchronized (OutboundAdmission.this) {
                released = true;
                settle();
            }
        }

        void completed() {
            synchronized (OutboundAdmission.this) {
                completed = true;
                settle();
            }
        }

        void unknown() {
            synchronized (OutboundAdmission.this) {
                unknown = true;
                failed = true;
            }
        }

        private void settle() {
            if (settled || unknown || !released || !completed) return;
            settled = true;
            owner.bytes -= size;
            owner.messages--;
            bytes -= size;
            messages--;
        }
    }
}
