package org.documentknowledge.observability;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Supplied compilation metadata; this type neither mints nor authenticates a build. */
public record BuildIdentity(UUID buildId, String component, String gitSha, String sourceFingerprint) {
    private static final Pattern COMPONENT = Pattern.compile("[a-z][a-z0-9.-]{0,95}");
    private static final Pattern GIT_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    public BuildIdentity {
        IdentityChecks.requireId(buildId);
        requireMatch(component, COMPONENT);
        requireMatch(gitSha, GIT_SHA);
        requireMatch(sourceFingerprint, FINGERPRINT);
    }

    private static void requireMatch(String value, Pattern pattern) {
        if (!pattern.matcher(Objects.requireNonNull(value)).matches()) {
            throw new IllegalArgumentException("INVALID_BUILD_METADATA");
        }
    }
}
