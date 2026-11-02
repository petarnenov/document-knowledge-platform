package org.documentknowledge.devlogging.adapters.inbound;

import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.devlogging.adapters.inbound.requests.DeadlineLogRequests;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP adapter integration; store port fixtures are not claimed as persistent-runtime E2E. */
class LogQueryControllerTest {
    final Instant now = Instant.now();
    final LogReader reader = new LogReader(UUID.randomUUID(), false, "https://issuer.invalid", "tenant-a", now.plusSeconds(3600));
    final String window = "from=" + now.minusSeconds(60) + "&to=" + now.plusSeconds(60);
    final AtomicReference<LogFailure.Kind> failure = new AtomicReference<>();
    final AtomicReference<LogScope> scope = new AtomicReference<>(new LogScope(UUID.randomUUID(), LogScope.Kind.TENANT,
        "https://issuer.invalid", "actor", "tenant-a"));
    final AtomicReference<byte[]> payload = new AtomicReference<>("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    final AtomicReference<LogQuery> observed = new AtomicReference<>();

    class Fixture implements AutoCloseable {
        final DeadlineLogRequests requests = new DeadlineLogRequests();
        final ApplicationContext context;
        final EmbeddedServer server;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Fixture() {
            LogReaderAuthentication auth = token -> {
                if (token.equals("valid-reader")) return reader;
                if (token.equals("operator")) return new LogReader(reader.id(), true, null, null, reader.expiresAt());
                if (token.equals("expired")) throw new LogFailure(LogFailure.Kind.EXPIRED);
                throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
            };
            var cursors = new LogQueries.Cursors() {
                public String encode(LogReader r, LogQuery q, LogQueries.Position p) { return "opaque-page"; }
                public LogQueries.Position decode(LogReader r, LogQuery q, String c) { throw new LogFailure(LogFailure.Kind.INVALID); }
            };
            var service = new LogQueries((r,q,p,d) -> {
                observed.set(q);
                if (failure.get() != null) throw new LogFailure(failure.get());
                var event = new LogQueries.Stored(UUID.randomUUID(), scope.get(), UUID.randomUUID(), now, payload.get());
                return List.of(new LogQueries.Row(event, 1234567890123L), new LogQueries.Row(event, 1234567890122L));
            }, cursors, Clock.systemUTC());
            context = ApplicationContext.builder().properties(Map.of(
                "micronaut.server.host", "127.0.0.1", "micronaut.server.port", -1,
                "micronaut.server.netty.worker.threads", 2, "micronaut.server.netty.parent.threads", 1,
                "micronaut.server.max-request-size", 32768, "micronaut.server.max-request-buffer-size", 32768)).build();
            context.registerSingleton(LogReaderAuthentication.class, auth);
            context.registerSingleton(LogQueries.class, service);
            context.registerSingleton(DeadlineLogRequests.class, requests);
            context.start(); server = context.getBean(EmbeddedServer.class).start();
        }
        HttpResponse<String> get(String query, String... tokens) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/v1/dev-logs/events?" + query))
                .timeout(Duration.ofSeconds(10)).GET();
            for (var token : tokens) request.header("Authorization", token);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        public void close() { client.close(); server.close(); context.close(); requests.close(); }
    }

    @Test void realHttpRequiresDedicatedAuthAndRejectsAmbiguousOrUnboundedFilters() throws Exception {
        try (var f = new Fixture()) {
            for (String[] tokens : List.of(new String[]{}, new String[]{"Basic arbitrary"}, new String[]{"Bearer producer"},
                    new String[]{"Bearer valid-reader", "Bearer valid-reader"})) {
                var response = f.get(window, tokens);
                assertEquals(401, response.statusCode()); assertTrue(response.body().contains("AUTHENTICATION_REQUIRED"));
                assertFalse(response.body().contains("valid-reader"));
            }
            assertEquals(403, f.get(window, "Bearer expired").statusCode());
            for (String query : List.of("", "from=invalid&to=invalid", window + "&unknown=secret", window + "&limit=1&limit=2",
                    window + "&buildId=1-1-1-1-1", window + "&buildId=00000000-0000-0000-0000-000000000000",
                    window + "&limit=201", window + "&limit=-1", window + "&minSeverity=WRONG", window + "&cursor=bad",
                    window + "&component=" + "a".repeat(97))) {
                var response = f.get(query, "Bearer valid-reader");
                assertEquals(400, response.statusCode(), query.substring(0, Math.min(80, query.length())));
                assertTrue(response.body().contains("INVALID_LOG_QUERY")); assertFalse(response.body().contains("secret"));
            }
        }
    }

    @Test void realHttpResponseOmitsInternalSequenceAndUsesFixedSafeFailures() throws Exception {
        try (var f = new Fixture()) {
            UUID build = UUID.randomUUID(); UUID run = UUID.randomUUID(); UUID correlation = UUID.randomUUID();
            var result = f.get(window + "&limit=1&buildId=" + build + "&runId=" + run
                + "&component=browser-bff&minSeverity=DEBUG&correlationId=" + correlation + "&traceId=" + "1".repeat(32), "bearer valid-reader");
            assertEquals(200, result.statusCode()); assertEquals(build, observed.get().buildId());
            assertEquals(run, observed.get().runId()); assertEquals(correlation, observed.get().correlationId());
            assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", result.headers().firstValue("X-Content-Type-Options").orElseThrow());
            var wire = f.context.getBean(JsonMapper.class).readValue(result.body(), Map.class);
            assertEquals(Set.of("events", "nextCursor"), wire.keySet()); assertEquals("opaque-page", wire.get("nextCursor"));
            assertFalse(result.body().contains("sequence")); assertFalse(result.body().contains("1234567890123"));
            assertTrue(result.body().contains("tenant-a"));
            scope.set(new LogScope(UUID.randomUUID(), LogScope.Kind.OPERATOR_ONLY, null, null, null));
            assertEquals(200, f.get(window, "Bearer operator").statusCode());
            assertEquals(50, observed.get().limit());
            for (var kind : List.of(LogFailure.Kind.FORBIDDEN, LogFailure.Kind.RATE_LIMITED,
                    LogFailure.Kind.UNAVAILABLE, LogFailure.Kind.CAPACITY)) {
                failure.set(kind); var response = f.get(window, "Bearer valid-reader");
                assertEquals(kind == LogFailure.Kind.FORBIDDEN ? 403 : kind == LogFailure.Kind.RATE_LIMITED ? 429 : 503, response.statusCode());
            }
            failure.set(null); payload.set("{".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(503, f.get(window, "Bearer valid-reader").statusCode());
        }
    }
}
