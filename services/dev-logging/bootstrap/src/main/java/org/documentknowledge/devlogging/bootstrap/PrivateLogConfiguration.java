package org.documentknowledge.devlogging.bootstrap;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.devlogging.adapters.outbound.persistence.ConfiguredLogReaders;
import org.documentknowledge.devlogging.domain.LogReader;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Trusted operator files only; never reused as an HTTP authority or an ingestion parser. */
final class PrivateLogConfiguration {
    private final String jdbcUrl;
    private final String password;
    private final byte[] cursorKey;
    private final List<ConfiguredLogReaders.Grant> grants;

    PrivateLogConfiguration(String url, String passwordFile, String grantsFile, String keyFile, Clock clock) {
        try {
            if (url == null || !url.matches("jdbc:postgresql://[A-Za-z0-9.-]+:[0-9]{1,5}/dkp_dev_logs")) fail();
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            if (port < 1 || port > 65535) fail();
            jdbcUrl = url;
            byte[] secret = read(passwordFile, 512);
            password = new String(secret, StandardCharsets.US_ASCII);
            if (!password.matches("[A-Za-z0-9_-]{32,512}")) fail();
            String key = new String(read(keyFile, 65), StandardCharsets.US_ASCII);
            if (!key.matches("[0-9a-f]{64}\n?")) fail();
            cursorKey = HexFormat.of().parseHex(key.stripTrailing());
            grants = parse(read(grantsFile, 1024 * 1024), clock);
        } catch (Exception invalid) { throw failure(); }
    }

    String jdbcUrl() { return jdbcUrl; }
    String password() { return password; }
    byte[] cursorKey() { return cursorKey.clone(); }
    List<ConfiguredLogReaders.Grant> grants() { return grants; }

    static byte[] read(String name, int maximum) throws Exception {
        Path file = Path.of(Objects.requireNonNull(name));
        var before = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var forbidden = EnumSet.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
        if (!before.isRegularFile() || before.size() < 1 || before.size() > maximum
                || !before.permissions().contains(PosixFilePermission.OWNER_READ)
                || !Collections.disjoint(before.permissions(), forbidden)) fail();
        byte[] bytes;
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream input = Channels.newInputStream(channel)) {
            bytes = input.readNBytes(maximum + 1);
        }
        var after = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (bytes.length < 1 || bytes.length > maximum || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !before.permissions().equals(after.permissions())) fail();
        return bytes;
    }

    private static List<ConfiguredLogReaders.Grant> parse(byte[] bytes, Clock clock) {
        var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                .maxDocumentLength(1024 * 1024).maxStringLength(512).maxNumberLength(10).build()).build();
        var mapper = JsonMapper.builder(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        var root = mapper.readValue(bytes, Map.class);
        if (!root.keySet().equals(Set.of("schemaVersion", "readers"))
                || !(root.get("schemaVersion") instanceof Integer version) || version != 1
                || !(root.get("readers") instanceof List<?>)) fail();
        var rows = (List<?>) root.get("readers");
        if (rows.isEmpty() || rows.size() > 4096) fail();
        var result = new ArrayList<ConfiguredLogReaders.Grant>();
        for (var row : rows) {
            if (!(row instanceof Map<?, ?>)) fail();
            var item = (Map<?, ?>) row;
            var keys = item.keySet();
            if (!keys.containsAll(Set.of("id", "operator", "expiresAt", "tokenSha256"))
                    || !Set.of("id", "operator", "expiresAt", "tokenSha256", "issuer", "tenantId").containsAll(keys)
                    || !(item.get("operator") instanceof Boolean)) fail();
            String id = text(item, "id"), expiry = text(item, "expiresAt"), hash = text(item, "tokenSha256");
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || !expiry.endsWith("Z") || !hash.matches("[0-9a-f]{64}")) fail();
            boolean operator = (Boolean) item.get("operator");
            if (operator && (keys.contains("issuer") || keys.contains("tenantId"))) fail();
            var reader = new LogReader(UUID.fromString(id), operator, operator ? null : text(item, "issuer"),
                operator ? null : text(item, "tenantId"), Instant.parse(expiry));
            // Keep expired entries and their hashes for role/duplicate checks.
            // ConfiguredLogReaders rejects them on every authentication attempt.
            result.add(new ConfiguredLogReaders.Grant(reader, HexFormat.of().parseHex(hash)));
        }
        // Registry constructor performs the bounded duplicate-ID/hash checks as well as immutable copying.
        new ConfiguredLogReaders(result, clock);
        return List.copyOf(result);
    }
    private static String text(Map<?, ?> item, String key) {
        if (!(item.get(key) instanceof String)) fail();
        return (String) item.get(key);
    }
    private static void fail() { throw failure(); }
    private static IllegalStateException failure() { return new IllegalStateException("LOG_CONFIGURATION_UNAVAILABLE"); }
}
