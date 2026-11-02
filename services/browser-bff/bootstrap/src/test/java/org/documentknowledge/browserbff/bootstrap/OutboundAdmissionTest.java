package org.documentknowledge.browserbff.bootstrap;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutboundAdmissionTest {
    @Test void byteLimitsAreSharedAcrossIndependentConnections() {
        var budget = new OutboundAdmission();
        var first = budget.connection();
        var charge = first.tryRetain(OutboundAdmission.CONNECTION_BYTES);
        assertNotNull(charge);
        assertNull(first.tryRetain(1));
        var held = new ArrayList<OutboundAdmission.Charge>();
        held.add(charge);
        for (int i = 1; i < 8; i++) {
            var next = budget.connection().tryRetain(OutboundAdmission.CONNECTION_BYTES);
            assertNotNull(next);
            held.add(next);
        }
        assertEquals(OutboundAdmission.APPLICATION_BYTES, budget.snapshot().bytes());
        assertNull(budget.connection().tryRetain(1));
        charge.completed();
        assertNull(first.tryRetain(1));
        charge.released();
        assertNotNull(first.tryRetain(1));
        assertEquals(7L * OutboundAdmission.CONNECTION_BYTES + 1, budget.snapshot().bytes());
        assertFalse(budget.failed());
    }

    @Test void emptyMessagesHaveFinitePerConnectionAndApplicationSlots() {
        var budget = new OutboundAdmission();
        var first = budget.connection();
        var charges = new ArrayList<OutboundAdmission.Charge>();
        for (int i = 0; i < OutboundAdmission.CONNECTION_MESSAGES; i++) {
            var charge = first.tryRetain(0);
            assertNotNull(charge);
            charges.add(charge);
        }
        assertNull(first.tryRetain(0));
        for (int connection = 1; connection < 32; connection++) {
            var next = budget.connection();
            for (int i = 0; i < OutboundAdmission.CONNECTION_MESSAGES; i++)
                assertNotNull(next.tryRetain(0));
        }
        assertEquals(0, budget.snapshot().bytes());
        assertEquals(OutboundAdmission.APPLICATION_MESSAGES, budget.snapshot().messages());
        assertNull(budget.connection().tryRetain(0));
        charges.getFirst().released();
        assertNull(first.tryRetain(0));
        charges.getFirst().completed();
        assertNotNull(first.tryRetain(0));
    }

    @Test void bothWitnessesAreRequiredExactlyOnceInEitherOrder() throws Exception {
        var budget = new OutboundAdmission();
        var connection = budget.connection();
        var first = connection.tryRetain(13);
        first.completed();
        first.completed();
        assertEquals(13, budget.snapshot().bytes());
        first.released();
        first.released();
        assertEquals(0, budget.snapshot().messages());
        var next = connection.tryRetain(21);
        next.released();
        assertEquals(21, budget.snapshot().bytes());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 100; i++) tasks.add(workers.submit(() -> {
                next.released();
                next.completed();
            }));
            for (var task : tasks) task.get();
        }
        assertEquals(0, budget.snapshot().bytes());
        assertEquals(0, budget.snapshot().messages());
    }

    @Test void unknownReleaseCannotBeRepairedByLaterCallbacks() {
        var budget = new OutboundAdmission();
        var connection = budget.connection();
        var charge = connection.tryRetain(8192);
        charge.unknown();
        charge.completed();
        charge.released();
        charge.unknown();
        assertTrue(budget.failed());
        assertEquals(8192, budget.snapshot().bytes());
        assertEquals(1, budget.snapshot().messages());
        assertNull(connection.tryRetain(0));
        assertNull(budget.connection().tryRetain(1));
    }

    @Test void unresolvedUnadmittedOwnersAreExplicitAndIdentityBound() {
        var budget = new OutboundAdmission();
        Object first = new Object(), second = new Object();
        budget.unresolved(first, 5);
        budget.unresolved(first, 5);
        budget.unresolved(second, 9);
        assertEquals(14, budget.snapshot().unresolvedBytes());
        assertEquals(2, budget.snapshot().unresolvedMessages());
        assertEquals(0, budget.snapshot().bytes());
        assertEquals(0, budget.snapshot().messages());
        assertTrue(budget.failed());
        assertNull(budget.connection().tryRetain(1));
    }

    @Test void invalidAndOversizedChargesDoNotConsumeCapacity() {
        var budget = new OutboundAdmission();
        var connection = budget.connection();
        assertThrows(IllegalArgumentException.class, () -> connection.tryRetain(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.unresolved(new Object(), -1));
        assertThrows(NullPointerException.class, () -> budget.unresolved(null, 1));
        assertNull(connection.tryRetain(OutboundAdmission.CONNECTION_BYTES + 1));
        assertNotNull(connection.tryRetain(1));
        assertEquals(1, budget.snapshot().bytes());
        assertFalse(budget.failed());
    }

    @Test void unknownSizesAreNeverConfusedWithProvenEmptyOwnersOrRepairedByRetry() {
        var budget = new OutboundAdmission();
        Object empty = new Object(), unknown = new Object();
        budget.unresolved(empty, 0);
        budget.unresolvedUnknown(unknown);
        budget.unresolvedUnknown(unknown);
        budget.unresolved(unknown, 999);
        budget.unresolvedUnknown(empty);
        assertEquals(0, budget.snapshot().unresolvedBytes());
        assertEquals(2, budget.snapshot().unresolvedMessages());
        assertEquals(1, budget.snapshot().unresolvedUnknownSizeMessages());
        assertTrue(budget.failed());
        assertThrows(NullPointerException.class, () -> budget.unresolvedUnknown(null));
    }
}
