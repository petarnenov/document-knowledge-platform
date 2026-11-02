package org.documentknowledge.devlogging.domain;

import java.util.Objects;
import java.util.UUID;
import org.documentknowledge.observability.BuildIdentity;
import org.documentknowledge.observability.EventMetadata;

/** Includes a BFF bridge target only after its installed artifact grant was checked. */
public record ProducerBinding(BuildIdentity build, UUID runId) {
    public ProducerBinding {
        Objects.requireNonNull(build);
        LogScope.requireId(runId);
        if (build.buildId().equals(runId)) throw new LogFailure(LogFailure.Kind.INVALID);
    }

    public boolean matches(EventMetadata event) {
        Objects.requireNonNull(event);
        return build.equals(event.execution().build()) && runId.equals(event.execution().runId());
    }
}
