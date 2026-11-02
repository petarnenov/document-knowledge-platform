package org.documentknowledge.devlogging.domain;

import java.time.Instant;
import java.util.UUID;

/** Trusted authentication result, never decoded from query parameters. */
public record LogReader(UUID id, boolean operator, String issuer, String tenantId, Instant expiresAt) {
    public LogReader {
        LogScope.requireId(id);
        if (expiresAt == null || (operator && (issuer != null || tenantId != null))
                || (!operator && (!text(issuer, 512) || !text(tenantId, 256)))) {
            throw new LogFailure(LogFailure.Kind.INVALID);
        }
    }

    private static boolean text(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
            && value.chars().noneMatch(Character::isISOControl);
    }

    public void requireActive(Instant now) {
        if (!now.isBefore(expiresAt)) throw new LogFailure(LogFailure.Kind.EXPIRED);
    }
}
