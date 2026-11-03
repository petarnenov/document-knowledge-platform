package org.documentknowledge.browserbff.application;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class BrowserLogContextsTest {
    private static final Instant NOW = Instant.parse("2026-11-03T04:00:00Z");
    private static final UUID BUILD = UUID.randomUUID(), RUN = UUID.randomUUID();
    private static final BrowserLogContexts.Artifact ARTIFACT = new BrowserLogContexts.Artifact(
        BUILD, "admin-portal", "a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64));
    private static final BrowserLogGateway.Scope SCOPE = new BrowserLogGateway.Scope() { };
    private final MutableClock clock = new MutableClock();
    private final BrowserLogContexts contexts = new BrowserLogContexts(clock, Map.of(BUILD, ARTIFACT));
    private BrowserLogContexts.Authority authority(String session, String tenant) {
        return new BrowserLogContexts.Authority(session, "https://identity.test", "staff", tenant, NOW.plusSeconds(600));
    }
    private BrowserLogContexts.Reservation reserve(String session, UUID run) {
        return contexts.reserve(authority(session, "tenant-a"), NOW.plusSeconds(500), BUILD, run);
    }
    private static void failure(int status, Runnable action) {
        assertEquals(status, assertThrows(BffFailure.class, action::run).status());
    }
    @Test void pendingReservationHasSingleOwnerAndBecomesExactImmutableCapture() {
        var owner = reserve("session", RUN); var follower = reserve("session", RUN);
        assertTrue(owner.owner()); assertFalse(follower.owner()); assertEquals(1, contexts.bindingCount());
        failure(429, () -> contexts.ready(follower));
        failure(403, () -> contexts.activate(follower, SCOPE));
        contexts.failed(follower); assertEquals(1, contexts.bindingCount());
        var context = contexts.activate(owner, SCOPE);
        assertEquals(context, contexts.ready(follower)); assertEquals(BUILD, context.buildId()); assertEquals(RUN, context.runId());
        assertEquals(NOW.plusSeconds(300), context.expiresAt());
        var capture = contexts.capture(authority("session", "tenant-a"), context.contextEpoch(), BUILD, RUN);
        assertSame(SCOPE, capture.scope()); assertEquals(owner.grant(), capture.grant());
        assertEquals(ARTIFACT, capture.grant().artifact()); assertEquals("tenant-a", capture.grant().authority().tenantId());
        assertFalse(capture.toString().contains("tenant-a")); assertFalse(owner.toString().contains("session"));
        assertFalse(capture.grant().authority().toString().contains("staff"));
        contexts.failed(owner); assertEquals(1, contexts.bindingCount());
        failure(403, () -> contexts.activate(owner, SCOPE));
    }
    @Test void sameEpochServesMultipleTabsAndOriginalOlderArtifacts() {
        UUID otherBuild = UUID.randomUUID(), otherRun = UUID.randomUUID();
        var otherArtifact = new BrowserLogContexts.Artifact(otherBuild, "admin-portal", "a".repeat(40),
            "b".repeat(64), "e".repeat(64), "d".repeat(64));
        var registry = new BrowserLogContexts(clock, Map.of(BUILD, ARTIFACT, otherBuild, otherArtifact));
        var first = registry.reserve(authority("session", null), NOW.plusSeconds(60), BUILD, RUN);
        var second = registry.reserve(authority("session", null), NOW.plusSeconds(100), otherBuild, otherRun);
        var a = registry.activate(first, SCOPE); var b = registry.activate(second, SCOPE);
        assertEquals(a.contextEpoch(), b.contextEpoch()); assertEquals(NOW.plusSeconds(60), b.expiresAt());
        assertEquals(otherArtifact, registry.capture(authority("session", null), b.contextEpoch(), otherBuild, otherRun).grant().artifact());
        assertNull(first.grant().authority().tenantId()); assertEquals(2, registry.bindingCount());
    }
    @Test void retirementRejectsLateDelegationAndDoesNotRelabelPreviouslyAcceptedCapture() {
        var owner = reserve("session", RUN); var context = contexts.activate(owner, SCOPE);
        var accepted = contexts.capture(authority("session", "tenant-a"), context.contextEpoch(), BUILD, RUN);
        contexts.retire("session"); contexts.retire("session"); assertEquals(0, contexts.bindingCount());
        failure(403, () -> contexts.ready(owner));
        failure(403, () -> contexts.capture(authority("session", "tenant-a"), context.contextEpoch(), BUILD, RUN));
        var next = contexts.reserve(authority("session", "tenant-b"), NOW.plusSeconds(500), BUILD, RUN);
        var current = contexts.activate(next, SCOPE);
        assertNotEquals(context.contextEpoch(), current.contextEpoch());
        assertEquals("tenant-a", accepted.grant().authority().tenantId());
        failure(403, () -> contexts.capture(authority("session", "tenant-b"), context.contextEpoch(), BUILD, RUN));
        contexts.failed(owner); assertEquals(1, contexts.bindingCount());
        contexts.retire("session"); var pending = reserve("session", RUN); contexts.retire("session");
        failure(403, () -> contexts.activate(pending, SCOPE));
    }
    @Test void authorityChangeAndForeignSessionOrExecutionCannotUseOldBinding() {
        var a = reserve("session", RUN); var context = contexts.activate(a, SCOPE);
        failure(403, () -> contexts.capture(authority("other", "tenant-a"), context.contextEpoch(), BUILD, RUN));
        failure(403, () -> contexts.capture(authority("session", "tenant-b"), context.contextEpoch(), BUILD, RUN));
        failure(403, () -> contexts.capture(authority("session", "tenant-a"), UUID.randomUUID(), BUILD, RUN));
        failure(403, () -> contexts.capture(authority("session", "tenant-a"), context.contextEpoch(), BUILD, UUID.randomUUID()));
        var changed = contexts.reserve(authority("session", "tenant-b"), NOW.plusSeconds(500), BUILD, RUN);
        assertNotEquals(context.contextEpoch(), changed.grant().contextEpoch()); assertEquals(1, contexts.bindingCount());
        failure(403, () -> contexts.ready(a));
        failure(403, () -> contexts.capture(authority("session", "tenant-b"), changed.grant().contextEpoch(), BUILD, RUN));
    }
    @Test void failedOwnerReleasesOnlyItsPendingReservation() {
        var first = reserve("session", RUN); contexts.failed(first); assertEquals(0, contexts.bindingCount());
        var next = reserve("session", RUN); contexts.failed(first); assertEquals(1, contexts.bindingCount());
        failure(403, () -> contexts.activate(first, SCOPE));
        contexts.activate(next, SCOPE); contexts.failed(next); assertEquals(1, contexts.bindingCount());
        var different = reserve("session", UUID.randomUUID()); contexts.failed(different); assertEquals(1, contexts.bindingCount());
    }
    @Test void expiryUsesEarlierIdentitySessionAndFiveMinuteLimitAndReclaimsPendingCapacity() {
        var shortIdentity = new BrowserLogContexts.Authority("short", "https://identity.test", "staff", null, NOW.plusSeconds(10));
        var identityBound = contexts.reserve(shortIdentity, NOW.plusSeconds(100), BUILD, RUN);
        assertEquals(NOW.plusSeconds(10), identityBound.grant().captureUntil());
        var sessionBound = contexts.reserve(authority("session", null), NOW.plusSeconds(5), BUILD, RUN);
        assertEquals(NOW.plusSeconds(5), sessionBound.grant().captureUntil());
        clock.now = NOW.plusSeconds(5); failure(403, () -> contexts.activate(sessionBound, SCOPE));
        assertEquals(1, contexts.bindingCount());
        clock.now = NOW.plusSeconds(10); assertEquals(0, contexts.bindingCount());
        failure(403, () -> contexts.reserve(shortIdentity, NOW.plusSeconds(100), BUILD, RUN));
        failure(403, () -> contexts.reserve(authority("session", null), clock.now, BUILD, RUN));
    }
    @Test void finiteCapsIncludePendingAndExpiryRestoresCapacity() {
        for (int session = 0; session < 64; session++) {
            for (int tab = 0; tab < 64; tab++) reserve("session-" + session, UUID.randomUUID());
            int number = session;
            failure(429, () -> reserve("session-" + number, UUID.randomUUID()));
        }
        assertEquals(4096, contexts.bindingCount()); failure(429, () -> reserve("overflow", RUN));
        contexts.retire("session-0"); assertEquals(4032, contexts.bindingCount());
        reserve("overflow", RUN); assertEquals(4033, contexts.bindingCount());
        clock.now = NOW.plusSeconds(300); assertEquals(0, contexts.bindingCount());
        var renewed = contexts.reserve(authority("renewed", null), NOW.plusSeconds(500), BUILD, RUN);
        assertTrue(renewed.owner()); assertEquals(1, contexts.bindingCount());
    }
    @Test void concurrentRequestsCannotAllocateDuplicateScopesOrOverRelease() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<java.util.concurrent.Callable<BrowserLogContexts.Reservation>>();
            for (int i = 0; i < 32; i++) calls.add(() -> reserve("session", RUN));
            var results = workers.invokeAll(calls); int owners = 0;
            for (var result : results) if (result.get().owner()) owners++;
            assertEquals(1, owners); assertEquals(1, contexts.bindingCount());
        }
    }
    @Test void rejectsUnknownArtifactNoncanonicalValuesAndInvalidConfiguration() {
        failure(403, () -> contexts.reserve(authority("session", null), NOW.plusSeconds(100), UUID.randomUUID(), RUN));
        failure(400, () -> reserve("session", new UUID(0, 0)));
        failure(400, () -> contexts.reserve(authority("session", null), NOW.plusSeconds(100), null, RUN));
        failure(400, () -> new BrowserLogContexts(clock, Map.of()));
        failure(400, () -> new BrowserLogContexts(clock, Map.of(UUID.randomUUID(), ARTIFACT)));
        for (String bad : List.of("", " ", "x".repeat(513))) {
            failure(400, () -> new BrowserLogContexts.Authority(bad, "issuer", "subject", null, NOW));
            failure(400, () -> new BrowserLogContexts.Authority("session", bad, "subject", null, NOW));
            failure(400, () -> new BrowserLogContexts.Authority("session", "issuer", bad, null, NOW));
            failure(400, () -> new BrowserLogContexts.Authority("session", "issuer", "subject", bad, NOW));
        }
        failure(400, () -> new BrowserLogContexts.Authority(null, "issuer", "subject", null, NOW));
        failure(400, () -> new BrowserLogContexts.Artifact(BUILD, "other", "a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64)));
        for (int part = 0; part < 4; part++) {
            String[] hashes = {"a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64)};
            hashes[part] = "Z";
            failure(400, () -> new BrowserLogContexts.Artifact(BUILD, "admin-portal", hashes[0], hashes[1], hashes[2], hashes[3]));
        }
        failure(400, () -> new BrowserLogContexts.Artifact(BUILD, "admin-portal", null, "b".repeat(64), "c".repeat(64), "d".repeat(64)));
    }
    private static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
