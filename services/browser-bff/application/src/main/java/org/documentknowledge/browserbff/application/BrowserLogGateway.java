package org.documentknowledge.browserbff.application;

import java.time.Instant;
import java.util.UUID;

/** Explicit outbound logging port. Authority is supplied only by the verified session adapter. */
public interface BrowserLogGateway {
    /** Opaque adapter-owned credential handle; never serialized to a browser. */
    interface Scope { }
    record Grant(UUID contextEpoch, BrowserLogContexts.Artifact artifact, UUID runId,
                 BrowserLogContexts.Authority authority, Instant captureUntil) { }
    Scope delegate(Grant grant, BrowserLogDeadline deadline);
    byte[] ingest(Scope scope, byte[] sanitizedBatch, BrowserLogDeadline deadline);
    byte[] receipts(Scope scope, byte[] eventIds, BrowserLogDeadline deadline);
}
