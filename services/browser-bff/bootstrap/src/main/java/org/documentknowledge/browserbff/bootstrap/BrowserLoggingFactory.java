package org.documentknowledge.browserbff.bootstrap;

import com.sun.security.auth.module.UnixSystem;
import io.micronaut.context.annotation.*;
import jakarta.inject.Singleton;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.documentknowledge.browserbff.adapters.inbound.*;
import org.documentknowledge.browserbff.application.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Optional private composition of the installed portal artifacts and finite server delegation grant. */
@Factory
@Requires(property = "dkp.logging.browser-config")
public final class BrowserLoggingFactory {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(1048576).maxNestingDepth(16)
            .maxStringLength(4096).maxNumberLength(16).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Settings(URI endpoint, UUID parent, String delegator, SSLContext tls, String caSha256, Path spoolDirectory,
                    Map<UUID, BrowserLogContexts.Artifact> artifacts, Map<UUID, BrowserLogJson.Catalog> catalogs) {
        @Override public String toString() { return "BrowserLoggingSettings[REDACTED]"; }
    }
    private final Settings settings;
    public BrowserLoggingFactory(@Value("${dkp.logging.browser-config}") String path) throws IOException { settings = read(Path.of(path)); }
    @Singleton @Bean(preDestroy = "close") BrowserLoggingRuntime runtime(BrowserLogRequests requests) throws IOException {
        return new BrowserLoggingRuntime(settings, requests);
    }
    @Singleton public BrowserLogGateway gateway(BrowserLoggingRuntime runtime) { return runtime.gateway(); }
    @Singleton public BrowserLogContexts contexts() { return new BrowserLogContexts(Clock.systemUTC(), settings.artifacts()); }
    @Singleton public BrowserLogCatalogs catalogs() { return new BrowserLogCatalogs(settings.catalogs()); }
    @Singleton @Bean(preDestroy = "close") public BrowserLogRequests requests() { return new BrowserLogRequests(); }

    static Settings read(Path path) throws IOException {
        try {
            var config = object(parse(privateBytes(path, 32768)), Set.of("schemaVersion", "endpoint", "parentProducerId", "delegatorToken", "caFile", "portalManifests", "spoolDirectory"));
            require(Integer.valueOf(1).equals(config.get("schemaVersion")));
            URI endpoint = URI.create(text(config, "endpoint"));
            require("https".equals(endpoint.getScheme()) && endpoint.getHost() != null && endpoint.getUserInfo() == null
                && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null && "/api/v1/dev-logs".equals(endpoint.getRawPath()));
            UUID parent = BrowserLogJson.id(config.get("parentProducerId")); String delegator = text(config, "delegatorToken");
            require(delegator.matches("[A-Za-z0-9_-]{32,512}"));
            Path spool = Path.of(text(config, "spoolDirectory"));
            require(spool.isAbsolute() && spool.toRealPath().equals(spool) && Files.isDirectory(spool, LinkOption.NOFOLLOW_LINKS)
                && Files.getPosixFilePermissions(spool, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))
                && ((Number) Files.getAttribute(spool, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() == new UnixSystem().getUid());
            var artifacts = new HashMap<UUID, BrowserLogContexts.Artifact>(); var catalogs = new HashMap<UUID, BrowserLogJson.Catalog>();
            require(config.get("portalManifests") instanceof List<?> manifests && !manifests.isEmpty() && manifests.size() <= 64);
            for (Object value : (List<?>) config.get("portalManifests")) {
                var installed = object(value, Set.of("file", "sha256")); byte[] raw = privateBytes(Path.of(text(installed, "file")), 1048576);
                require(sha(raw).equals(text(installed, "sha256")));
                var manifest = object(parse(raw), Set.of("schemaVersion", "buildId", "component", "gitSha", "sourceFingerprint", "compiledAt",
                    "toolchainFingerprint", "dependencyFingerprint", "vocabularySha256", "vocabulary", "sourceFingerprintAlgorithm", "dirty"));
                require(Integer.valueOf(1).equals(manifest.get("schemaVersion")) && manifest.get("dirty") instanceof Boolean
                    && "dkp-public-inputs-v1".equals(manifest.get("sourceFingerprintAlgorithm")));
                BrowserLogJson.timestamp(text(manifest, "compiledAt"), false);
                for (String field : List.of("toolchainFingerprint", "dependencyFingerprint", "vocabularySha256"))
                    require(text(manifest, field).matches("[0-9a-f]{64}"));
                var build = BrowserLogJson.id(manifest.get("buildId"));
                var artifact = new BrowserLogContexts.Artifact(build, text(manifest, "component"), text(manifest, "gitSha"),
                    text(manifest, "sourceFingerprint"), text(installed, "sha256"), text(manifest, "vocabularySha256"));
                var vocabulary = object(manifest.get("vocabulary"), Set.of("events", "phases", "errorCodes", "dependencies", "exceptionClasses", "frames"));
                require(sha(JSON.writeValueAsBytes(sorted(vocabulary))).equals(artifact.vocabularySha256()));
                var catalog = new BrowserLogJson.Catalog(symbols(vocabulary.get("events")), symbols(vocabulary.get("phases")));
                require(artifacts.putIfAbsent(build, artifact) == null); catalogs.put(build, catalog);
            }
            var trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
            byte[] ca = privateBytes(Path.of(text(config, "caFile")), 16384);
            try (var input = new ByteArrayInputStream(ca)) {
                trust.setCertificateEntry("logging-root", CertificateFactory.getInstance("X.509").generateCertificate(input));
            }
            var manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); manager.init(trust);
            var tls = SSLContext.getInstance("TLS"); tls.init(null, manager.getTrustManagers(), null);
            return new Settings(endpoint, parent, delegator, tls, sha(ca), spool, Map.copyOf(artifacts), Map.copyOf(catalogs));
        } catch (Exception invalid) { throw new IOException("INVALID_BROWSER_LOG_CONFIGURATION"); }
    }
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>(); map.forEach((key, child) -> result.put((String) key, sorted(child))); return result;
        }
        if (value instanceof List<?> rows) return rows.stream().map(BrowserLoggingFactory::sorted).toList();
        return value;
    }
    private static Set<String> symbols(Object value) {
        require(value instanceof List<?>); var values = (List<?>) value; var result = new HashSet<String>();
        for (Object item : values) { require(item instanceof String && result.add((String) item)); }
        return Set.copyOf(result);
    }
    private static Object parse(byte[] raw) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        return JSON.readValue(text, Object.class);
    }
    private static Map<?, ?> object(Object value, Set<String> keys) {
        require(value instanceof Map<?, ?>); var map = (Map<?, ?>) value; require(map.keySet().equals(keys)); return map;
    }
    private static String text(Map<?, ?> row, String key) {
        require(row.get(key) instanceof String text && !text.isBlank() && text.length() <= 4096); return (String) row.get(key);
    }
    private static String sha(byte[] raw) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
    private static byte[] privateBytes(Path path, int maximum) throws IOException {
        require(path.isAbsolute() && path.toRealPath().equals(path) && !Files.isSymbolicLink(path)
            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))
            && ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() == new UnixSystem().getUid());
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1); require(bytes.length > 0 && bytes.length <= maximum); return bytes;
        }
    }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("INVALID_BROWSER_LOG_CONFIGURATION"); }
}
