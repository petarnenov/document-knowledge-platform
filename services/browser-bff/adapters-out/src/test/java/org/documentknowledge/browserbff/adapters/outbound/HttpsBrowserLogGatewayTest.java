package org.documentknowledge.browserbff.adapters.outbound;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class HttpsBrowserLogGatewayTest {
    @TempDir static Path temporary;
    static SSLContext tls;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID PARENT = UUID.randomUUID();
    static final String DELEGATOR = "d".repeat(64), PRODUCER = "p".repeat(64);
    static final BrowserLogContexts.Artifact ARTIFACT = new BrowserLogContexts.Artifact(UUID.randomUUID(), "admin-portal",
        "a".repeat(40), "b".repeat(64), "c".repeat(64), "e".repeat(64));
    final List<HttpsBrowserLogGateway> gateways = new ArrayList<>();
    HttpsServer server; ExecutorService threads; URI endpoint;

    @BeforeAll static void certificate() throws Exception {
        Path store = temporary.resolve("tls.p12"); String password = UUID.randomUUID().toString();
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(), "-genkeypair", "-alias", "local",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", password, "-keypass", password)
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        var keys = KeyStore.getInstance("PKCS12"); try (var input = Files.newInputStream(store)) { keys.load(input, password.toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password.toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    }
    void start(HttpHandler handler) throws Exception {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 8); server.setHttpsConfigurator(new HttpsConfigurator(tls));
        threads = Executors.newFixedThreadPool(2); server.setExecutor(threads); server.createContext("/", handler); server.start();
        endpoint = URI.create("https://localhost:" + server.getAddress().getPort() + "/api/v1/dev-logs");
    }
    HttpsBrowserLogGateway gateway(SSLContext selected, Clock clock) {
        var gateway = new HttpsBrowserLogGateway(endpoint, PARENT, DELEGATOR, selected, clock); gateways.add(gateway); return gateway;
    }
    @AfterEach void stop() throws Exception {
        for (var gateway : gateways) gateway.close();
        if (server != null) server.stop(0); if (threads != null) threads.shutdownNow();
        for (var gateway : gateways) assertTrue(gateway.awaitClosed(Duration.ofSeconds(3)));
    }
    static BrowserLogGateway.Grant grant(String tenant) {
        var until = Instant.now().plusSeconds(60).with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789);
        return new BrowserLogGateway.Grant(UUID.randomUUID(), ARTIFACT, UUID.randomUUID(),
            new BrowserLogContexts.Authority("private-session-nonce", "https://issuer.invalid", "verified-subject", tenant, until), until);
    }
    static Map<String, Object> reply(BrowserLogGateway.Grant grant) {
        return new HashMap<>(Map.of("producerId", PARENT.toString(), "scopeId", UUID.randomUUID().toString(),
            "captureUntil", grant.captureUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(), "replayUntil", grant.captureUntil().plusSeconds(60).toString(), "producerToken", PRODUCER));
    }
    static void respond(HttpExchange exchange, int status, String type, byte[] body) throws java.io.IOException {
        exchange.getRequestBody().readNBytes(32769); exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
    }
    static byte[] bytes(Object value) { return JSON.writeValueAsBytes(value); }
    static void unavailable(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(BffFailure.class, action); assertEquals(503, failure.status());
        assertEquals("LOG_STORE_UNAVAILABLE", failure.getMessage()); assertNull(failure.getCause());
    }

    @Test void sourceBridgeDelegationAndFixedScopedRoutesKeepCredentialsServerSide() throws Exception {
        var grant = grant("tenant-a"); var seen = new CopyOnWriteArrayList<String>(); var delegated = new AtomicReference<Map<?, ?>>();
        start(exchange -> { try (exchange) {
            seen.add(exchange.getRequestURI().getPath()); assertEquals("POST", exchange.getRequestMethod());
            if (exchange.getRequestURI().getPath().endsWith("/delegations")) {
                assertEquals("Bearer " + DELEGATOR, exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = exchange.getRequestBody().readNBytes(32769); delegated.set(JSON.readValue(body, Map.class));
                assertFalse(new String(body, StandardCharsets.UTF_8).contains("private-session-nonce"));
                respond(exchange, 201, "application/json", bytes(reply(grant)));
            } else {
                assertEquals("Bearer " + PRODUCER, exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("{}", new String(exchange.getRequestBody().readNBytes(32769), StandardCharsets.UTF_8));
                respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            }
        } });
        var gateway = gateway(tls, Clock.systemUTC()); var scope = gateway.delegate(grant, BrowserLogDeadline.start());
        assertEquals(Set.of("parentProducerId", "binding", "scope", "contextEpoch", "captureUntil"), delegated.get().keySet());
        var binding = (Map<?, ?>) delegated.get().get("binding");
        assertEquals(ARTIFACT.buildId().toString(), binding.get("buildId")); assertEquals(grant.runId().toString(), binding.get("runId"));
        assertEquals(ARTIFACT.manifestSha256(), binding.get("manifestSha256"));
        assertEquals(Map.of("scopeKind", "TENANT", "issuer", grant.authority().issuer(), "subject", "verified-subject", "tenantId", "tenant-a"), delegated.get().get("scope"));
        assertFalse(scope.toString().contains(PRODUCER));
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), gateway.ingest(scope, new byte[]{'{', '}'}, BrowserLogDeadline.start()));
        gateway.receipts(scope, new byte[]{'{', '}'}, BrowserLogDeadline.start());
        assertEquals(List.of("/api/v1/dev-logs/delegations", "/api/v1/dev-logs/batches", "/api/v1/dev-logs/receipts"), seen);
        var other = gateway(tls, Clock.systemUTC()); unavailable(() -> other.ingest(scope, new byte[]{1}, BrowserLogDeadline.start()));
        unavailable(() -> gateway.receipts(new BrowserLogGateway.Scope() { }, new byte[]{1}, BrowserLogDeadline.start()));
        unavailable(() -> gateway.ingest(null, new byte[]{1}, BrowserLogDeadline.start()));
        for (byte[] body : new byte[][]{null, new byte[0], new byte[32769]}) unavailable(() -> gateway.ingest(scope, body, BrowserLogDeadline.start()));
        gateway.close(); gateway.close(); unavailable(() -> gateway.ingest(scope, new byte[]{1}, BrowserLogDeadline.start()));
    }

    @Test void authenticatedUnselectedAndExpiredOrForeignScopes() throws Exception {
        var grant = grant(null); var now = new AtomicReference<>(Instant.now());
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId ignored) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        start(exchange -> { try (exchange) {
            var body = JSON.readValue(exchange.getRequestBody().readNBytes(32769), Map.class);
            assertEquals(Map.of("scopeKind", "AUTHENTICATED_UNSELECTED", "issuer", grant.authority().issuer(), "subject", "verified-subject"), body.get("scope"));
            respond(exchange, 201, "application/json", bytes(reply(grant)));
        } });
        var gateway = gateway(tls, clock); var scope = gateway.delegate(grant, BrowserLogDeadline.start());
        now.set(grant.captureUntil().plusSeconds(61));
        unavailable(() -> gateway.receipts(scope, new byte[]{1}, BrowserLogDeadline.start()));
        unavailable(() -> gateway.delegate(grant, BrowserLogDeadline.start()));
    }

    @Test void malformedDelegationReplyNeverCreatesScope() throws Exception {
        var grant = grant("tenant-a"); var raw = new AtomicReference<>(bytes(reply(grant)));
        start(exchange -> { try (exchange) { respond(exchange, 201, "application/json", raw.get()); } });
        var gateway = gateway(tls, Clock.systemUTC());
        var invalid = new ArrayList<byte[]>();
        for (String field : List.of("producerId", "scopeId", "captureUntil", "replayUntil", "producerToken")) {
            var row = reply(grant); row.put(field, "invalid"); invalid.add(bytes(row));
            row = reply(grant); row.remove(field); invalid.add(bytes(row));
        }
        var row = reply(grant); row.put("extra", "secret"); invalid.add(bytes(row));
        row = reply(grant); row.put("producerId", UUID.randomUUID().toString()); invalid.add(bytes(row));
        row = reply(grant); row.put("scopeId", new UUID(0, 0).toString()); invalid.add(bytes(row));
        row = reply(grant); row.put("captureUntil", grant.captureUntil().plusSeconds(1).toString()); invalid.add(bytes(row));
        row = reply(grant); row.put("replayUntil", grant.captureUntil().minusSeconds(1).toString()); invalid.add(bytes(row));
        row = reply(grant); row.put("captureUntil", grant.captureUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusNanos(1).toString()); invalid.add(bytes(row));
        invalid.add(new byte[]{(byte) 0xc3, 0x28}); invalid.add("{} {}".getBytes(StandardCharsets.UTF_8));
        invalid.add("{\"producerId\":1,\"producerId\":2}".getBytes(StandardCharsets.UTF_8)); invalid.add(new byte[32769]);
        for (byte[] body : invalid) { raw.set(body); unavailable(() -> gateway.delegate(grant, BrowserLogDeadline.start())); }
    }

    @Test void refusesUntrustedTlsRedirectsWrongTypesAndCompressedResponses() throws Exception {
        var grant = grant("tenant-a"); var status = new AtomicInteger(201); var type = new AtomicReference<>("application/json"); var compressed = new AtomicBoolean();
        start(exchange -> { try (exchange) {
            exchange.getResponseHeaders().set("Location", "/redirected");
            if (compressed.get()) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            respond(exchange, status.get(), type.get(), bytes(reply(grant)));
        } });
        var gateway = gateway(tls, Clock.systemUTC());
        var untrusted = gateway(SSLContext.getDefault(), Clock.systemUTC()); unavailable(() -> untrusted.delegate(grant, BrowserLogDeadline.start()));
        for (int code : new int[]{302, 401, 403, 429, 503}) { status.set(code); unavailable(() -> gateway.delegate(grant, BrowserLogDeadline.start())); }
        status.set(201); type.set("text/html"); unavailable(() -> gateway.delegate(grant, BrowserLogDeadline.start()));
        type.set("application/json"); compressed.set(true); unavailable(() -> gateway.delegate(grant, BrowserLogDeadline.start()));
    }

    @Test void originalAdmissionBudgetIncludesTimeBeforeHttpsAndInterruptsArePreserved() throws Exception {
        var grant = grant("tenant-a"); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        start(exchange -> { try (exchange) {
            entered.countDown(); try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
            respond(exchange, 201, "application/json", bytes(reply(grant)));
        } });
        var gateway = gateway(tls, Clock.systemUTC()); var deadline = BrowserLogDeadline.start();
        Thread.sleep(4200);
        long started = System.nanoTime();
        try {
            unavailable(() -> gateway.delegate(grant, deadline));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2000);
            unavailable(() -> gateway.delegate(grant, deadline));
            Thread.currentThread().interrupt();
            unavailable(() -> gateway.delegate(grant, BrowserLogDeadline.start())); assertTrue(Thread.interrupted());
        } finally { Thread.interrupted(); release.countDown(); }
    }

    @Test void configurationAndResponseSubscriberAreBounded() throws Exception {
        for (String uri : List.of("http://localhost/api/v1/dev-logs", "https://user:secret@localhost/api/v1/dev-logs",
                "https://localhost/api/v1/dev-logs?x", "https://localhost/api/v1/dev-logs#x", "https://localhost/other"))
            assertThrows(IllegalArgumentException.class, () -> new HttpsBrowserLogGateway(URI.create(uri), PARENT, DELEGATOR, tls, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new HttpsBrowserLogGateway(URI.create("https://localhost/api/v1/dev-logs"), new UUID(0, 0), DELEGATOR, tls, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new HttpsBrowserLogGateway(URI.create("https://localhost/api/v1/dev-logs"), PARENT, "secret\r\n", tls, Clock.systemUTC()));
        var cancelled = new AtomicInteger();
        var subscription = new Flow.Subscription() { public void request(long count) { } public void cancel() { cancelled.incrementAndGet(); } };
        var body = new HttpsBrowserLogGateway.LimitedBody(); body.onSubscribe(subscription); body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[32768]))); body.onComplete(); assertEquals(32768, body.getBody().toCompletableFuture().join().length);
        body.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        var overflow = new HttpsBrowserLogGateway.LimitedBody(); overflow.onSubscribe(subscription); overflow.onNext(List.of(ByteBuffer.wrap(new byte[32769])));
        assertThrows(CompletionException.class, () -> overflow.getBody().toCompletableFuture().join());
        var failed = new HttpsBrowserLogGateway.LimitedBody(); failed.onError(new IllegalStateException("secret")); failed.onSubscribe(subscription);
        assertThrows(CompletionException.class, () -> failed.getBody().toCompletableFuture().join()); assertEquals(3, cancelled.get());
    }
    @Test void directProxyPolicyNeverConsultsAmbientFallbackEvenAfterConnectionFailure() {
        var previous = ProxySelector.getDefault(); var ambient = new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { ambient.incrementAndGet(); throw new AssertionError("Ambient proxy consulted"); }
            @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { ambient.incrementAndGet(); }
        });
        try {
            var policy = new HttpsBrowserLogGateway.DirectProxy(); var target = URI.create("https://localhost/api/v1/dev-logs");
            assertEquals(List.of(Proxy.NO_PROXY), policy.select(target));
            policy.connectFailed(target, new InetSocketAddress("127.0.0.1", 443), new java.io.IOException("synthetic failure"));
            assertEquals(List.of(Proxy.NO_PROXY), policy.select(target)); assertEquals(0, ambient.get());
        } finally { ProxySelector.setDefault(previous); }
    }
}
