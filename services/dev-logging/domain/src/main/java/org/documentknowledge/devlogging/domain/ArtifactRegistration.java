package org.documentknowledge.devlogging.domain;

import java.util.Objects;

/** Claimed diagnostic identity must match an installed immutable manifest before registration. */
public record ArtifactRegistration(ProducerBinding binding, String manifestSha256, String vocabularySha256) {
    public ArtifactRegistration {
        Objects.requireNonNull(binding);
        if (manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}")
                || vocabularySha256 == null || !vocabularySha256.matches("[0-9a-f]{64}")) {
            throw new LogFailure(LogFailure.Kind.INVALID);
        }
    }
}
