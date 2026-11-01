package org.documentknowledge.browserbff.bootstrap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.WrappedByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutboundWritesTest {
    @Test void completedWriteReleasesOnlyAfterActualPrivateRelease() {
        try (var fixture = new Fixture(() -> true)) {
            var source = Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
            var caller = fixture.channel.newPromise();
            fixture.channel.pipeline().writeAndFlush(source, caller);
            var write = fixture.held.getFirst();
            assertEquals(0, source.refCnt());
            assertTrue(write.bytes.isDirect());
            assertNotSame(caller, write.promise);
            assertFalse(write.promise.isCancellable());
            assertFalse(caller.isDone());
            assertEquals(3, fixture.budget.snapshot().bytes());
            assertTrue(write.bytes.release());
            assertFalse(caller.isDone());
            write.promise.setSuccess();
            assertTrue(caller.isSuccess());
            assertEquals(0, fixture.budget.snapshot().bytes());
            assertFalse(fixture.budget.failed());
        }
    }

    @Test void cancelledAndVoidCallersNeverCancelTheInternalOwner() {
        for (boolean voidCaller : new boolean[] {false, true}) {
            try (var fixture = new Fixture(() -> true)) {
                var caller = voidCaller ? fixture.channel.voidPromise() : fixture.channel.newPromise();
                fixture.channel.pipeline().writeAndFlush(Unpooled.EMPTY_BUFFER, caller);
                var write = fixture.held.getFirst();
                if (!voidCaller) assertTrue(caller.cancel(false));
                assertFalse(write.promise.cancel(false));
                assertFalse(write.promise.isDone());
                assertEquals(1, fixture.budget.snapshot().messages());
                assertEquals(1, write.bytes.capacity());
                assertTrue(write.bytes.release());
                write.promise.setSuccess();
                assertEquals(0, fixture.budget.snapshot().messages());
                assertFalse(fixture.budget.failed());
            }
        }
    }

    @Test void prematureTransportSuccessCannotMaskMissingReleaseOrLeaveCallerPending() {
        try (var fixture = new Fixture(() -> true)) {
            var caller = fixture.channel.newPromise();
            fixture.channel.pipeline().writeAndFlush(Unpooled.wrappedBuffer(new byte[] {1}), caller);
            var write = fixture.held.getFirst();
            write.promise.setSuccess();
            assertTrue(caller.isDone());
            assertFalse(caller.isSuccess());
            assertInstanceOf(IOException.class, caller.cause());
            assertTrue(fixture.budget.failed());
            assertFalse(fixture.channel.isOpen());
            assertEquals(1, fixture.budget.snapshot().bytes());
            assertEquals(1, fixture.budget.snapshot().unresolvedMessages());
            write.bytes.release();
            assertEquals(1, fixture.budget.snapshot().bytes());
        }
    }

    @Test void provenTransportFailureSettlesWithoutPoisoningPolicy() {
        try (var fixture = new Fixture(() -> true)) {
            var caller = fixture.channel.newPromise();
            fixture.channel.pipeline().writeAndFlush(Unpooled.wrappedBuffer(new byte[] {1}), caller);
            var write = fixture.held.getFirst();
            write.bytes.release();
            var failure = new IOException("synthetic disconnect");
            write.promise.setFailure(failure);
            assertSame(failure, caller.cause());
            assertFalse(fixture.channel.isOpen());
            assertFalse(fixture.budget.failed());
            assertEquals(0, fixture.budget.snapshot().messages());
            assertEquals(0, fixture.invalid);
        }
    }

    @Test void byteExhaustionRefusesOnlyNewOwnerAndKeepsHeldCharge() {
        try (var fixture = new Fixture(() -> true)) {
            fixture.channel.pipeline().writeAndFlush(Unpooled.buffer(OutboundAdmission.CONNECTION_BYTES)
                .writeZero(OutboundAdmission.CONNECTION_BYTES), fixture.channel.newPromise());
            var refused = Unpooled.wrappedBuffer(new byte[] {1});
            var caller = fixture.channel.newPromise();
            fixture.channel.pipeline().writeAndFlush(refused, caller);
            assertEquals(0, refused.refCnt());
            assertTrue(caller.isDone());
            assertFalse(caller.isSuccess());
            assertEquals(1, fixture.held.size());
            assertEquals(OutboundAdmission.CONNECTION_BYTES, fixture.budget.snapshot().bytes());
            assertFalse(fixture.budget.failed());
            assertEquals(0, fixture.invalid);
        }
    }

    @Test void zeroLengthMessageExhaustionIsFinite() {
        try (var fixture = new Fixture(() -> true)) {
            for (int i = 0; i < OutboundAdmission.CONNECTION_MESSAGES; i++)
                fixture.channel.pipeline().writeAndFlush(Unpooled.EMPTY_BUFFER, fixture.channel.newPromise());
            var caller = fixture.channel.newPromise();
            fixture.channel.pipeline().writeAndFlush(Unpooled.EMPTY_BUFFER, caller);
            assertTrue(caller.isDone());
            assertEquals(128, fixture.held.size());
            assertEquals(128, fixture.budget.snapshot().messages());
            assertFalse(fixture.budget.failed());
        }
    }

    @Test void unknownTypeOrProfileFailureCannotReachHead() {
        for (int mode = 0; mode < 4; mode++) {
            int selected = mode;
            try (var fixture = new Fixture(() -> {
                if (selected == 2) throw new IllegalStateException("synthetic profile failure");
                return selected != 1;
            })) {
                if (mode == 3) fixture.budget.fail();
                var caller = fixture.channel.newPromise();
                Object message = mode == 0 ? "unsupported" : Unpooled.wrappedBuffer(new byte[] {1});
                fixture.channel.pipeline().writeAndFlush(message, caller);
                assertTrue(caller.isDone());
                assertTrue(fixture.held.isEmpty());
                assertTrue(fixture.budget.failed());
                assertTrue(fixture.invalid > 0);
            }
        }
    }

    @Test void refusedReleaseExceptionIsSeparatelyRetained() {
        for (boolean knownSize : new boolean[] {false, true}) {
          var original = Unpooled.wrappedBuffer(new byte[] {1});
          try (var fixture = new Fixture(() -> knownSize)) {
            if (knownSize) fixture.channel.pipeline().writeAndFlush(
                Unpooled.buffer(OutboundAdmission.CONNECTION_BYTES).writeZero(OutboundAdmission.CONNECTION_BYTES),
                fixture.channel.newPromise());
            var faulty = new WrappedByteBuf(original) {
                @Override public boolean release() { throw new IllegalStateException("synthetic refused failure"); }
            };
            var caller = fixture.channel.newPromise();
            fixture.channel.pipeline().writeAndFlush(faulty, caller);
            assertTrue(caller.isDone());
            assertEquals(1, fixture.budget.snapshot().unresolvedMessages());
            assertEquals(knownSize ? 0 : 1, fixture.budget.snapshot().unresolvedUnknownSizeMessages());
            assertEquals(knownSize ? 1 : 0, fixture.budget.snapshot().unresolvedBytes());
            assertTrue(fixture.budget.failed());
          } finally { original.release(); }
        }
    }

    @Test void failedCopyCannotLeaveCallerPendingOrPermitNewAdmission() {
        try (var fixture = new Fixture(() -> true)) {
            fixture.channel.config().setAllocator(OwnedWriteBufferTest.allocator(() -> {
                throw new IllegalStateException("synthetic allocation failure");
            }));
            var source = Unpooled.wrappedBuffer(new byte[] {1});
            var caller = fixture.channel.newPromise();
            fixture.channel.pipeline().writeAndFlush(source, caller);
            assertTrue(caller.isDone());
            assertFalse(caller.isSuccess());
            assertEquals(0, source.refCnt());
            assertTrue(fixture.held.isEmpty());
            assertTrue(fixture.budget.failed());
            assertEquals(1, fixture.budget.snapshot().bytes());
        }
    }

    @Test void unreadableOfferedSizeRemainsExplicitlyUnknownEvenAfterKnownCleanup() {
        var source = new io.netty.buffer.UnpooledHeapByteBuf(
            io.netty.buffer.UnpooledByteBufAllocator.DEFAULT, 1, 1) {
            @Override public int readableBytes() { throw new IllegalStateException("synthetic size failure"); }
        };
        try (var fixture = new Fixture(() -> true)) {
            var caller = fixture.channel.newPromise();
            fixture.channel.pipeline().writeAndFlush(source, caller);
            assertTrue(caller.isDone());
            assertEquals(0, source.refCnt());
            assertEquals(1, fixture.budget.snapshot().unresolvedUnknownSizeMessages());
            assertEquals(0, fixture.budget.snapshot().unresolvedBytes());
            assertEquals(0, fixture.budget.snapshot().messages());
            assertTrue(fixture.budget.failed());
        } finally { if (source.refCnt() > 0) source.release(); }
    }

    private record Write(ByteBuf bytes, ChannelPromise promise) { }

    private static final class Fixture implements AutoCloseable {
        final OutboundAdmission budget = new OutboundAdmission();
        final ArrayList<Write> held = new ArrayList<>();
        final EmbeddedChannel channel;
        int invalid;

        Fixture(BooleanSupplier profile) {
            var writes = new OutboundWrites(budget, profile, () -> invalid++);
            channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    held.add(new Write((ByteBuf) message, promise));
                }
            }, new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    writes.write(context, message, promise);
                }
            });
        }

        @Override public void close() {
            for (var write : held) {
                if (write.bytes.refCnt() > 0) write.bytes.release(write.bytes.refCnt());
                write.promise.tryFailure(new IOException("synthetic fixture shutdown"));
            }
            channel.finishAndReleaseAll();
        }
    }
}
