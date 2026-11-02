package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.buffer.netty.NettyReadBufferFactory;
import io.netty.buffer.ByteBuf;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BridgeAllocationPoolTest {
    @Test void retainedViewsKeepTheActualSharedArenaAlive() {
        var pool = new BridgeAllocationPool();
        var callbacks = new AtomicInteger();
        var owner = pool.owner(callbacks::incrementAndGet);
        var buffer = owner.allocator().directBuffer(4, 4).writeInt(0x01020304);
        var stale = buffer.nioBuffer(0, 4);
        var scope = MemorySegment.ofBuffer(stale).scope();
        var duplicate = buffer.retainedDuplicate();
        var part = buffer.readRetainedSlice(2);
        assertFalse(buffer.release());
        assertFalse(duplicate.release());
        assertTrue(scope.isAlive());
        assertEquals(1, owner.allocations());
        assertEquals(8, owner.bytes());
        assertTrue(part.release());
        assertFalse(scope.isAlive());
        assertThrows(IllegalStateException.class, () -> stale.get(0));
        assertEquals(0, pool.snapshot().allocations());
        assertEquals(1, callbacks.get());
        assertThrows(io.netty.util.IllegalReferenceCountException.class, part::release);
        assertEquals(0, owner.bytes());
        assertEquals(1, callbacks.get());
    }

    @Test void actualMicronautMovesSplitsAndCopiesReleaseTheirOwnArenas() {
        assertActualMicronautConsumption();
    }

    static void assertActualMicronautConsumption() {
        var pool = new BridgeAllocationPool();
        var owner = pool.owner(() -> {});
        var original = owner.allocator().directBuffer(4, 4).writeInt(0x01020304);
        var originalScope = MemorySegment.ofBuffer(original.nioBuffer()).scope();
        var body = NettyReadBufferFactory.of(owner.allocator()).adapt(original);
        boolean strict = Boolean.getBoolean("io.micronaut.buffer.netty.NettyReadBuffer.STRICT_REFCNT");
        assertEquals(!strict, originalScope.isAlive());
        assertEquals(strict ? 0 : 1, original.refCnt());
        assertEquals(1, pool.snapshot().allocations());
        var duplicate = body.duplicate();
        var part = body.split(2);
        var moved = body.move();
        body.close();
        assertArrayEquals(new byte[] {1, 2}, part.toArray());
        duplicate.close();
        assertEquals(1, pool.snapshot().allocations());
        assertArrayEquals(new byte[] {3, 4}, moved.toArray());
        assertEquals(0, pool.snapshot().allocations());
        assertEquals(0, pool.snapshot().bytes());
        assertFalse(originalScope.isAlive());
        assertFalse(pool.snapshot().failed());
    }

    @Test void fixedCopiesAreIndependentAndUnsupportedStorageRequestsDoNotMutateOwnership() {
        var pool = new BridgeAllocationPool();
        var owner = pool.owner(() -> {});
        var allocator = owner.allocator();
        assertFalse(allocator.isDirectBufferPooled());
        try {
            for (int[] capacity : new int[][] {{-1, -1}, {1, 2}, {8193, 8193}})
                assertThrows(IllegalArgumentException.class, () -> allocator.directBuffer(capacity[0], capacity[1]));
            assertThrows(UnsupportedOperationException.class, () -> allocator.heapBuffer(0, 0));
            var original = allocator.directBuffer(4, 4).writeInt(0x01020304);
            var copy = original.copy(1, 2);
            try {
                assertEquals(2, pool.snapshot().allocations());
                assertEquals(12, pool.snapshot().bytes());
                assertSame(original, original.capacity(4));
                assertThrows(UnsupportedOperationException.class, () -> original.capacity(2));
                assertThrows(IndexOutOfBoundsException.class, () -> original.copy(3, 2));
                original.release();
                assertEquals(1, owner.allocations());
                assertEquals(2, copy.readByte()); assertEquals(3, copy.readByte());
            } finally { if (original.refCnt() > 0) original.release(); copy.release(); }
        } finally { assertEquals(0, pool.snapshot().allocations()); }
        assertFalse(pool.snapshot().failed());
    }

    @Test void byteAndMessageCapacityAreIndependentBeforeAllocation() {
        var pool = new BridgeAllocationPool();
        var buffers = new ArrayList<ByteBuf>();
        try {
            for (int i = 0; i < 128; i++) {
                var owner = pool.owner(() -> {});
                for (int j = 0; j < 4; j++) buffers.add(owner.allocator().directBuffer(8192, 8192));
                assertThrows(BridgeAllocationPool.CapacityExceeded.class, () -> owner.allocator().directBuffer(1, 1));
            }
            assertEquals(BridgeAllocationPool.APPLICATION_BYTES, pool.snapshot().bytes());
            assertThrows(BridgeAllocationPool.CapacityExceeded.class, () -> pool.owner(() -> {}).allocator().directBuffer(1, 1));
            assertFalse(pool.snapshot().failed());
            buffers.removeLast().release();
            var recovered = pool.owner(() -> {}).allocator().directBuffer(8192, 8192);
            recovered.release();
        } finally { buffers.forEach(ByteBuf::release); }
        assertEquals(0, pool.snapshot().bytes());
        buffers.clear();
        try {
            for (int i = 0; i < 128; i++) {
                var owner = pool.owner(() -> {});
                for (int j = 0; j < 8; j++) buffers.add(owner.allocator().directBuffer(0, 0));
                assertThrows(BridgeAllocationPool.CapacityExceeded.class, () -> owner.allocator().directBuffer(0, 0));
            }
            assertEquals(1024, pool.snapshot().allocations());
            assertEquals(2048, pool.snapshot().bytes());
            assertThrows(BridgeAllocationPool.CapacityExceeded.class, () -> pool.owner(() -> {}).allocator().directBuffer(0, 0));
            assertFalse(pool.snapshot().failed());
        } finally { buffers.forEach(ByteBuf::release); }
        assertEquals(0, pool.snapshot().allocations());
    }

    @Test void actualWrongThreadCloseCannotFreeCapacityOrBeRepairedByLaterCleanup() throws Exception {
        var confined = Arena.ofConfined();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pool = new BridgeAllocationPool(() -> confined);
            var owner = pool.owner(() -> fail("Unproved arena must not notify release"));
            var buffer = owner.allocator().directBuffer(4, 4);
            var failure = assertThrows(ExecutionException.class,
                () -> executor.submit((Callable<Boolean>) buffer::release).get(2, TimeUnit.SECONDS));
            assertInstanceOf(WrongThreadException.class, failure.getCause());
            assertTrue(confined.scope().isAlive());
            assertEquals(1, pool.snapshot().unknown());
            assertEquals(8, pool.snapshot().bytes());
            assertTrue(pool.snapshot().failed());
            assertThrows(BridgeAllocationPool.CapacityExceeded.class, () -> owner.allocator().directBuffer(0, 0));
            confined.close();
            assertFalse(confined.scope().isAlive());
            assertThrows(io.netty.util.IllegalReferenceCountException.class, buffer::release);
            assertEquals(1, pool.snapshot().unknown());
            assertEquals(1, pool.snapshot().allocations());
        } finally { if (confined.scope().isAlive()) confined.close(); }
    }

    @Test void knownConstructionFailureClosesItsArenaAndUnknownFactoryFailureStaysCharged() {
        var callbacks = new AtomicInteger();
        var known = new BridgeAllocationPool(() -> new Arena() {
            private final Arena delegate = Arena.ofShared();
            @Override public MemorySegment allocate(long size, long alignment) { return delegate.allocate(-1, alignment); }
            @Override public MemorySegment.Scope scope() { return delegate.scope(); }
            @Override public void close() { delegate.close(); }
        });
        assertThrows(IllegalArgumentException.class, () -> known.owner(callbacks::incrementAndGet).allocator().directBuffer(4, 4));
        assertEquals(0, known.snapshot().allocations());
        assertEquals(1, callbacks.get());
        assertFalse(known.snapshot().failed());
        var original = new IllegalStateException("synthetic arena creation failure");
        var unknown = new BridgeAllocationPool(() -> { throw original; });
        assertSame(original, assertThrows(IllegalStateException.class,
            () -> unknown.owner(() -> fail("No known arena close")).allocator().directBuffer(4, 4)));
        assertEquals(1, unknown.snapshot().unknown());
        assertTrue(unknown.snapshot().failed());
        assertThrows(NullPointerException.class, () -> new BridgeAllocationPool(null));
        assertThrows(NullPointerException.class, () -> known.owner(null));
    }

    @Test void callbacksRunOutsidePoolLockAndConcurrentOwnersRemainConsistent() throws Exception {
        var pool = new BridgeAllocationPool();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = pool.owner(() -> {
                try { executor.submit((Callable<BridgeAllocationPool.Snapshot>) pool::snapshot).get(1, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            owner.allocator().directBuffer(1, 1).release();
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++) tasks.add(executor.submit(() -> {
                var concurrent = pool.owner(() -> {});
                for (int j = 0; j < 64; j++) concurrent.allocator().directBuffer(16, 16).release();
                assertEquals(0, concurrent.allocations());
            }));
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, pool.snapshot().allocations());
        assertEquals(0, pool.snapshot().bytes());
        assertFalse(pool.snapshot().failed());
    }

    @Test void returningFromCloseWithoutInvalidatingScopeCannotReleaseCapacity() {
        try (var actual = Arena.ofShared()) {
            var pool = new BridgeAllocationPool(() -> new Arena() {
                @Override public MemorySegment allocate(long size, long alignment) { return actual.allocate(size, alignment); }
                @Override public MemorySegment.Scope scope() { return actual.scope(); }
                @Override public void close() { /* Deliberate unit control: underlying real arena stays alive. */ }
            });
            var buffer = pool.owner(() -> fail("Scope remains alive")).allocator().directBuffer(4, 4);
            assertThrows(IllegalStateException.class, buffer::release);
            assertTrue(actual.scope().isAlive());
            assertEquals(1, pool.snapshot().unknown());
            assertEquals(8, pool.snapshot().bytes());
            assertTrue(pool.snapshot().failed());
        }
    }

    @Test void constructionFailurePreservesItsCauseIfCleanupOrNotificationFails() {
        for (boolean failClose : new boolean[] {false, true}) {
            var actual = Arena.ofShared();
            try {
                var original = new IllegalArgumentException("synthetic construction failure");
                var secondary = new IllegalStateException("synthetic cleanup or notification failure");
                var pool = new BridgeAllocationPool(() -> new Arena() {
                    @Override public MemorySegment allocate(long size, long alignment) { throw original; }
                    @Override public MemorySegment.Scope scope() { return actual.scope(); }
                    @Override public void close() { if (failClose) throw secondary; actual.close(); }
                });
                assertSame(original, assertThrows(IllegalArgumentException.class,
                    () -> pool.owner(() -> { throw secondary; }).allocator().directBuffer(4, 4)));
                assertArrayEquals(new Throwable[] {secondary}, original.getSuppressed());
                assertEquals(failClose ? 1 : 0, pool.snapshot().allocations());
                assertEquals(failClose, pool.snapshot().failed());
                assertEquals(failClose, actual.scope().isAlive());
            } finally { if (actual.scope().isAlive()) actual.close(); }
        }
    }
}
