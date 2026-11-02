package org.documentknowledge.devlogging.application;

import org.documentknowledge.devlogging.domain.ProducerScope;
import org.documentknowledge.devlogging.domain.VerifiedLogManifest;

/** Private scoped read of the original installed artifact; no caller-selected digest or public download. */
public interface LogManifestCatalog {
    VerifiedLogManifest load(ProducerScope authority, RequestDeadline deadline);
}
