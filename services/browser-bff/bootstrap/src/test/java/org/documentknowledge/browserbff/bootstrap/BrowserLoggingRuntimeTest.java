package org.documentknowledge.browserbff.bootstrap;

import com.sun.net.httpserver.*;
import io.micronaut.context.ApplicationContext;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.documentknowledge.browserbff.adapters.inbound.*;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class BrowserLoggingRuntimeTest {
    @TempDir Path temporary;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String DELEGATOR = "d".repeat(64), TOKEN = "p".repeat(64);
    static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    static String sha(byte[] raw) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
    static Path privateFile(Path path, byte[] raw) throws Exception {
        Files.write(path, raw); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); return path;
    }
    @Test void automaticReplayAfterRuntimeReopenUsesOriginalScopeAndBuildThroughRealHttps() throws Exception {
        Path root = temporary.toRealPath(), spool = root.resolve("queue"), keysFile = root.resolve("tls.p12");
        Files.createDirectory(spool, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        String password = UUID.randomUUID().toString();
        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(), "-genkeypair", "-alias", "local",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", keysFile.toString(), "-storepass", password, "-keypass", password)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(keytool.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, keytool.exitValue());
        var keys = KeyStore.getInstance("PKCS12"); try (var input = Files.newInputStream(keysFile)) { keys.load(input, password.toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password.toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        var tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        byte[] caPem = ("-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getCertificate("local").getEncoded())
            + "\n-----END CERTIFICATE-----\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String ca = sha(caPem);
        UUID parent = UUID.randomUUID(), eventId = UUID.randomUUID();
        Map<String, Object> manifest;
        try (var input = getClass().getResourceAsStream("/META-INF/dkp-logging/manifest.json")) {
            manifest = JSON.readValue(input, Map.class);
        }
        manifest.put("component", "admin-portal"); manifest.put("buildId", UUID.randomUUID().toString());
        var vocabulary = new TreeMap<String, Object>((Map<String, Object>)manifest.get("vocabulary"));
        vocabulary.put("phases", List.of("HTTP")); manifest.put("vocabulary", vocabulary);
        manifest.put("vocabularySha256", sha(JSON.writeValueAsBytes(vocabulary)));
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
        var artifact = new BrowserLogContexts.Artifact(UUID.fromString((String)manifest.get("buildId")), "admin-portal",
            (String)manifest.get("gitSha"), (String)manifest.get("sourceFingerprint"), sha(manifestBytes), (String)manifest.get("vocabularySha256"));
        Instant capture = Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var grant = new BrowserLogGateway.Grant(UUID.randomUUID(), artifact, UUID.randomUUID(),
            new BrowserLogContexts.Authority("private-session-nonce", "https://issuer.invalid", "verified-subject", "tenant-a", capture), capture);
        var event = new HashMap<String, Object>(Map.of("eventId", eventId.toString(), "buildId", artifact.buildId().toString(), "runId", grant.runId().toString(),
            "instanceId", UUID.randomUUID().toString(), "component", artifact.component(), "gitSha", artifact.gitSha(), "sourceFingerprint", artifact.sourceFingerprint(),
            "timestamp", Instant.now().toString(), "severity", "DEBUG", "eventName", "operation.started"));
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("context", Map.of("phase", "HTTP", "outcome", "STARTED", "durationMs", 0, "attempt", 1, "attributes", Map.of("count", 1, "cleanupSettled", true)));
        byte[] batch = JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "events", List.of(event)));
        var available = new AtomicBoolean(); var forwards = new AtomicInteger(); var delegations = new AtomicInteger();
        var stored = ConcurrentHashMap.<UUID>newKeySet(); var errors = new ConcurrentLinkedQueue<Throwable>();
        var threads = Executors.newFixedThreadPool(2); var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setHttpsConfigurator(new HttpsConfigurator(tls)); server.setExecutor(threads);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readNBytes(32769); int code; Object response;
                if (exchange.getRequestURI().getPath().endsWith("/delegations")) {
                    assertEquals("Bearer " + DELEGATOR, exchange.getRequestHeaders().getFirst("Authorization")); delegations.incrementAndGet();
                    assertFalse(new String(body, java.nio.charset.StandardCharsets.UTF_8).contains("private-session-nonce")); code = 201;
                    response = Map.of("producerId", parent.toString(), "scopeId", UUID.randomUUID().toString(), "captureUntil", capture.toString(),
                        "replayUntil", capture.plusSeconds(300).toString(), "producerToken", TOKEN);
                } else {
                    assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization")); assertArrayEquals(batch, body); forwards.incrementAndGet();
                    if (available.get()) { stored.add(eventId); code = 200; response = Map.of("receipts", List.of(Map.of("eventId", eventId.toString(), "state", "COMMITTED", "receivedAt", Instant.now().toString()))); }
                    else { code = 503; response = Map.of(); }
                }
                byte[] bytes = JSON.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) { errors.add(failure); }
        }); server.start();
        var settings = new BrowserLoggingFactory.Settings(URI.create("https://localhost:" + server.getAddress().getPort() + "/api/v1/dev-logs"), parent, DELEGATOR,
            tls, ca, spool, Map.of(artifact.buildId(), artifact), Map.of(artifact.buildId(), new BrowserLogJson.Catalog(Set.of("operation.started"), Set.of("HTTP"))));
        try {
            try (var runtime = new BrowserLoggingRuntime(settings, new BrowserLogRequests())) {
                var scope = runtime.gateway().delegate(grant, BrowserLogDeadline.start());
                assertEquals(503, assertThrows(BffFailure.class, () -> runtime.gateway().ingest(scope, batch, BrowserLogDeadline.start())).status());
                assertEquals(1, runtime.status().queue().pendingEvents());
            }
            var newer = new BrowserLogContexts.Artifact(UUID.randomUUID(), "admin-portal", "f".repeat(40), "d".repeat(64), "e".repeat(64), "c".repeat(64));
            var retained = new BrowserLoggingFactory.Settings(settings.endpoint(), parent, DELEGATOR, tls, ca, spool,
                Map.of(artifact.buildId(), artifact, newer.buildId(), newer),
                Map.of(artifact.buildId(), settings.catalogs().get(artifact.buildId()), newer.buildId(), settings.catalogs().get(artifact.buildId())));
            int prior = forwards.get();
            try (var reopened = new BrowserLoggingRuntime(retained, new BrowserLogRequests())) {
                until(() -> forwards.get() > prior);
                assertEquals(1, reopened.status().queue().pendingEvents());
            }
            Path caFile = privateFile(root.resolve("ca.pem"), caPem);
            Path manifestFile = privateFile(root.resolve("portal.json"), manifestBytes);
            Path config = privateFile(root.resolve("browser.json"), JSON.writeValueAsBytes(Map.of("schemaVersion", 1,
                "endpoint", settings.endpoint().toASCIIString(), "parentProducerId", parent.toString(), "delegatorToken", DELEGATOR,
                "caFile", caFile.toString(), "spoolDirectory", spool.toString(),
                "portalManifests", List.of(Map.of("file", manifestFile.toString(), "sha256", artifact.manifestSha256())))));
            int startupForwards = forwards.get();
            // Starting the real factory must reopen/replay without getBean() or any logging HTTP request.
            try (var context = ApplicationContext.run(Map.of("dkp.logging.browser-config", config.toString()))) {
                until(() -> forwards.get() > startupForwards);
                available.set(true);
                until(() -> {
                    try { return Integer.valueOf(0).equals(JSON.readValue(Files.readAllBytes(spool.resolve("health.json")), Map.class).get("pendingEvents")); }
                    catch (Exception unavailable) { return false; }
                });
                assertEquals(Set.of(eventId), stored); assertEquals(1, delegations.get());
                assertEquals(1, JSON.readValue(Files.readAllBytes(spool.resolve("health.json")), Map.class).get("committedEvents"));
            }
            var health = JSON.readValue(Files.readAllBytes(spool.resolve("health.json")), Map.class);
            assertEquals(true, health.get("closed")); assertEquals(false, health.get("storageUncertain"));
            byte[] stable = Files.readAllBytes(spool.resolve("pending.bin"));
            var changed = new BrowserLoggingFactory.Settings(settings.endpoint(), UUID.randomUUID(), DELEGATOR, tls, ca, spool, settings.artifacts(), settings.catalogs());
            try (var rejectedRequests = new BrowserLogRequests()) { assertThrows(java.io.IOException.class, () -> new BrowserLoggingRuntime(changed, rejectedRequests)); }
            assertArrayEquals(stable, Files.readAllBytes(spool.resolve("pending.bin"))); assertTrue(errors.isEmpty(), errors::toString);
        } finally { server.stop(0); threads.shutdownNow(); assertTrue(threads.awaitTermination(3, TimeUnit.SECONDS)); }
    }
}
