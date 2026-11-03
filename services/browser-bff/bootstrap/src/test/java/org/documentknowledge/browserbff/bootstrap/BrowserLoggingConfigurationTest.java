package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.documentknowledge.browserbff.adapters.inbound.*;
import org.documentknowledge.browserbff.adapters.outbound.DurableBrowserLogGateway;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class BrowserLoggingConfigurationTest {
    @TempDir Path temporary;
    @TempDir static Path certificates;
    static byte[] ca;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TOKEN = "t".repeat(64);
    @BeforeAll static void certificate() throws Exception {
        var store = certificates.resolve("tls.p12"); var certificate = certificates.resolve("ca.pem"); var password = UUID.randomUUID().toString();
        var keytool = Path.of(System.getProperty("java.home"), "bin/keytool").toString();
        run(keytool, "-genkeypair", "-alias", "local", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", password, "-keypass", password);
        run(keytool, "-exportcert", "-rfc", "-alias", "local", "-keystore", store.toString(), "-storepass", password, "-file", certificate.toString());
        ca = Files.readAllBytes(certificate);
    }
    static void run(String... command) throws Exception {
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
    }
    Path write(String name, byte[] raw) throws Exception {
        var path = temporary.toRealPath().resolve(name); Files.write(path, raw); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); return path;
    }
    static String sha(byte[] raw) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
    Map<String, Object> manifest() throws Exception {
        try (var input = getClass().getResourceAsStream("/META-INF/dkp-logging/manifest.json")) {
            @SuppressWarnings("unchecked") var row = (Map<String, Object>) JSON.readValue(input, Map.class);
            row.put("component", "admin-portal");
            @SuppressWarnings("unchecked") var vocabulary = new TreeMap<>((Map<String, Object>) row.get("vocabulary"));
            row.put("vocabularySha256", sha(JSON.writeValueAsBytes(vocabulary))); return row;
        }
    }
    Map<String, Object> config(Map<String, Object> manifest) throws Exception {
        byte[] raw = JSON.writeValueAsBytes(manifest); var path = write("portal.json", raw); var caPath = write("ca.pem", ca);
        Path spool = temporary.toRealPath().resolve("browser-spool");
        if (!Files.exists(spool)) Files.createDirectory(spool, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return new HashMap<>(Map.of("schemaVersion", 1, "endpoint", "https://localhost:18443/api/v1/dev-logs", "parentProducerId", UUID.randomUUID().toString(),
            "delegatorToken", TOKEN, "caFile", caPath.toString(), "spoolDirectory", spool.toString(),
            "portalManifests", List.of(Map.of("file", path.toString(), "sha256", sha(raw)))));
    }
    static void invalid(Path path) {
        var failure = assertThrows(IOException.class, () -> BrowserLoggingFactory.read(path));
        assertEquals("INVALID_BROWSER_LOG_CONFIGURATION", failure.getMessage()); assertNull(failure.getCause());
    }
    @Test void privateConfigurationComposesOnlyInstalledOriginalArtifactsAndOwnedLifecycles() throws Exception {
        var manifest = manifest(); var config = config(manifest); var path = write("configuration.json", JSON.writeValueAsBytes(config));
        var settings = BrowserLoggingFactory.read(path); assertFalse(settings.toString().contains(TOKEN));
        var build = UUID.fromString((String) manifest.get("buildId"));
        assertEquals(manifest.get("sourceFingerprint"), settings.artifacts().get(build).sourceFingerprint());
        assertEquals(manifest.get("vocabularySha256"), settings.artifacts().get(build).vocabularySha256());
        assertThrows(UnsupportedOperationException.class, () -> settings.artifacts().clear());
        BrowserLoggingRuntime runtime; BrowserLogRequests requests;
        try (var context = ApplicationContext.run(Map.of("dkp.logging.browser-config", path.toString()))) {
            assertInstanceOf(DurableBrowserLogGateway.class, context.getBean(BrowserLogGateway.class));
            runtime = context.getBean(BrowserLoggingRuntime.class); requests = context.getBean(BrowserLogRequests.class);
            assertNotNull(context.getBean(BrowserLogController.class)); assertNotNull(context.getBean(BrowserLogContexts.class));
            assertTrue(context.getBean(BrowserLogCatalogs.class).require(build).events().contains("process.started"));
        }
        assertTrue(runtime.awaitClosed(Duration.ofSeconds(2))); assertTrue(runtime.status().stopped()); assertFalse(runtime.status().incompleteShutdown());
        assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); assertTrue(requests.health().closed());
        try (var disabled = ApplicationContext.run()) { assertFalse(disabled.containsBean(BrowserLogGateway.class)); }
    }
    @Test void malformedAndCallerAuthorityConfigurationNeverEnablesBrowserLogging() throws Exception {
        var config = config(manifest());
        for (String field : List.of("endpoint", "parentProducerId", "delegatorToken", "caFile", "portalManifests", "schemaVersion", "spoolDirectory")) {
            var copy = new HashMap<>(config); copy.remove(field); invalid(write("configuration.json", JSON.writeValueAsBytes(copy)));
        }
        for (Object value : List.of("http://localhost/api/v1/dev-logs", "https://user:secret@localhost/api/v1/dev-logs", "https://localhost/api/v1/dev-logs?x", "https://localhost/api/v1/dev-logs#x", "https://localhost/other")) {
            var copy = new HashMap<>(config); copy.put("endpoint", value); invalid(write("configuration.json", JSON.writeValueAsBytes(copy)));
        }
        for (var entry : Map.<String, Object>of("schemaVersion", 2, "parentProducerId", new UUID(0, 0).toString(), "delegatorToken", "secret\r\n", "portalManifests", List.of()).entrySet()) {
            var copy = new HashMap<>(config); copy.put(entry.getKey(), entry.getValue()); invalid(write("configuration.json", JSON.writeValueAsBytes(copy)));
        }
        config.put("tenantId", "untrusted"); invalid(write("configuration.json", JSON.writeValueAsBytes(config)));
        for (byte[] raw : List.of("{}{}".getBytes(StandardCharsets.UTF_8), "{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xc3, 0x28}, new byte[32769]))
            invalid(write("configuration.json", raw));
    }
    @Test void changedManifestIdentityDigestCatalogAndDuplicateBuildAreRejected() throws Exception {
        var manifest = manifest();
        for (var entry : Map.<String, Object>of("component", "browser-bff", "sourceFingerprintAlgorithm", "wrong", "dirty", "true",
                "gitSha", "bad", "compiledAt", "bad", "toolchainFingerprint", "bad", "vocabularySha256", "e".repeat(64)).entrySet()) {
            var copy = new HashMap<>(manifest); copy.put(entry.getKey(), entry.getValue());
            invalid(write("configuration.json", JSON.writeValueAsBytes(config(copy))));
        }
        for (String stamp : List.of("2026-11-03T24:00:00Z", "+10000-11-03T04:00:00Z")) {
            var copy = new HashMap<>(manifest); copy.put("compiledAt", stamp);
            invalid(write("configuration.json", JSON.writeValueAsBytes(config(copy))));
        }
        var config = config(manifest); var installed = ((List<?>) config.get("portalManifests")).getFirst();
        config.put("portalManifests", List.of(installed, installed)); invalid(write("configuration.json", JSON.writeValueAsBytes(config)));
        config = config(manifest); Files.writeString(temporary.toRealPath().resolve("portal.json"), "{}");
        invalid(write("configuration.json", JSON.writeValueAsBytes(config)));
        config = config(manifest); Files.writeString(temporary.toRealPath().resolve("ca.pem"), "not a certificate");
        invalid(write("configuration.json", JSON.writeValueAsBytes(config)));
    }
    @Test void privatePathsPermissionsAndByteBoundsFailClosed() throws Exception {
        var config = config(manifest()); var path = write("configuration.json", JSON.writeValueAsBytes(config));
        invalid(Path.of("relative.json")); invalid(temporary.resolve("absent.json"));
        var link = temporary.toRealPath().resolve("link.json"); Files.createSymbolicLink(link, path); invalid(link);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--")); invalid(path);
        path = write("configuration.json", JSON.writeValueAsBytes(config));
        Files.setPosixFilePermissions(temporary.toRealPath().resolve("portal.json"), PosixFilePermissions.fromString("rw-r--r--")); invalid(path);
        config = config(manifest()); path = write("configuration.json", JSON.writeValueAsBytes(config));
        Files.write(temporary.toRealPath().resolve("portal.json"), new byte[1048577]); invalid(path);
        write("portal.json", new byte[0]); invalid(path);
    }
    @Test void spoolMustAlreadyExistBeCanonicalAndPrivateAndCorruptStateIsPreserved() throws Exception {
        var config = config(manifest()); var path = write("configuration.json", JSON.writeValueAsBytes(config));
        Path spool = Path.of((String)config.get("spoolDirectory"));
        for (String invalid : List.of("relative", temporary.toRealPath().resolve("missing").toString(), path.toString())) {
            var copy = new HashMap<>(config); copy.put("spoolDirectory", invalid); invalid(write("configuration.json", JSON.writeValueAsBytes(copy)));
        }
        Path alias = temporary.toRealPath().resolve("spool-link"); Files.createSymbolicLink(alias, spool);
        var copy = new HashMap<>(config); copy.put("spoolDirectory", alias.toString()); invalid(write("configuration.json", JSON.writeValueAsBytes(copy)));
        Files.setPosixFilePermissions(spool, PosixFilePermissions.fromString("rwxr-xr-x")); invalid(write("configuration.json", JSON.writeValueAsBytes(config)));
        Files.setPosixFilePermissions(spool, PosixFilePermissions.fromString("rwx------"));
        var settings = BrowserLoggingFactory.read(write("configuration.json", JSON.writeValueAsBytes(config)));
        assertEquals(sha(ca), settings.caSha256());
        var requests = new BrowserLogRequests();
        try (var runtime = new BrowserLoggingRuntime(settings, requests)) { assertEquals(0, runtime.status().queue().pendingEvents()); }
        Files.write(spool.resolve("pending.bin"), new byte[]{1, 2, 3});
        Files.setPosixFilePermissions(spool.resolve("pending.bin"), PosixFilePermissions.fromString("rw-------"));
        try (var otherRequests = new BrowserLogRequests()) {
            var failure = assertThrows(IOException.class, () -> new BrowserLoggingRuntime(settings, otherRequests));
            assertEquals("INVALID_BROWSER_LOG_CONFIGURATION", failure.getMessage());
        }
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(spool.resolve("pending.bin")));
    }
    @Test void interruptedCloseReportsIncompleteUntilAllActualDispatcherWorkersExit() throws Exception {
        var config = config(manifest()); var settings = BrowserLoggingFactory.read(write("configuration.json", JSON.writeValueAsBytes(config)));
        var requests = new BrowserLogRequests(); var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var runtime = new BrowserLoggingRuntime(settings, requests);
        try {
            requests.submit(BrowserLogDeadline.start(), deadline -> {
                entered.countDown(); boolean interrupted = false;
                for (;;) try { release.await(); break; } catch (InterruptedException signal) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt(); return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); Thread.currentThread().interrupt(); runtime.close();
            assertTrue(Thread.interrupted()); assertTrue(runtime.status().incompleteShutdown()); assertFalse(runtime.status().stopped());
            assertFalse(runtime.status().queue().storageUncertain());
            assertFalse(runtime.awaitClosed(Duration.ofMillis(1100))); release.countDown();
            assertTrue(runtime.awaitClosed(Duration.ofSeconds(2))); assertTrue(runtime.status().stopped()); assertFalse(runtime.status().incompleteShutdown());
        } finally { release.countDown(); runtime.close(); assertTrue(requests.awaitClosed(Duration.ofSeconds(2))); }
    }
}
