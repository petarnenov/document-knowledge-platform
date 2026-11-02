package org.documentknowledge.browserbff.adapters.outbound;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamReadLifecycleTest {
    @Test void successfulCloseCannotSettleAnActiveReader() {
        var calls = new AtomicInteger();
        var owner = new StreamReadLifecycle(calls::incrementAndGet);
        assertTrue(owner.beginRead());
        assertTrue(owner.beginRead());
        assertTrue(owner.beginClose());
        owner.finishClose(true);
        assertFalse(owner.beginRead());
        assertEquals(2, owner.snapshot().activeReads());
        assertTrue(owner.snapshot().closeSucceeded());
        assertFalse(owner.snapshot().settled());
        owner.endRead();
        assertEquals(0, calls.get());
        owner.endRead();
        assertEquals(1, calls.get());
        assertTrue(owner.snapshot().settled());
        assertFalse(owner.beginClose());
        owner.finishClose(true);
        assertEquals(1, calls.get());
    }

    @Test void readExitBeforeCloseStillRequiresKnownCloseCompletion() {
        var calls = new AtomicInteger();
        var owner = new StreamReadLifecycle(calls::incrementAndGet);
        assertTrue(owner.beginRead());
        owner.endRead();
        assertEquals(0, calls.get());
        assertTrue(owner.beginClose());
        assertFalse(owner.snapshot().closeSucceeded());
        assertEquals(0, calls.get());
        owner.finishClose(true);
        assertEquals(1, calls.get());
    }

    @Test void failedCloseRemainsUnknownDespiteReadExitAndRepeatedCompletion() {
        var calls = new AtomicInteger();
        var owner = new StreamReadLifecycle(calls::incrementAndGet);
        assertTrue(owner.beginRead());
        owner.beginClose();
        owner.finishClose(false);
        owner.endRead();
        owner.finishClose(true);
        assertTrue(owner.snapshot().closeFailed());
        assertFalse(owner.snapshot().closeSucceeded());
        assertFalse(owner.snapshot().settled());
        assertEquals(0, calls.get());
    }

    @Test void completionCallbackDoesNotHoldTheLifecycleLock() throws Exception {
        var reference = new AtomicReference<StreamReadLifecycle>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = new StreamReadLifecycle(() -> {
                try {
                    var snapshot = executor.submit(() -> reference.get().snapshot()).get(1, TimeUnit.SECONDS);
                    assertTrue(snapshot.settled());
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            reference.set(owner);
            owner.beginClose();
            owner.finishClose(true);
        }
    }

    @Test void concurrentReaderExitsReleaseOnlyOnce() throws Exception {
        var calls = new AtomicInteger();
        var owner = new StreamReadLifecycle(calls::incrementAndGet);
        for (int i = 0; i < 64; i++) assertTrue(owner.beginRead());
        owner.beginClose();
        owner.finishClose(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 64; i++) tasks.add(executor.submit(owner::endRead));
            for (var task : tasks) task.get(2, TimeUnit.SECONDS);
        }
        assertEquals(0, owner.snapshot().activeReads());
        assertEquals(1, calls.get());
    }

    @Test void invalidLifecycleCallsCannotUnderflowOrManufactureCompletion() {
        assertThrows(NullPointerException.class, () -> new StreamReadLifecycle(null));
        var owner = new StreamReadLifecycle(() -> fail("No settlement expected"));
        assertThrows(IllegalStateException.class, owner::endRead);
        assertThrows(IllegalStateException.class, () -> owner.finishClose(true));
        assertEquals(0, owner.snapshot().activeReads());
        assertFalse(owner.snapshot().settled());
    }
}
