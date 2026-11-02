package org.documentknowledge.devlogging.domain;

import java.util.Objects;
import java.util.UUID;

/** Constructed from verified server grants, never from browser actor/tenant claims. */
public record LogScope(UUID id, Kind kind, String issuer, String subject, String tenantId) {
    public enum Kind { TENANT, AUTHENTICATED_UNSELECTED, UNATTRIBUTED, OPERATOR_ONLY }

    public LogScope {
        requireId(id);
        requireFields(kind, issuer, subject, tenantId);
    }

    /** Trusted scope description without a caller-selected persistent ID. */
    public record Description(Kind kind, String issuer, String subject, String tenantId) {
        public Description { requireFields(kind, issuer, subject, tenantId); }
        public LogScope identified(UUID serverId) { return new LogScope(serverId, kind, issuer, subject, tenantId); }
    }

    private static void requireFields(Kind kind, String issuer, String subject, String tenantId) {
        Objects.requireNonNull(kind);
        switch (kind) {
            case TENANT -> {
                requireText(issuer, 512);
                requireText(subject, 256);
                requireText(tenantId, 256);
            }
            case AUTHENTICATED_UNSELECTED -> {
                requireText(issuer, 512);
                requireText(subject, 256);
                if (tenantId != null) throw new LogFailure(LogFailure.Kind.INVALID);
            }
            case UNATTRIBUTED, OPERATOR_ONLY -> {
                if (issuer != null || subject != null || tenantId != null) throw new LogFailure(LogFailure.Kind.INVALID);
            }
        }
    }

    static void requireId(UUID id) {
        if (id == null || id.equals(new UUID(0, 0))) throw new LogFailure(LogFailure.Kind.INVALID);
    }

    private static void requireText(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl)) {
            throw new LogFailure(LogFailure.Kind.INVALID);
        }
    }
}
