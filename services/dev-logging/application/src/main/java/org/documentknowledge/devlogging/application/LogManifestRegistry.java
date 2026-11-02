package org.documentknowledge.devlogging.application;

import org.documentknowledge.devlogging.domain.VerifiedLogManifest;
import org.documentknowledge.observability.BuildIdentity;

/** Private operator boundary only; producers/readers cannot install or replace catalogs. */
@FunctionalInterface
public interface LogManifestRegistry {
    record Installed(String manifestSha256, BuildIdentity build) { }
    /** Returns only after commit; repeat installation is immutable and idempotent. */
    Installed install(VerifiedLogManifest manifest, RequestDeadline deadline);
}
