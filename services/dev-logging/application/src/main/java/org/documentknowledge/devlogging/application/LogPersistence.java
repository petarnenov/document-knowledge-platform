package org.documentknowledge.devlogging.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.documentknowledge.devlogging.domain.LogBatch;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.documentknowledge.devlogging.domain.ProducerScope;

/** SQL adapters implement this port; application/domain never depend on JDBC or HTTP. */
public interface LogPersistence {
    record Committed(UUID eventId, Instant receivedAt) {
        public Committed { requireId(eventId); Objects.requireNonNull(receivedAt); }
    }
    enum ReceiptState { COMMITTED, EXPIRED, UNKNOWN }
    record Receipt(UUID eventId, ReceiptState state, Instant receivedAt) {
        public Receipt {
            requireId(eventId);
            Objects.requireNonNull(state);
            if ((state == ReceiptState.COMMITTED) != (receivedAt != null)) throw new LogFailure(LogFailure.Kind.INVALID);
        }
    }

    private static void requireId(UUID id) {
        if (id == null || id.equals(new UUID(0, 0))) throw new LogFailure(LogFailure.Kind.INVALID);
    }

    /** Whole batch commits or fails; scope and current authority are rechecked transactionally. */
    List<Committed> commit(ProducerScope authority, LogBatch batch, Instant now);

    /** Outside-scope and absent IDs produce the same UNKNOWN result, without event bodies. */
    List<Receipt> receipts(ProducerScope authority, List<UUID> eventIds, Instant now);
}
