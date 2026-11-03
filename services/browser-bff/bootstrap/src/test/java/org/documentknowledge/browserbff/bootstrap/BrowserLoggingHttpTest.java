package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.context.annotation.Requires;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.browserbff.adapters.inbound.*;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;

/** Real HTTP/session/security path; outbound gateway is explicit synthetic test scope, not a store E2E. */
class BrowserLoggingHttpTest {
    static final String ORIGIN = "https://localhost:18443", CSRF = ".synthetic-csrf";
    static final UUID BUILD = UUID.randomUUID(), RUN = UUID.randomUUID();
    static final BrowserLogContexts.Artifact ARTIFACT = new BrowserLogContexts.Artifact(BUILD, "admin-portal", "a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64));
    @Test void actualContextBeaconAndExactScopedReceiptsStripBrowserSecretsAndPreserveAuthority() throws Exception {
        try (var f = new Fixture()) {
            var session = f.seed(); var context = f.open(session.cookie());
            assertEquals(Set.of("contextEpoch", "buildId", "runId", "expiresAt"), context.keySet());
            assertEquals(BUILD.toString(), context.get("buildId")); assertEquals(RUN.toString(), context.get("runId"));
            assertEquals("tenant-a", f.gateway.lastGrant.authority().tenantId());
            assertNotEquals(session.session().getId(), f.gateway.lastGrant.authority().sessionId());
            assertFalse(BrowserLogJson.encode(context).length > 1024);
            var again = f.open(session.cookie()); assertEquals(context, again); assertEquals(1, f.gateway.delegations.get());
            UUID eventId = UUID.randomUUID(); var body = f.beacon(context, eventId);
            var sent = f.post("/beacon", session.cookie(), body, null, ORIGIN); assertEquals(204, sent.statusCode(), sent.body());
            String sanitized = new String(f.gateway.lastBatch, StandardCharsets.UTF_8);
            assertFalse(sanitized.contains(CSRF)); assertFalse(sanitized.contains("csrfToken")); assertFalse(sanitized.contains(session.session().getId()));
            assertFalse(sanitized.contains("tenant-a")); assertFalse(sanitized.contains("synthetic-access"));
            UUID other = UUID.randomUUID(); var receipt = f.post("/beacon/receipts", session.cookie(), f.lookup(context, List.of(eventId, other)), null, ORIGIN);
            assertEquals(200, receipt.statusCode(), receipt.body());
            var rows = (List<?>) BrowserLogJson.parse(receipt.body().getBytes(StandardCharsets.UTF_8)).get("receipts");
            assertEquals("COMMITTED", BrowserLogJson.object(rows.getFirst()).get("state"));
            assertEquals("UNKNOWN", BrowserLogJson.object(rows.getLast()).get("state"));
            assertEquals("no-store", receipt.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", receipt.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(f.gateway.sameBudget.get()); assertTrue(f.probe.seen.get() >= 4);
        }
    }
    @Test void missingSessionOriginCsrfInvalidBindingsAndBodiesFailBeforeGateway() throws Exception {
        try (var f = new Fixture()) {
            var session = f.seed();
            assertEquals(401, f.post("/context", null, Map.of("buildId", BUILD.toString(), "runId", RUN.toString()), CSRF, ORIGIN).statusCode());
            for (String origin : new String[]{null, "https://evil.example", ORIGIN + ".evil.example"})
                assertEquals(403, f.post("/context", session.cookie(), Map.of("buildId", BUILD.toString(), "runId", RUN.toString()), CSRF, origin).statusCode());
            for (String csrf : new String[]{null, "wrong"}) assertEquals(403, f.post("/context", session.cookie(), Map.of("buildId", BUILD.toString(), "runId", RUN.toString()), csrf, ORIGIN).statusCode());
            assertEquals(403, f.post("/context", session.cookie(), Map.of("buildId", UUID.randomUUID().toString(), "runId", RUN.toString()), CSRF, ORIGIN).statusCode());
            assertEquals(400, f.raw("/context", session.cookie(), "{\"buildId\":\"" + BUILD + "\",\"buildId\":\"" + BUILD + "\",\"runId\":\"" + RUN + "\"}", CSRF, ORIGIN).statusCode());
            assertEquals(413, f.raw("/context", session.cookie(), " ".repeat(1025), CSRF, ORIGIN).statusCode());
            assertEquals(0, f.gateway.delegations.get());
            var context = f.open(session.cookie()); var body = f.beacon(context, UUID.randomUUID());
            body.put("csrfToken", "wrong"); assertEquals(403, f.post("/beacon", session.cookie(), body, null, ORIGIN).statusCode());
            body.put("csrfToken", CSRF); body.put("tenantId", "forged"); assertEquals(400, f.post("/beacon", session.cookie(), body, null, ORIGIN).statusCode()); body.remove("tenantId");
            body.put("captureClass", "UNATTRIBUTED"); assertEquals(400, f.post("/beacon", session.cookie(), body, null, ORIGIN).statusCode()); body.put("captureClass", "CURRENT_CONTEXT");
            var batch = BrowserLogJson.object(body.get("batch")); var event = BrowserLogJson.firstEvent(batch);
            event.put("message", "synthetic-secret"); assertEquals(400, f.post("/beacon", session.cookie(), body, null, ORIGIN).statusCode()); event.remove("message");
            event.put("runId", UUID.randomUUID().toString()); assertEquals(403, f.post("/beacon", session.cookie(), body, null, ORIGIN).statusCode());
            assertEquals(0, f.gateway.ingestions.get());
            var duplicateOrigin = f.request("/beacon", session.cookie(), f.beacon(context, UUID.randomUUID()), null, ORIGIN).header("Origin", "https://evil.example");
            assertEquals(403, f.client.send(duplicateOrigin.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void tenantRotationAndLogoutRejectOldTabEvenWithNewCookiesAndCsrf() throws Exception {
        try (var f = new Fixture()) {
            var original = f.seed(); var old = f.open(original.cookie());
            var rotated = f.business("/api/v1/session/tenant", original.cookie(), Map.of("tenantId", "tenant-b"), CSRF);
            assertEquals(200, rotated.statusCode(), rotated.body()); assertEquals(0, f.contexts.bindingCount());
            String cookie = rotated.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String csrf = (String) BrowserLogJson.parse(rotated.body().getBytes(StandardCharsets.UTF_8)).get("csrfToken");
            var late = f.beacon(old, UUID.randomUUID()); late.put("csrfToken", csrf);
            assertEquals(403, f.post("/beacon", cookie, late, null, ORIGIN).statusCode());
            var receipt = f.lookup(old, List.of(UUID.randomUUID())); receipt.put("csrfToken", csrf);
            assertEquals(403, f.post("/beacon/receipts", cookie, receipt, null, ORIGIN).statusCode());
            assertEquals(200, f.post("/context", cookie, Map.of("buildId", BUILD.toString(), "runId", RUN.toString()), csrf, ORIGIN).statusCode());
            assertEquals("tenant-b", f.gateway.lastGrant.authority().tenantId());
            assertEquals(204, f.business("/api/v1/session/logout", cookie, Map.of(), csrf).statusCode());
            assertEquals(0, f.contexts.bindingCount());
            assertTrue(original.session().get("dkp.retired", Boolean.class).orElse(false));
        }
    }
    @Test void delayedDelegationDoesNotHoldTransitionLockAndCannotPublishAfterRotation() throws Exception {
        try (var f = new Fixture()) {
            var original = f.seed(); f.gateway.delegationEntered = new CountDownLatch(1); f.gateway.release = new CountDownLatch(1);
            var pending = f.client.sendAsync(f.request("/context", original.cookie(), Map.of("buildId", BUILD.toString(), "runId", RUN.toString()), CSRF, ORIGIN).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(f.gateway.delegationEntered.await(2, TimeUnit.SECONDS));
            var rotated = f.business("/api/v1/session/tenant", original.cookie(), Map.of("tenantId", "tenant-b"), CSRF);
            assertEquals(200, rotated.statusCode(), rotated.body()); assertFalse(pending.isDone());
            f.gateway.release.countDown(); assertEquals(401, pending.get(3, TimeUnit.SECONDS).statusCode()); assertEquals(0, f.contexts.bindingCount());
        }
    }
    @Test void delayedLogForwardDoesNotBlockBusinessSessionAndInvalidUpstreamNeverClaimsCommit() throws Exception {
        try (var f = new Fixture()) {
            var session = f.seed(); var context = f.open(session.cookie());
            f.gateway.ingestionEntered = new CountDownLatch(1); f.gateway.release = new CountDownLatch(1);
            var pending = f.client.sendAsync(f.request("/beacon", session.cookie(), f.beacon(context, UUID.randomUUID()), null, ORIGIN).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(f.gateway.ingestionEntered.await(2, TimeUnit.SECONDS));
            var business = f.client.send(HttpRequest.newBuilder(f.server.getURI().resolve("/api/v1/session")).header("Cookie", session.cookie()).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, business.statusCode()); assertFalse(pending.isDone());
            f.gateway.release.countDown(); assertEquals(204, pending.get(3, TimeUnit.SECONDS).statusCode());
            f.gateway.badReceipt = true;
            assertEquals(503, f.post("/beacon", session.cookie(), f.beacon(context, UUID.randomUUID()), null, ORIGIN).statusCode());
            assertEquals(503, f.post("/beacon/receipts", session.cookie(), f.lookup(context, List.of(UUID.randomUUID())), null, ORIGIN).statusCode());
        }
    }
    @Test void actualOffloadSaturationMaps429AndStampExistsBeforeOffloadAndSecurity() throws Exception {
        try (var f = new Fixture()) {
            var session = f.seed();
            var executor = f.context.getBean(ExecutorService.class, Qualifiers.byName(BrowserLogResponseExecutor.NAME));
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 2; i++) executor.execute(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 16; i++) executor.execute(() -> { });
                var response = f.post("/context", session.cookie(), Map.of("buildId", BUILD.toString(), "runId", RUN.toString()), CSRF, ORIGIN);
                assertEquals(429, response.statusCode(), response.body()); assertEquals("{\"code\":\"LOG_RATE_LIMITED\"}", response.body());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow()); assertTrue(f.probe.seen.get() > 0);
                assertEquals(0, f.gateway.delegations.get());
            } finally { release.countDown(); }
        }
    }
    @Test void dependencySaturationAndUnexpectedQueryRefuseWithoutDelegation() throws Exception {
        try (var f = new Fixture()) {
            var session = f.seed(); var body = Map.of("buildId", BUILD.toString(), "runId", RUN.toString());
            assertEquals(400, f.post("/context?unexpected=1", session.cookie(), body, CSRF, ORIGIN).statusCode());
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 2; i++) f.requests.submit(BrowserLogDeadline.start(), deadline -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                    return 1;
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 16; i++) f.requests.submit(BrowserLogDeadline.start(), deadline -> 1);
                assertEquals(18, f.requests.health().chargedTasks());
                var response = f.post("/context", session.cookie(), body, CSRF, ORIGIN);
                assertEquals(429, response.statusCode(), response.body());
                assertEquals("{\"code\":\"LOG_RATE_LIMITED\"}", response.body());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals(0, f.gateway.delegations.get()); assertEquals(18, f.requests.health().chargedTasks());
            } finally { release.countDown(); }
        }
    }
    @Test void enablingLoggingPreservesExistingFrameworkErrorsOutsideLoggingRoutes() throws Exception {
        try (var disabled = ApplicationContext.run(EmbeddedServer.class, Map.of("micronaut.server.port", -1, "micronaut.server.host", "127.0.0.1", "dkp.test.browser-logging", true));
             var f = new Fixture()) {
            String path = "/synthetic-framework-error";
            var baseline = f.client.send(HttpRequest.newBuilder(disabled.getURI().resolve(path)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            var enabled = f.client.send(HttpRequest.newBuilder(f.server.getURI().resolve(path)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(baseline.statusCode(), enabled.statusCode());
            assertEquals(baseline.headers().firstValue("Content-Type"), enabled.headers().firstValue("Content-Type"));
            var expected = BrowserLogJson.parse(baseline.body().getBytes(StandardCharsets.UTF_8));
            var actual = BrowserLogJson.parse(enabled.body().getBytes(StandardCharsets.UTF_8));
            assertEquals(expected.keySet(), actual.keySet()); assertEquals(expected.get("message"), actual.get("message"));
        }
    }
    @io.micronaut.http.annotation.Controller("/synthetic-framework-error")
    @io.micronaut.security.annotation.Secured(io.micronaut.security.rules.SecurityRule.IS_ANONYMOUS)
    @Requires(property = "dkp.test.browser-logging", value = "true")
    public static class OrdinaryFrameworkError {
        @io.micronaut.http.annotation.Get public io.micronaut.http.HttpResponse<?> fail() {
            throw new io.micronaut.http.exceptions.HttpStatusException(io.micronaut.http.HttpStatus.BAD_REQUEST, "Synthetic ordinary framework error");
        }
    }
    @ServerFilter("/api/v1/dev-logs/**") @Order(Ordered.HIGHEST_PRECEDENCE + 50)
    @Requires(property = "dkp.test.browser-logging", value = "true")
    public static class AdmissionProbe {
        final AtomicInteger seen = new AtomicInteger(); final AtomicReference<BrowserLogDeadline> original = new AtomicReference<>();
        @RequestFilter public void beforeSecurity(io.micronaut.http.HttpRequest<?> request) {
            original.set(BrowserLogRouteDeadline.admitted(request)); seen.incrementAndGet();
        }
    }
    record Seed(Session session, String cookie) { }
    static final class Fixture implements AutoCloseable {
        final BrowserLogContexts contexts = new BrowserLogContexts(Clock.systemUTC(), Map.of(BUILD, ARTIFACT));
        final BrowserLogRequests requests = new BrowserLogRequests(); final FakeGateway gateway = new FakeGateway();
        final ApplicationContext context; final EmbeddedServer server; final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        final AdmissionProbe probe;
        Fixture() {
            context = ApplicationContext.builder().properties(Map.of("micronaut.server.host", "127.0.0.1", "micronaut.server.port", -1,
                "dkp.bff.portal-origin", ORIGIN, "dkp.test.browser-logging", true)).build();
            context.registerSingleton(BrowserLogGateway.class, gateway); context.registerSingleton(BrowserLogContexts.class, contexts);
            context.registerSingleton(BrowserLogCatalogs.class, new BrowserLogCatalogs(Map.of(BUILD, new BrowserLogJson.Catalog(Set.of("operation.started"), Set.of("HTTP")))));
            context.registerSingleton(BrowserLogRequests.class, requests); context.start(); server = context.getBean(EmbeddedServer.class).start();
            probe = context.getBean(AdmissionProbe.class); gateway.probe = probe;
        }
        @SuppressWarnings("unchecked") Seed seed() throws Exception {
            var store = (SessionStore<Session>) context.getBean(SessionStore.class); var session = store.newSession();
            var identity = new BrowserIdentity("https://issuer.example", "staff", "Synthetic", List.of("tenant-a", "tenant-b"), true, "synthetic-access", Instant.now().plusSeconds(240));
            session.put(SecurityFilter.AUTHENTICATION, Authentication.build("staff", Map.of(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE, identity)));
            session.put("csrfToken", CSRF); session.put("dkp.selected.tenant", "tenant-a"); store.save(session).get(2, TimeUnit.SECONDS);
            return new Seed(session, "__Host-DKP-SESSION=" + session.getId());
        }
        Map<String, Object> open(String cookie) throws Exception {
            var response = post("/context", cookie, Map.of("buildId", BUILD.toString(), "runId", RUN.toString()), CSRF, ORIGIN);
            assertEquals(200, response.statusCode(), response.body()); return BrowserLogJson.parse(response.body().getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Object> beacon(Map<String, Object> context, UUID id) {
            var event = new HashMap<String, Object>(); event.put("eventId", id.toString()); event.put("buildId", BUILD.toString()); event.put("runId", RUN.toString());
            event.put("instanceId", UUID.randomUUID().toString()); event.put("component", "admin-portal"); event.put("gitSha", "a".repeat(40)); event.put("sourceFingerprint", "b".repeat(64));
            event.put("timestamp", Instant.now().toString()); event.put("severity", "DEBUG"); event.put("eventName", "operation.started"); event.put("correlationId", UUID.randomUUID().toString());
            event.put("context", Map.of("phase", "HTTP", "outcome", "STARTED", "durationMs", 0, "attempt", 1, "attributes", Map.of("count", 1, "cleanupSettled", true)));
            return new HashMap<>(Map.of("csrfToken", CSRF, "contextEpoch", context.get("contextEpoch"), "captureClass", "CURRENT_CONTEXT", "batch", Map.of("schemaVersion", 1, "events", List.of(event))));
        }
        Map<String, Object> lookup(Map<String, Object> context, List<UUID> ids) {
            return new HashMap<>(Map.of("csrfToken", CSRF, "contextEpoch", context.get("contextEpoch"), "captureClass", "CURRENT_CONTEXT", "buildId", BUILD.toString(), "runId", RUN.toString(), "eventIds", ids.stream().map(UUID::toString).toList()));
        }
        HttpRequest.Builder request(String path, String cookie, Object body, String csrf, String origin) {
            var request = HttpRequest.newBuilder(server.getURI().resolve("/api/v1/dev-logs" + path)).timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(BrowserLogJson.encode(body)));
            if (cookie != null) request.header("Cookie", cookie); if (csrf != null) request.header("X-CSRF-TOKEN", csrf); if (origin != null) request.header("Origin", origin); return request;
        }
        HttpResponse<String> post(String path, String cookie, Object body, String csrf, String origin) throws Exception { return client.send(request(path, cookie, body, csrf, origin).build(), HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> raw(String path, String cookie, String body, String csrf, String origin) throws Exception {
            return client.send(request(path, cookie, Map.of(), csrf, origin).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> business(String path, String cookie, Object body, String csrf) throws Exception {
            return client.send(HttpRequest.newBuilder(server.getURI().resolve(path)).timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json").header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", csrf).header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofByteArray(BrowserLogJson.encode(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() throws Exception {
            gateway.release.countDown(); requests.close(); assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); server.close(); context.close(); client.close();
        }
    }
    static final class FakeGateway implements BrowserLogGateway {
        static final class Handle implements Scope { final Set<UUID> ids = ConcurrentHashMap.newKeySet(); @Override public String toString() { return "SyntheticScope[REDACTED]"; } }
        volatile Grant lastGrant; volatile byte[] lastBatch; volatile AdmissionProbe probe; volatile boolean badReceipt;
        final AtomicInteger delegations = new AtomicInteger(), ingestions = new AtomicInteger(); final AtomicBoolean sameBudget = new AtomicBoolean(true);
        volatile CountDownLatch delegationEntered = new CountDownLatch(0), ingestionEntered = new CountDownLatch(0), release = new CountDownLatch(0);
        private void check(BrowserLogDeadline deadline) { sameBudget.compareAndSet(true, deadline == probe.original.get()); deadline.requireTime(); }
        @Override public Scope delegate(Grant grant, BrowserLogDeadline deadline) { check(deadline); lastGrant = grant; delegations.incrementAndGet(); delegationEntered.countDown(); waitOn(release); return new Handle(); }
        @Override public byte[] ingest(Scope scope, byte[] batch, BrowserLogDeadline deadline) {
            check(deadline); ingestions.incrementAndGet(); lastBatch = batch.clone(); ingestionEntered.countDown(); waitOn(release);
            var handle = (Handle) scope;
            for (Object event : (List<?>) BrowserLogJson.parse(batch).get("events")) handle.ids.add(BrowserLogJson.id(BrowserLogJson.object(event).get("eventId")));
            var ids = ((List<?>) BrowserLogJson.parse(batch).get("events")).stream().map(event -> BrowserLogJson.id(BrowserLogJson.object(event).get("eventId"))).toList();
            return receipt(handle, ids);
        }
        @Override public byte[] receipts(Scope scope, byte[] lookup, BrowserLogDeadline deadline) { check(deadline); return receipt((Handle) scope, BrowserLogJson.ids(BrowserLogJson.parse(lookup).get("eventIds"))); }
        private byte[] receipt(Handle scope, List<UUID> ids) {
            if (badReceipt) return "{\"receipts\":[]}".getBytes(StandardCharsets.UTF_8);
            return BrowserLogJson.encode(Map.of("receipts", ids.stream().map(id -> scope.ids.contains(id)
                ? Map.of("eventId", id.toString(), "state", "COMMITTED", "receivedAt", Instant.now().toString())
                : Map.of("eventId", id.toString(), "state", "UNKNOWN")).toList()));
        }
        private static void waitOn(CountDownLatch latch) {
            try { if (!latch.await(3, TimeUnit.SECONDS)) throw new BffFailure(503, "LOG_STORE_UNAVAILABLE"); }
            catch (InterruptedException signal) { Thread.currentThread().interrupt(); throw new BffFailure(503, "LOG_STORE_UNAVAILABLE"); }
        }
    }
}
