package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class IngressAdmissionTest {
    @Test void simultaneousAdmissionsCannotExceedEitherGlobalLimit() throws Exception {
        var budget = new IngressAdmission();
        var start = new CountDownLatch(1);
        var admitted = new java.util.concurrent.ConcurrentLinkedQueue<IngressAdmission.Connection>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 128; i++) futures.add(executor.submit(() -> {
                start.await();
                var connection = budget.tryOpen();
                if (connection != null) admitted.add(connection);
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get();
            assertEquals(32, admitted.size());
            var acceptedRequests = new java.util.concurrent.atomic.AtomicInteger();
            futures.clear();
            for (int i = 0; i < 128; i++) futures.add(executor.submit(() -> {
                if (admitted.element().tryRetain(new Object())) acceptedRequests.incrementAndGet();
            }));
            for (var future : futures) future.get();
            assertEquals(64, acceptedRequests.get());
            assertEquals(new IngressAdmission.Snapshot(32, 64), budget.snapshot());
            admitted.forEach(IngressAdmission.Connection::confirmTeardown);
            assertEquals(new IngressAdmission.Snapshot(0, 0), budget.snapshot());
        }
    }

    @Test void connectionCapacityRequiresConfirmedTeardownAndCannotUnderflow() {
        var budget = new IngressAdmission();
        var connections = new ArrayList<IngressAdmission.Connection>();
        for (int i = 0; i < 32; i++) connections.add(assertNotNullConnection(budget));
        assertNull(budget.tryOpen());
        assertEquals(new IngressAdmission.Snapshot(32, 0), budget.snapshot());
        connections.getFirst().confirmTeardown();
        connections.getFirst().confirmTeardown();
        var replacement = assertNotNullConnection(budget);
        assertNull(budget.tryOpen());
        replacement.confirmTeardown();
        connections.forEach(IngressAdmission.Connection::confirmTeardown);
        assertEquals(new IngressAdmission.Snapshot(0, 0), budget.snapshot());
    }

    @Test void requestCapacityIsSharedAcrossConnectionsAndCountsPipelinedIdentities() {
        var budget = new IngressAdmission();
        var first = assertNotNullConnection(budget);
        var second = assertNotNullConnection(budget);
        var identities = new ArrayList<Object>();
        for (int i = 0; i < 64; i++) {
            Object identity = new Object();
            identities.add(identity);
            assertTrue((i % 2 == 0 ? first : second).tryRetain(identity));
        }
        assertFalse(first.tryRetain(new Object()));
        assertFalse(second.tryRetain(new Object()));
        Runnable disposal = first.bind(identities.getFirst());
        assertNotNull(disposal);
        disposal.run();
        disposal.run();
        assertTrue(second.tryRetain(new Object()));
        assertEquals(new IngressAdmission.Snapshot(2, 64), budget.snapshot());
        first.confirmTeardown();
        second.confirmTeardown();
        assertEquals(new IngressAdmission.Snapshot(0, 0), budget.snapshot());
    }

    @Test void bindingUsesIdentityAndNeverRecreatesReleasedOrForeignOwners() {
        var budget = new IngressAdmission();
        var first = assertNotNullConnection(budget);
        var second = assertNotNullConnection(budget);
        String nativeRequest = new String("same value");
        assertTrue(first.tryRetain(nativeRequest));
        assertFalse(first.tryRetain(nativeRequest));
        assertNull(first.bind(new String("same value")));
        assertNull(second.bind(nativeRequest));
        Runnable disposal = first.bind(nativeRequest);
        assertNotNull(disposal);
        assertNull(first.bind(nativeRequest));
        disposal.run();
        assertNull(first.bind(nativeRequest));
        assertThrows(NullPointerException.class, () -> first.tryRetain(null));
        first.confirmTeardown();
        assertFalse(first.tryRetain(new Object()));
        second.confirmTeardown();
        assertEquals(new IngressAdmission.Snapshot(0, 0), budget.snapshot());
    }

    @Test void teardownFreesOnlyUnboundBodiesAndLateCallbacksReleaseBoundBodiesOnce() {
        var budget = new IngressAdmission();
        var connection = assertNotNullConnection(budget);
        Object bound = new Object();
        Object unbound = new Object();
        assertTrue(connection.tryRetain(bound));
        assertTrue(connection.tryRetain(unbound));
        Runnable disposal = connection.bind(bound);
        assertNotNull(disposal);
        connection.confirmTeardown();
        assertNull(connection.bind(unbound));
        assertEquals(new IngressAdmission.Snapshot(0, 1), budget.snapshot());
        disposal.run();
        disposal.run();
        assertEquals(new IngressAdmission.Snapshot(0, 0), budget.snapshot());
    }

    @Test void concurrentAdmissionDisposalAndTeardownPreserveTheSharedBudget() throws Exception {
        var budget = new IngressAdmission();
        var connection = assertNotNullConnection(budget);
        var ready = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 64; i++) {
                Object nativeRequest = new Object();
                assertTrue(connection.tryRetain(nativeRequest));
                Runnable disposal = connection.bind(nativeRequest);
                assertNotNull(disposal);
                futures.add(executor.submit(() -> {
                    ready.await();
                    disposal.run();
                    disposal.run();
                    connection.confirmTeardown();
                    return null;
                }));
            }
            ready.countDown();
            for (var future : futures) future.get();
        }
        assertEquals(new IngressAdmission.Snapshot(0, 0), budget.snapshot());
    }

    private static IngressAdmission.Connection assertNotNullConnection(IngressAdmission budget) {
        var connection = budget.tryOpen();
        assertNotNull(connection);
        return connection;
    }
}
