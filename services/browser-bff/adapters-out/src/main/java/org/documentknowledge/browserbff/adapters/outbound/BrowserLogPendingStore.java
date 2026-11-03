package org.documentknowledge.browserbff.adapters.outbound;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Replaceable private pending storage; authority checkpoints are opaque to the application port. */
public interface BrowserLogPendingStore extends AutoCloseable {
    int MAX_EVENTS = 2048;
    int MAX_BYTES = 8 * 1024 * 1024;
    record Entry(UUID id, byte[] authority, byte[] batch, int eventCount) {
        public Entry {
            if (id == null || id.equals(new UUID(0, 0)) || authority == null || authority.length == 0
                    || authority.length > 8192 || batch == null || batch.length == 0 || batch.length > 32768
                    || eventCount < 1 || eventCount > 32) throw new IllegalArgumentException("INVALID_BROWSER_LOG_PENDING");
            authority = authority.clone(); batch = batch.clone();
        }
        @Override public byte[] authority() { return authority.clone(); }
        @Override public byte[] batch() { return batch.clone(); }
        @Override public String toString() { return "BrowserLogPending[REDACTED]"; }
    }
    List<Entry> load() throws IOException;
    void replace(List<Entry> entries) throws IOException;
    @Override void close();
}
