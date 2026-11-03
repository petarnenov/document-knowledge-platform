package org.documentknowledge.browserbff.adapters.outbound;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.browserbff.application.BrowserLogGateway;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Recovery checks for private sanitized bytes and exact upstream commit acknowledgements. */
final class BrowserLogPendingJson {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(32768).maxNestingDepth(16)
            .maxStringLength(512).maxNumberLength(16).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> EVENT = Set.of("eventId", "buildId", "runId", "instanceId", "component", "gitSha",
        "sourceFingerprint", "timestamp", "severity", "eventName", "correlationId", "context");
    private BrowserLogPendingJson() { }
    static List<UUID> events(byte[] raw, BrowserLogGateway.Grant grant) {
        var batch = object(parse(raw)); keys(batch, Set.of("schemaVersion", "events"));
        require(Integer.valueOf(1).equals(batch.get("schemaVersion")));
        var rows = rows(batch.get("events")); var ids = new ArrayList<UUID>(); var unique = new HashSet<UUID>();
        var artifact = grant.artifact();
        for (var value : rows) {
            var event = object(value); var expected = new HashSet<>(EVENT);
            if (event.containsKey("traceId")) expected.add("traceId");
            if (event.containsKey("spanId")) expected.add("spanId"); keys(event, expected);
            UUID id = id(event.get("eventId")); require(unique.add(id)); ids.add(id);
            require(artifact.buildId().toString().equals(event.get("buildId")) && grant.runId().toString().equals(event.get("runId"))
                && artifact.component().equals(event.get("component")) && artifact.gitSha().equals(event.get("gitSha"))
                && artifact.sourceFingerprint().equals(event.get("sourceFingerprint")));
            id(event.get("instanceId")); id(event.get("correlationId"));
            String stamp = text(event.get("timestamp")); require(stamp.endsWith("Z")); timestamp(stamp);
            require(Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL").contains(text(event.get("severity")))
                && text(event.get("eventName")).matches("[a-z][a-z0-9_.-]{0,127}"));
            if (event.containsKey("traceId")) trace(event.get("traceId"), 32);
            if (event.containsKey("spanId")) { require(event.containsKey("traceId")); trace(event.get("spanId"), 16); }
            var context = object(event.get("context")); keys(context, Set.of("phase", "outcome", "durationMs", "attempt", "attributes"));
            require(text(context.get("phase")).matches("[A-Za-z0-9_.-]{1,96}")
                && Set.of("STARTED", "SUCCEEDED", "FAILED", "CANCELLED", "RETRYING", "REJECTED", "UNKNOWN").contains(text(context.get("outcome"))));
            number(context.get("durationMs")); number(context.get("attempt"));
            var attributes = object(context.get("attributes")); keys(attributes, Set.of("count", "cleanupSettled"));
            number(attributes.get("count")); require(attributes.get("cleanupSettled") instanceof Boolean);
            require(JSON.writeValueAsBytes(event).length <= 8192);
        }
        return List.copyOf(ids);
    }
    static void committed(byte[] raw, List<UUID> expected) {
        var response = object(parse(raw)); keys(response, Set.of("receipts")); var rows = rows(response.get("receipts"));
        require(rows.size() == expected.size()); var seen = new HashSet<UUID>();
        for (Object value : rows) {
            var row = object(value); keys(row, Set.of("eventId", "state", "receivedAt")); UUID id = id(row.get("eventId"));
            require(expected.contains(id) && seen.add(id) && "COMMITTED".equals(row.get("state")));
            timestamp(text(row.get("receivedAt")));
        }
    }
    private static Object parse(byte[] bytes) {
        try {
            require(bytes != null && bytes.length > 0 && bytes.length <= 32768);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return JSON.readValue(text, Object.class);
        } catch (Exception invalid) { throw failure(); }
    }
    private static Map<?, ?> object(Object value) { require(value instanceof Map<?, ?>); return (Map<?, ?>) value; }
    private static List<?> rows(Object value) {
        require(value instanceof List<?> rows && !rows.isEmpty() && rows.size() <= 32); return (List<?>) value;
    }
    private static void keys(Map<?, ?> row, Set<String> keys) { require(row.keySet().equals(keys)); }
    private static String text(Object value) { require(value instanceof String text && !text.isBlank() && text.length() <= 512); return (String) value; }
    private static UUID id(Object value) {
        String text = text(value); require(text.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
        UUID id = UUID.fromString(text); require(!id.equals(new UUID(0, 0))); return id;
    }
    private static void trace(Object value, int size) {
        String text = text(value); require(text.matches("[0-9a-f]{" + size + "}") && !text.equals("0".repeat(size)));
    }
    private static void timestamp(String text) {
        try { require(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:(?:[0-5][0-9]|60)"
            + "(?:\\.[0-9]+)?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])")); Instant.parse(text); }
        catch (Exception invalid) { throw failure(); }
    }
    private static void number(Object value) {
        require((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0
            && ((Number) value).longValue() <= 9_007_199_254_740_991L);
    }
    private static void require(boolean value) { if (!value) throw failure(); }
    private static IllegalArgumentException failure() { return new IllegalArgumentException("INVALID_BROWSER_LOG_PENDING"); }
}
