package org.documentknowledge.knowledgebase.application;

public interface SnapshotTokenPort {
    /** Fresh unpredictable 256-bit base64url reference, never an authority grant. */
    String issue();
}
