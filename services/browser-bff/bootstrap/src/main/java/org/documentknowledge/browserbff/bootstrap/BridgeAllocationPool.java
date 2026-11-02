package org.documentknowledge.browserbff.bootstrap;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledDirectByteBuf;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Owned bridge payload only. Producer/IO admission and route integration remain separate. */
final class BridgeAllocationPool {
    static final int MAX_CAPACITY = 8192;
    static final long APPLICATION_BYTES = 8L * 1024 * 1024;
    static final int APPLICATION_ALLOCATIONS = 1024;
    static final long PRODUCER_BYTES = 64L * 1024;
    static final int PRODUCER_ALLOCATIONS = 8;

    private final Supplier<Arena> arenas;
    private final IdentityHashMap<Charge, Boolean> retained = new IdentityHashMap<>();
    private long bytes;
    private boolean failed;

    BridgeAllocationPool() { this(Arena::ofShared); }

    // Package-only seam for real confined-arena and construction-error controls.
    BridgeAllocationPool(Supplier<Arena> arenas) { this.arenas = Objects.requireNonNull(arenas); }

    Owner owner(Runnable onReleased) { return new Owner(Objects.requireNonNull(onReleased)); }

    synchronized Snapshot snapshot() {
        int unknown = 0;
        for (var charge : retained.keySet()) if (charge.unknown) unknown++;
        return new Snapshot(bytes, retained.size(), unknown, failed);
    }

    record Snapshot(long bytes, int allocations, int unknown, boolean failed) { }

    final class Owner {
        private final Runnable onReleased;
        private final Allocator allocator = new Allocator(this);
        private long bytes;
        private int allocations;

        private Owner(Runnable onReleased) { this.onReleased = onReleased; }

        Allocator allocator() { return allocator; }

        long bytes() { synchronized (BridgeAllocationPool.this) { return bytes; } }
        int allocations() { synchronized (BridgeAllocationPool.this) { return allocations; } }

        private Charge retain(int capacity) {
            long reserved = 2L * Math.max(1, capacity);
            synchronized (BridgeAllocationPool.this) {
                if (failed || reserved > APPLICATION_BYTES - BridgeAllocationPool.this.bytes
                    || retained.size() >= APPLICATION_ALLOCATIONS
                    || reserved > PRODUCER_BYTES - bytes || allocations >= PRODUCER_ALLOCATIONS)
                    throw new CapacityExceeded();
                var charge = new Charge(this, reserved);
                retained.put(charge, Boolean.TRUE);
                BridgeAllocationPool.this.bytes += reserved;
                bytes += reserved;
                allocations++;
                return charge;
            }
        }
    }

    /** Capacity refusal is known before allocation and does not latch the pool failed. */
    static final class CapacityExceeded extends IllegalStateException {
        private CapacityExceeded() { super("Bridge allocation capacity unavailable"); }
    }

    final class Allocator extends AbstractByteBufAllocator {
        private final Owner owner;
        private Allocator(Owner owner) { super(true); this.owner = owner; }

        @Override public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
            if (initialCapacity < 0 || initialCapacity != maxCapacity || maxCapacity > MAX_CAPACITY)
                throw new IllegalArgumentException("Fixed bridge buffer profile required");
            var charge = owner.retain(initialCapacity);
            try {
                charge.arena = Objects.requireNonNull(arenas.get());
                var memory = charge.arena.allocate(Math.max(1, initialCapacity));
                ByteBuffer view = memory.asByteBuffer();
                view.limit(initialCapacity);
                var buffer = new OwnedBuffer(this, view, initialCapacity, charge);
                charge.buffer = buffer;
                return buffer;
            } catch (RuntimeException | Error failure) {
                charge.constructionFailed(failure);
                throw failure;
            }
        }

        @Override public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
            throw new UnsupportedOperationException("Bridge buffers require owned direct storage");
        }
        @Override protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            throw new UnsupportedOperationException("Bridge buffers require owned direct storage");
        }
        @Override protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return directBuffer(initialCapacity, maxCapacity);
        }
        @Override public boolean isDirectBufferPooled() { return false; }
    }

    private final class Charge {
        private final Owner owner;
        private final long reserved;
        // The retained charge strongly identifies every unproved arena and private buffer.
        private Arena arena;
        private ByteBuf buffer;
        private boolean unknown;
        private boolean settled;

        private Charge(Owner owner, long reserved) { this.owner = owner; this.reserved = reserved; }

        private void unknown() {
            synchronized (BridgeAllocationPool.this) {
                unknown = true;
                failed = true;
            }
        }

        private void closeArena() {
            arena.close();
            if (arena.scope().isAlive()) throw new IllegalStateException("Bridge arena remains alive");
        }

        private void released() {
            synchronized (BridgeAllocationPool.this) {
                if (unknown || settled) return;
                settled = true;
                retained.remove(this);
                bytes -= reserved;
                owner.bytes -= reserved;
                owner.allocations--;
            }
            // Notification failure cannot turn actual arena deallocation into live allocation.
            owner.onReleased.run();
        }

        private void constructionFailed(Throwable original) {
            if (arena == null) { unknown(); return; }
            try { closeArena(); }
            catch (RuntimeException | Error closeFailure) {
                unknown();
                if (closeFailure != original) original.addSuppressed(closeFailure);
                return;
            }
            try { released(); }
            catch (RuntimeException | Error notificationFailure) {
                if (notificationFailure != original) original.addSuppressed(notificationFailure);
            }
        }
    }

    private static final class OwnedBuffer extends UnpooledDirectByteBuf {
        private final Charge charge;

        private OwnedBuffer(Allocator allocator, ByteBuffer view, int capacity, Charge charge) {
            super(allocator, view, capacity);
            this.charge = charge;
            clear();
        }

        @Override public ByteBuf capacity(int newCapacity) {
            if (newCapacity != capacity()) throw new UnsupportedOperationException("Fixed bridge capacity");
            return this;
        }

        @Override public ByteBuf copy(int index, int length) {
            checkIndex(index, length);
            var copy = alloc().directBuffer(length, length);
            try { copy.writeBytes(this, index, length); return copy; }
            catch (RuntimeException | Error failure) {
                try { copy.release(); }
                catch (RuntimeException | Error closeFailure) {
                    if (closeFailure != failure) failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        @Override protected void deallocate() {
            try {
                super.deallocate();
                charge.closeArena();
            } catch (RuntimeException | Error failure) {
                charge.unknown();
                throw failure;
            }
            charge.released();
        }
    }
}
