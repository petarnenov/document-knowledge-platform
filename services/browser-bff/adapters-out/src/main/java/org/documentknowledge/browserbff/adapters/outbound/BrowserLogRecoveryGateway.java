package org.documentknowledge.browserbff.adapters.outbound;

import java.util.List;
import java.util.UUID;
import org.documentknowledge.browserbff.application.BrowserLogDeadline;
import org.documentknowledge.browserbff.application.BrowserLogGateway;

/** Outbound-only recovery SPI; application code continues to use opaque BrowserLogGateway.Scope. */
public interface BrowserLogRecoveryGateway extends BrowserLogGateway {
    byte[] checkpoint(Scope scope);
    Scope restore(byte[] checkpoint);
    boolean expired(Scope scope);
    List<UUID> eventIds(Scope scope, byte[] sanitizedBatch);
    byte[] forward(Scope scope, byte[] sanitizedBatch, BrowserLogDeadline deadline);

    /** Safe transport classification. Never carries a response body, URL or credential. */
    final class DeliveryFailure extends RuntimeException {
        private final boolean terminal;
        public DeliveryFailure(boolean terminal) { super("LOG_STORE_UNAVAILABLE"); this.terminal = terminal; }
        public boolean terminal() { return terminal; }
    }
}
