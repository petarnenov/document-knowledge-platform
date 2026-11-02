package org.documentknowledge.browserbff.bootstrap;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Invoked only by the first raw guard, after encoding and immediately before the pinned NIO head. */
final class OutboundWrites {
    static final int LOW_WATERMARK = 32768;
    static final int HIGH_WATERMARK = 65536;
    private final OutboundAdmission budget;
    private final OutboundAdmission.Connection connection;
    private final BooleanSupplier profile;
    private final Runnable invalid;

    OutboundWrites(OutboundAdmission budget, BooleanSupplier profile, Runnable invalid) {
        this.budget = budget;
        this.connection = budget.connection();
        this.profile = profile;
        this.invalid = invalid;
    }

    void write(ChannelHandlerContext context, Object message, ChannelPromise caller) {
        boolean supported;
        try { supported = profile.getAsBoolean(); }
        catch (RuntimeException | Error failure) { supported = false; }
        if (!(message instanceof ByteBuf bytes) || !supported || budget.failed()) {
            budget.fail();
            refuse(context, message, caller, -1);
            invalid.run();
            return;
        }
        final int size;
        try { size = bytes.readableBytes(); }
        catch (RuntimeException | Error failure) {
            budget.unresolvedUnknown(message);
            refuse(context, message, caller, -1);
            invalid.run();
            return;
        }
        var charge = connection.tryRetain(size);
        if (charge == null) { refuse(context, message, caller, size); return; }
        final OwnedWriteBuffer owned;
        try { owned = OwnedWriteBuffer.copy(bytes, context.alloc(), budget, charge); }
        catch (RuntimeException | Error failure) {
            caller.tryFailure(unavailable());
            invalid.run();
            context.close();
            return;
        }
        var internal = context.newPromise();
        internal.setUncancellable();
        internal.addListener(done -> {
            if (!owned.releasedSuccessfully()) {
                charge.unknown(owned);
                charge.completed();
                caller.tryFailure(unavailable());
                invalid.run();
                context.close();
            } else {
                charge.completed();
                if (done.isSuccess()) caller.trySuccess();
                else {
                    caller.tryFailure(done.cause());
                    context.close();
                }
            }
        });
        try { context.write(owned, internal); }
        catch (RuntimeException | Error failure) {
            charge.unknown(owned);
            internal.tryFailure(unavailable());
            invalid.run();
            context.close();
        }
    }

    private void refuse(ChannelHandlerContext context, Object message, ChannelPromise caller, int size) {
        try { ReferenceCountUtil.release(message); }
        catch (RuntimeException | Error failure) {
            if (size < 0) budget.unresolvedUnknown(message);
            else budget.unresolved(message, size);
            invalid.run();
        }
        caller.tryFailure(unavailable());
        context.close();
    }

    private static IOException unavailable() { return new IOException("BFF_WRITE_UNAVAILABLE"); }
}
