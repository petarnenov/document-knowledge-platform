package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.documentknowledge.browserbff.adapters.inbound.VerifiedOpenIdMapper;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

class SessionResponseRaceTest {
    private static final String COOKIE = "__Host-DKP-SESSION";
    private static final String ORIGIN = "https://localhost:18443";
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @AfterEach void closeClient() { client.close(); }

    @Test void responseEncodedBeforeRotationCannotReissueItsIncomingCookie() throws Exception {
        try (var server = server()) {
            String oldCookie = seed(server);
            var jar = new CookieJar(oldCookie);
            // Capture the actual framework-encoded response while the old session is still valid.
            // Applying its headers last models reverse delivery, without relying on network timing.
            var earlier = send(get(server, "/api/v1/session", oldCookie));
            assertEquals(200, earlier.statusCode());
            var selected = select(server, oldCookie, ".fixture-csrf", "tenant-b");
            assertEquals(200, selected.statusCode());
            jar.apply(selected);
            String newCookie = jar.value;
            assertFalse(oldCookie.equals(newCookie));
            jar.apply(earlier);
            var current = send(get(server, "/api/v1/session", jar.value));
            assertAll("Already encoded old response cannot overwrite later rotation",
                () -> assertTrue(earlier.headers().allValues("Set-Cookie").isEmpty(), "Existing identifier must not be reissued"),
                () -> assertTrue(newCookie.equals(jar.value), "Reordered old headers replaced rotated cookie"),
                () -> assertEquals(200, current.statusCode()));
            assertEquals("tenant-b", json(server, current).get("selectedTenant"));
            assertEquals(401, send(get(server, "/api/v1/session", oldCookie)).statusCode());
        }
    }

