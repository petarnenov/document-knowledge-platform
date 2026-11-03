package org.documentknowledge.browserbff.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Finite context policy; no framework, network, disk, logging or business-domain behavior. */
public final class BrowserLogContexts {
    public record Artifact(UUID buildId, String component, String gitSha, String sourceFingerprint,
                           String manifestSha256, String vocabularySha256) {
        public Artifact {
            id(buildId);
            if (!"admin-portal".equals(component) || !hex(gitSha, 40) || !hex(sourceFingerprint, 64)
                    || !hex(manifestSha256, 64) || !hex(vocabularySha256, 64)) invalid();
        }
    }
    /** Already verified identity snapshot, deliberately excludes access/CSRF/session credentials. */
    public record Authority(String sessionId, String issuer, String subject, String tenantId, Instant expiresAt) {
        public Authority {
            text(sessionId); text(issuer); text(subject); Objects.requireNonNull(expiresAt);
            if (tenantId != null) text(tenantId);
        }
        @Override public String toString() { return "BrowserLogAuthority[REDACTED]"; }
    }
    public record Context(UUID contextEpoch, UUID buildId, UUID runId, Instant expiresAt) { }
    public record Capture(BrowserLogGateway.Grant grant, BrowserLogGateway.Scope scope) {
        @Override public String toString() { return "BrowserLogCapture[REDACTED]"; }
    }
    /** The caller owns delegation only when owner is true; all other callers retry later. */
    public static final class Reservation {
        private final Epoch epoch;
        private final Binding binding;
        private final boolean owner;
        private Reservation(Epoch epoch, Binding binding, boolean owner) {
            this.epoch = epoch; this.binding = binding; this.owner = owner;
        }
        public boolean owner() { return owner; }
        public BrowserLogGateway.Grant grant() { return binding.grant; }
        @Override public String toString() { return "BrowserLogReservation[REDACTED]"; }
    }
    private record Execution(UUID build, UUID run) { }
    private static final class Binding {
        final BrowserLogGateway.Grant grant;
        BrowserLogGateway.Scope scope;
        Binding(BrowserLogGateway.Grant grant) { this.grant = grant; }
    }
    private static final class Epoch {
        final UUID id = UUID.randomUUID();
        final Authority authority;
        final Instant expiresAt;
        final Map<Execution, Binding> bindings = new HashMap<>();
        Epoch(Authority authority, Instant expiresAt) { this.authority = authority; this.expiresAt = expiresAt; }
    }
    private final Clock clock;
    private final Map<UUID, Artifact> artifacts;
    private final Map<String, Epoch> epochs = new HashMap<>();
    private int bindings;

