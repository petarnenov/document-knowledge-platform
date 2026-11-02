package org.documentknowledge.devlogging.application;

import java.time.Instant;
import java.util.UUID;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptContractTest {
    private static final UUID ID = new UUID(1, 1);
    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    @Test
    void persistenceConfirmationAlwaysCarriesAnEventAndCommitTime() {
        var receipt = new LogPersistence.Committed(ID, NOW);
        assertEquals(ID, receipt.eventId());
        assertEquals(NOW, receipt.receivedAt());
        assertThrows(LogFailure.class, () -> new LogPersistence.Committed(new UUID(0, 0), NOW));
        assertThrows(LogFailure.class, () -> new LogPersistence.Committed(null, NOW));
        assertThrows(NullPointerException.class, () -> new LogPersistence.Committed(ID, null));
    }

    @Test
    void expiredOrUnknownIsNotACommitAcknowledgement() {
        var committed = new LogPersistence.Receipt(ID, LogPersistence.ReceiptState.COMMITTED, NOW);
        assertEquals(NOW, committed.receivedAt());
        assertEquals(ID, committed.eventId());
        for (var state : LogPersistence.ReceiptState.values()) {
            assertEquals(state, new LogPersistence.Receipt(ID, state,
                state == LogPersistence.ReceiptState.COMMITTED ? NOW : null).state());
            assertThrows(LogFailure.class, () -> new LogPersistence.Receipt(ID, state,
                state == LogPersistence.ReceiptState.COMMITTED ? null : NOW));
        }
        assertThrows(LogFailure.class, () -> new LogPersistence.Receipt(null, LogPersistence.ReceiptState.UNKNOWN, null));
        assertThrows(LogFailure.class, () -> new LogPersistence.Receipt(new UUID(0, 0), LogPersistence.ReceiptState.UNKNOWN, null));
        assertThrows(NullPointerException.class, () -> new LogPersistence.Receipt(ID, null, null));
    }
}