    @Test void delayedResponseCannotUndoExplicitLogoutCookieRemoval() throws Exception {
        try (var server = server()) {
            String cookie = seed(server);
            var jar = new CookieJar(cookie);
            var gate = server.getApplicationContext().getBean(SessionResponseRaceFixture.class);
            var held = client.sendAsync(get(server, "/api/v1/session-response-race", cookie), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(gate.loaded.await(5, TimeUnit.SECONDS));
                var logout = send(HttpRequest.newBuilder(server.getURI().resolve("/api/v1/session/logout"))
                    .timeout(Duration.ofSeconds(5)).header("Cookie", cookie).header("Origin", ORIGIN)
                    .header("X-CSRF-TOKEN", ".fixture-csrf").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
                assertEquals(204, logout.statusCode());
                jar.apply(logout);
                assertTrue(jar.value.isEmpty(), "Explicit logout clears the browser cookie");
                gate.release.countDown();
                var delayed = held.get(5, TimeUnit.SECONDS);
                assertEquals(200, delayed.statusCode());
                jar.apply(delayed);
                assertAll("Late response cannot undo local logout",
                    () -> assertTrue(delayed.headers().allValues("Set-Cookie").isEmpty(), "Old response must not reissue logged-out identifier"),
                    () -> assertTrue(jar.value.isEmpty(), "Logout removal must survive late response"),
                    () -> assertEquals(401, send(get(server, "/api/v1/session", cookie)).statusCode()));
            } finally {
                gate.release.countDown();
                held.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void delayedRetiredResponseCannotReplaceRotatedBrowserSession() throws Exception {
        try (var server = server()) {
            String oldCookie = seed(server);
            var jar = new CookieJar(oldCookie);
            var gate = server.getApplicationContext().getBean(SessionResponseRaceFixture.class);
            var held = client.sendAsync(get(server, "/api/v1/session-response-race", oldCookie), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(gate.loaded.await(5, TimeUnit.SECONDS), "Old request must load its real session before rotation");
                var selected = select(server, oldCookie, ".fixture-csrf", "tenant-b");
                assertEquals(200, selected.statusCode());
                jar.apply(selected);
                String newCookie = jar.value;
                assertFalse(oldCookie.equals(newCookie), "Actual tenant selection rotates the identifier");
                var newSession = send(get(server, "/api/v1/session", newCookie));
                assertEquals(200, newSession.statusCode());
                var fresh = json(server, newSession);
                assertEquals("tenant-b", fresh.get("selectedTenant"));
                assertFalse(".fixture-csrf".equals(fresh.get("csrfToken")), "Actual selection rotates CSRF authority");
                assertEquals(401, send(get(server, "/api/v1/session", oldCookie)).statusCode());

                gate.release.countDown();
                var delayed = held.get(5, TimeUnit.SECONDS);
                assertEquals(200, delayed.statusCode(), "Fixture captures its harmless view before rotation");
                boolean staleCookie = delayed.headers().allValues("Set-Cookie").stream()
                    .anyMatch(value -> value.startsWith(oldCookie + ";"));
                jar.apply(delayed);
                var after = send(get(server, "/api/v1/session", jar.value));
                // Keep all outcomes, without printing identifiers or CSRF values in assertion failures.
                assertAll("A late retired response must preserve the current browser authority",
                    () -> assertFalse(staleCookie, "Retired response emitted its previous identifier"),
                    () -> assertTrue(newCookie.equals(jar.value), "Late response replaced the rotated cookie"),
                    () -> assertEquals(200, after.statusCode(), "Current authority was lost after delayed response"));
                assertEquals("tenant-b", json(server, after).get("selectedTenant"));
                assertEquals(401, send(get(server, "/api/v1/session", oldCookie)).statusCode());
            } finally {
                gate.release.countDown();
                held.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void delayedResponseWithoutRotationPreservesItsValidSession() throws Exception {
        try (var server = server()) {
            String cookie = seed(server);
            var jar = new CookieJar(cookie);
            var gate = server.getApplicationContext().getBean(SessionResponseRaceFixture.class);
            var held = client.sendAsync(get(server, "/api/v1/session-response-race", cookie), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(gate.loaded.await(5, TimeUnit.SECONDS));
                gate.release.countDown();
                var delayed = held.get(5, TimeUnit.SECONDS);
                assertEquals(200, delayed.statusCode());
                jar.apply(delayed);
                assertTrue(cookie.equals(jar.value), "Unchanged authority must remain usable");
                var current = send(get(server, "/api/v1/session", jar.value));
                assertEquals(200, current.statusCode());
                assertEquals("tenant-a", json(server, current).get("selectedTenant"));
            } finally {
                gate.release.countDown();
                held.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private EmbeddedServer server() {
        return ApplicationContext.run(EmbeddedServer.class, Map.of(
            "micronaut.server.port", -1, "micronaut.server.host", "127.0.0.1",
            "dkp.bff.portal-origin", ORIGIN, "dkp.test.session-response-race", true));
    }

    @SuppressWarnings("unchecked") private String seed(EmbeddedServer server) throws Exception {
        SessionStore<Session> store = server.getApplicationContext().getBean(SessionStore.class);
        var identity = new BrowserIdentity("https://issuer.example", "staff-1", "Synthetic Staff",
            List.of("tenant-a", "tenant-b"), true, "synthetic-access", Instant.now().plusSeconds(240));
        Session session = store.newSession();
        session.put(SecurityFilter.AUTHENTICATION, Authentication.build("staff-1", Map.of(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE, identity)));
        session.put("csrfToken", ".fixture-csrf");
        session.put("dkp.selected.tenant", "tenant-a");
        store.save(session).get(5, TimeUnit.SECONDS);
        return COOKIE + "=" + session.getId();
    }

    private HttpRequest get(EmbeddedServer server, String path, String cookie) {
        return HttpRequest.newBuilder(server.getURI().resolve(path)).timeout(Duration.ofSeconds(12))
            .header("Cookie", cookie).GET().build();
    }

    private HttpResponse<String> select(EmbeddedServer server, String cookie, String csrf, String tenant) throws Exception {
        return send(HttpRequest.newBuilder(server.getURI().resolve("/api/v1/session/tenant"))
            .timeout(Duration.ofSeconds(5)).header("Cookie", cookie).header("Origin", ORIGIN)
            .header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"" + tenant + "\"}")).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception { return client.send(request, HttpResponse.BodyHandlers.ofString()); }
    @SuppressWarnings("unchecked") private Map<String, Object> json(EmbeddedServer server, HttpResponse<String> response) throws Exception {
        return server.getApplicationContext().getBean(io.micronaut.json.JsonMapper.class).readValue(response.body(), Map.class);
    }

    /** Applies only this opaque cookie; loopback HTTP uses explicit headers, as existing BFF integration tests do. */
    private static final class CookieJar {
        private String value;
        private CookieJar(String initial) { value = initial; }
        private void apply(HttpResponse<?> response) {
            for (String header : response.headers().allValues("Set-Cookie")) {
                for (HttpCookie cookie : HttpCookie.parse(header)) {
                    if (!COOKIE.equals(cookie.getName())) continue;
                    assertTrue(cookie.getSecure(), "Session cookie stays Secure");
                    assertTrue(cookie.isHttpOnly(), "Session cookie stays HttpOnly");
                    assertEquals("/", cookie.getPath());
                    assertNull(cookie.getDomain());
                    assertTrue(header.toLowerCase(java.util.Locale.ROOT).contains("samesite=lax"));
                    value = cookie.getMaxAge() == 0 ? "" : COOKIE + "=" + cookie.getValue();
                }
            }
        }
    }
}
