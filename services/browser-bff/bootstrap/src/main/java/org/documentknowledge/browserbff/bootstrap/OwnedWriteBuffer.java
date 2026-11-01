package org.documentknowledge.browserbff.bootstrap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.WrappedByteBuf;

/** The pinned NIO head preserves this private-direct owner instead of making an invisible copy. */
final class OwnedWriteBuffer extends WrappedByteBuf {
    private final OutboundAdmission.Charge charge;
    private volatile boolean released;
    private volatile boolean releaseFailed;

    private OwnedWriteBuffer(ByteBuf buffer, OutboundAdmission.Charge charge) {
        super(buffer);
        this.charge = charge;
    }

    static OwnedWriteBuffer copy(ByteBuf original, ByteBufAllocator allocator,
                                 OutboundAdmission budget, OutboundAdmission.Charge charge) {
        int size = charge.size();
        ByteBuf copy = null;
        boolean releaseAttempted = false;
        boolean originalReleased = false;
        try {
            if (original.readableBytes() != size) throw new IllegalArgumentException("Write charge mismatch");
            int capacity = Math.max(1, size);
            copy = allocator.directBuffer(capacity, capacity);
            if (!copy.isDirect()) throw new IllegalStateException("Unsupported write allocator");
            copy.writeBytes(original, original.readerIndex(), size);
            releaseAttempted = true;
            original.release();
            originalReleased = true;
            return new OwnedWriteBuffer(copy, charge);
        } catch (RuntimeException | Error failure) {
            charge.unknown();
            if (!originalReleased) {
                if (releaseAttempted) budget.unresolved(original, size);
                else {
                    try { original.release(); }
                    catch (RuntimeException | Error releaseFailure) { budget.unresolved(original, size); }
                }
            }
            if (copy != null) {
                try { if (!copy.release()) budget.unresolved(copy, size); }
                catch (RuntimeException | Error releaseFailure) { budget.unresolved(copy, size); }
            }
            throw failure;
        }
    }

    boolean releasedSuccessfully() { return released && !releaseFailed; }

    @Override public boolean release() { return release(1); }

    @Override public boolean release(int decrement) {
        try {
            boolean deallocated = buf.release(decrement);
            if (deallocated && !releaseFailed) {
                released = true;
                charge.released();
            }
            return deallocated;
        } catch (RuntimeException | Error failure) {
            releaseFailed = true;
            charge.unknown(this);
            throw failure;
        }
    }
}
