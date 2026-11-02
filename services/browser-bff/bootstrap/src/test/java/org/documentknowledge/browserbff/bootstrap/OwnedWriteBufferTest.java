package org.documentknowledge.browserbff.bootstrap;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.WrappedByteBuf;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedWriteBufferTest {
    @Test void privateDirectOwnerAndCompletionAreBothNecessary() {
        var budget = new OutboundAdmission();
        var charge = budget.connection().tryRetain(4);
        var source = Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4});
        var owned = OwnedWriteBuffer.copy(source, UnpooledByteBufAllocator.DEFAULT, budget, charge);
        assertEquals(0, source.refCnt());
        assertTrue(owned.isDirect());
        assertEquals(4, owned.capacity());
        byte[] actual = new byte[4];
        owned.getBytes(0, actual);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, actual);
        charge.completed();
        assertEquals(4, budget.snapshot().bytes());
        assertFalse(owned.releasedSuccessfully());
        assertTrue(owned.release());
        assertTrue(owned.releasedSuccessfully());
        assertEquals(0, budget.snapshot().messages());
    }

    @Test void emptyMessageUsesRealPrivateStorageAndCount() {
        var budget = new OutboundAdmission();
        var charge = budget.connection().tryRetain(0);
        var owned = OwnedWriteBuffer.copy(Unpooled.EMPTY_BUFFER, UnpooledByteBufAllocator.DEFAULT, budget, charge);
        assertEquals(0, owned.readableBytes());
        assertEquals(1, owned.capacity());
        assertEquals(1, owned.refCnt());
        assertEquals(1, budget.snapshot().messages());
        assertTrue(owned.release());
        assertEquals(1, budget.snapshot().messages());
        charge.completed();
        assertEquals(0, budget.snapshot().messages());
    }

    @Test void retainedPrivateReferenceIsNotFinalRelease() {
        var budget = new OutboundAdmission();
        var charge = budget.connection().tryRetain(1);
        var owned = OwnedWriteBuffer.copy(Unpooled.wrappedBuffer(new byte[] {7}),
            UnpooledByteBufAllocator.DEFAULT, budget, charge);
        // Deliberate test-only violation of production's private-owner rule.
        owned.retain();
        assertFalse(owned.release());
        charge.completed();
        assertFalse(owned.releasedSuccessfully());
        assertEquals(1, budget.snapshot().bytes());
        assertTrue(owned.release(1));
        assertEquals(0, budget.snapshot().bytes());
    }

    @Test void releaseExceptionStaysUnknownDespiteLaterSuccessfulRetry() {
        var direct = Unpooled.directBuffer(1, 1);
        var faulty = new WrappedByteBuf(direct) {
            boolean first = true;
            @Override public boolean release(int decrement) {
                if (first) { first = false; throw new IllegalStateException("synthetic release failure"); }
                return super.release(decrement);
            }
        };
        var budget = new OutboundAdmission();
        var charge = budget.connection().tryRetain(1);
        var owned = OwnedWriteBuffer.copy(Unpooled.wrappedBuffer(new byte[] {1}),
            allocator(() -> faulty), budget, charge);
        try {
            assertThrows(IllegalStateException.class, owned::release);
            charge.completed();
            assertTrue(owned.release(1));
            assertFalse(owned.releasedSuccessfully());
            assertTrue(budget.failed());
            assertEquals(1, budget.snapshot().bytes());
        } finally { if (direct.refCnt() > 0) direct.release(); }
    }

    @Test void originalReleaseFailureIsSeparatelyRecordedAndPrivateCopyCleaned() {
        var original = Unpooled.wrappedBuffer(new byte[] {1, 2});
        var faulty = new WrappedByteBuf(original) {
            @Override public boolean release() { throw new IllegalStateException("synthetic original failure"); }
        };
        var copy = Unpooled.directBuffer(2, 2);
        var budget = new OutboundAdmission();
        var charge = budget.connection().tryRetain(2);
        try {
            assertThrows(IllegalStateException.class,
                () -> OwnedWriteBuffer.copy(faulty, allocator(() -> copy), budget, charge));
            assertEquals(0, copy.refCnt());
            assertEquals(1, original.refCnt());
            assertTrue(budget.failed());
            assertEquals(2, budget.snapshot().unresolvedBytes());
            assertEquals(1, budget.snapshot().unresolvedMessages());
            assertEquals(2, budget.snapshot().bytes());
        } finally { original.release(); }
    }

    @Test void allocationOrUnexpectedHeapAllocatorFailsClosedAndCleansKnownOriginal() {
        for (boolean failAllocation : new boolean[] {true, false}) {
            var budget = new OutboundAdmission();
            var charge = budget.connection().tryRetain(1);
            var original = Unpooled.wrappedBuffer(new byte[] {1});
            var heap = Unpooled.buffer(1, 1);
            try {
                assertThrows(IllegalStateException.class, () -> OwnedWriteBuffer.copy(original, allocator(() -> {
                    if (failAllocation) throw new IllegalStateException("synthetic allocation failure");
                    return heap;
                }), budget, charge));
                assertEquals(0, original.refCnt());
                assertTrue(budget.failed());
                assertEquals(1, budget.snapshot().bytes());
                assertEquals(0, budget.snapshot().unresolvedMessages());
                if (!failAllocation) assertEquals(0, heap.refCnt());
            } finally { if (heap.refCnt() > 0) heap.release(); }
        }
    }

    @Test void mismatchedChargeFailsBeforeCopyAndCleansOriginal() {
        var budget = new OutboundAdmission();
        var charge = budget.connection().tryRetain(3);
        var original = Unpooled.wrappedBuffer(new byte[] {1, 2});
        assertThrows(IllegalArgumentException.class, () -> OwnedWriteBuffer.copy(original,
            UnpooledByteBufAllocator.DEFAULT, budget, charge));
        assertEquals(0, original.refCnt());
        assertTrue(budget.failed());
        assertEquals(3, budget.snapshot().bytes());
    }

    @Test void incompleteCleanupRetainsPrivateIdentity() {
        var original = Unpooled.wrappedBuffer(new byte[] {1});
        var faulty = new WrappedByteBuf(original) {
            @Override public boolean release() { throw new IllegalStateException("synthetic original failure"); }
        };
        var privateCopy = Unpooled.directBuffer(1, 1);
        privateCopy.retain();
        var budget = new OutboundAdmission();
        var charge = budget.connection().tryRetain(1);
        try {
            assertThrows(IllegalStateException.class,
                () -> OwnedWriteBuffer.copy(faulty, allocator(() -> privateCopy), budget, charge));
            assertEquals(1, privateCopy.refCnt());
            assertEquals(2, budget.snapshot().unresolvedMessages());
            assertEquals(2, budget.snapshot().unresolvedBytes());
            assertEquals(1, budget.snapshot().bytes());
        } finally {
            original.release();
            privateCopy.release();
        }
    }

    static ByteBufAllocator allocator(Supplier<ByteBuf> supplier) {
        return new AbstractByteBufAllocator(true) {
            @Override protected ByteBuf newHeapBuffer(int initial, int maximum) { throw new UnsupportedOperationException(); }
            @Override protected ByteBuf newDirectBuffer(int initial, int maximum) { return supplier.get(); }
            @Override public boolean isDirectBufferPooled() { return false; }
        };
    }
}
