package org.documentknowledge.observability;

import java.util.Objects;
import java.util.UUID;

/** An execution retains its artifact identity rather than replacing it at startup. */
public record ExecutionIdentity(BuildIdentity build, UUID runId, UUID instanceId) {
    public ExecutionIdentity {
        Objects.requireNonNull(build);
        IdentityChecks.requireId(runId);
        IdentityChecks.requireId(instanceId);
        if (build.buildId().equals(runId)) throw new IllegalArgumentException("RUN_REUSES_BUILD_ID");
    }
}