    public BrowserLogContexts(Clock clock, Map<UUID, Artifact> artifacts) {
        this.clock = Objects.requireNonNull(clock); this.artifacts = Map.copyOf(artifacts);
        if (this.artifacts.isEmpty() || this.artifacts.size() > 4096
                || this.artifacts.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue().buildId()))) invalid();
    }
    /** Invoke under the current session's transition lock after session/Origin/CSRF validation. */
    public synchronized Reservation reserve(Authority authority, Instant sessionExpiresAt, UUID buildId, UUID runId) {
        Objects.requireNonNull(authority); Objects.requireNonNull(sessionExpiresAt); id(buildId); id(runId);
        Instant now = clock.instant(); expire(now);
        if (!now.isBefore(authority.expiresAt()) || !now.isBefore(sessionExpiresAt)) denied();
        Artifact artifact = artifacts.get(buildId); if (artifact == null) denied();
        Epoch epoch = epochs.get(authority.sessionId());
        if (epoch != null && !epoch.authority.equals(authority)) { remove(authority.sessionId()); epoch = null; }
        var execution = new Execution(buildId, runId);
        if (epoch != null && epoch.bindings.containsKey(execution))
            return new Reservation(epoch, epoch.bindings.get(execution), false);
        if (bindings >= 4096 || (epoch != null && epoch.bindings.size() >= 64))
            throw new BffFailure(429, "LOG_RATE_LIMITED");
        if (epoch == null) {
            Instant until = earlier(earlier(now.plusSeconds(300), authority.expiresAt()), sessionExpiresAt);
            epoch = new Epoch(authority, until); epochs.put(authority.sessionId(), epoch);
        }
        var grant = new BrowserLogGateway.Grant(epoch.id, artifact, runId, authority, epoch.expiresAt);
        var binding = new Binding(grant); epoch.bindings.put(execution, binding); bindings++;
        return new Reservation(epoch, binding, true);
    }
    /** Delegation has completed outside all session locks; caller rechecks current session before this call. */
    public synchronized Context activate(Reservation reservation, BrowserLogGateway.Scope scope) {
        Objects.requireNonNull(scope);
        if (!reservation.owner || reservation.binding.scope != null) denied();
        requireCurrent(reservation); reservation.binding.scope = scope;
        return context(reservation);
    }
    public synchronized Context ready(Reservation reservation) {
        requireCurrent(reservation);
        if (reservation.binding.scope == null) throw new BffFailure(429, "LOG_CONTEXT_PENDING");
        return context(reservation);
    }
    public synchronized void failed(Reservation reservation) {
        if (!reservation.owner || reservation.binding.scope != null) return;
        Epoch current = epochs.get(reservation.epoch.authority.sessionId());
        if (current != reservation.epoch) return;
        var key = new Execution(reservation.binding.grant.artifact().buildId(), reservation.binding.grant.runId());
        if (current.bindings.remove(key, reservation.binding)) bindings--;
        if (current.bindings.isEmpty()) epochs.remove(current.authority.sessionId());
    }
    /** Linearize acceptance with session retirement, then dispatch this immutable capture outside the lock. */
    public synchronized Capture capture(Authority authority, UUID epochId, UUID buildId, UUID runId) {
        id(epochId); id(buildId); id(runId); expire(clock.instant());
        Epoch epoch = epochs.get(authority.sessionId());
        if (epoch == null || !epoch.id.equals(epochId) || !epoch.authority.equals(authority)) { denied(); return null; }
        Binding binding = epoch.bindings.get(new Execution(buildId, runId));
        if (binding == null || binding.scope == null) { denied(); return null; }
        return new Capture(binding.grant, binding.scope);
    }
    public synchronized void retire(String sessionId) { remove(sessionId); }
    public synchronized int bindingCount() { expire(clock.instant()); return bindings; }
    private void requireCurrent(Reservation reservation) {
        expire(clock.instant());
        Epoch epoch = reservation.epoch;
        var key = new Execution(reservation.binding.grant.artifact().buildId(), reservation.binding.grant.runId());
        if (epochs.get(epoch.authority.sessionId()) != epoch || epoch.bindings.get(key) != reservation.binding) denied();
    }
    private Context context(Reservation reservation) {
        return new Context(reservation.epoch.id, reservation.binding.grant.artifact().buildId(),
            reservation.binding.grant.runId(), reservation.epoch.expiresAt);
    }
    private void expire(Instant now) {
        var iterator = epochs.values().iterator();
        while (iterator.hasNext()) {
            Epoch epoch = iterator.next();
            if (!now.isBefore(epoch.expiresAt)) { bindings -= epoch.bindings.size(); iterator.remove(); }
        }
    }
    private void remove(String sessionId) {
        Epoch epoch = epochs.remove(sessionId); if (epoch != null) bindings -= epoch.bindings.size();
    }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static boolean hex(String value, int length) { return value != null && value.matches("[0-9a-f]{" + length + "}"); }
    private static void id(UUID value) { if (value == null || value.equals(new UUID(0, 0))) invalid(); }
    private static void text(String value) { if (value == null || value.isBlank() || value.length() > 512) invalid(); }
    private static void invalid() { throw new BffFailure(400, "INVALID_LOG_CONTEXT"); }
    private static void denied() { throw new BffFailure(403, "LOG_CONTEXT_RETIRED"); }
}
