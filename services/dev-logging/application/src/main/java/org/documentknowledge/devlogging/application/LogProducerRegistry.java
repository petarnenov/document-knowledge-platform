package org.documentknowledge.devlogging.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.documentknowledge.devlogging.domain.*;

/** Private trusted ports: HTTP adapters must authenticate configuration-derived grants first. */
public interface LogProducerRegistry {
    record Registrar(UUID id, Instant expiresAt) {
        public Registrar { requireId(id); Objects.requireNonNull(expiresAt); }
        public void requireActive(Instant now) { active(expiresAt, now); }
    }

    record ScopePermission(LogScope.Kind kind, String issuer, String tenantId) {
        public ScopePermission {
            new LogScope.Description(kind, issuer,
                kind == LogScope.Kind.TENANT || kind == LogScope.Kind.AUTHENTICATED_UNSELECTED ? "verified-subject" : null, tenantId);
        }
        public boolean permits(LogScope.Description scope) {
            return kind == scope.kind() && Objects.equals(issuer, scope.issuer()) && Objects.equals(tenantId, scope.tenantId());
        }
    }

    record Delegator(UUID id, Instant expiresAt, Set<UUID> parents, Set<String> bridgeManifests,
                     Set<ScopePermission> permissions) {
        public Delegator {
            requireId(id); Objects.requireNonNull(expiresAt);
            parents = Set.copyOf(parents); bridgeManifests = Set.copyOf(bridgeManifests); permissions = Set.copyOf(permissions);
            if (parents.size() > 4096 || bridgeManifests.size() > 4096 || permissions.size() > 4096)
                throw new LogFailure(LogFailure.Kind.INVALID);
            parents.forEach(LogProducerRegistry::requireId);
            if (bridgeManifests.stream().anyMatch(value -> !value.matches("[0-9a-f]{64}")))
                throw new LogFailure(LogFailure.Kind.INVALID);
        }
        public void requireActive(Instant now) { active(expiresAt, now); }
        public void requireScope(UUID parent, LogScope.Description scope) {
            if (!parents.contains(parent) || permissions.stream().noneMatch(permission -> permission.permits(scope)))
                throw new LogFailure(LogFailure.Kind.FORBIDDEN);
        }
    }

    record Issued(ProducerScope authority, Instant captureUntil, String producerToken) {
        public Issued {
            Objects.requireNonNull(authority); Objects.requireNonNull(captureUntil);
            if (producerToken == null || !producerToken.matches("[A-Za-z0-9_-]{43}")) throw new LogFailure(LogFailure.Kind.INVALID);
        }
        @Override public String toString() { return "IssuedLogProducer[REDACTED]"; }
    }

    Issued register(Registrar registrar, ArtifactRegistration artifact, LogScope.Description scope,
                    Instant acceptUntil, RequestDeadline deadline);
    Issued delegate(Delegator delegator, UUID parent, ArtifactRegistration artifact, LogScope.Description scope,
                    UUID contextEpoch, Instant captureUntil, RequestDeadline deadline);
    void revoke(Registrar registrar, UUID parent, RequestDeadline deadline);

    private static void active(Instant expiry, Instant now) {
        if (!Objects.requireNonNull(now).isBefore(expiry)) throw new LogFailure(LogFailure.Kind.EXPIRED);
    }
    private static void requireId(UUID id) {
        if (id == null || id.equals(new UUID(0,0))) throw new LogFailure(LogFailure.Kind.INVALID);
    }
}
