package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.BrowserLogContexts;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Closed primitive-only wire policy. Secrets and arbitrary console/error/content text are never forwarded. */
public final class BrowserLogJson {
    public record Catalog(Set<String> events, Set<String> phases) {
        public Catalog {
            events = Set.copyOf(events); phases = Set.copyOf(phases);
            if (events.isEmpty() || events.size() > 1024 || phases.isEmpty() || phases.size() > 256
                    || events.stream().anyMatch(v -> !v.matches("[a-z][a-z0-9_.-]{0,127}"))
                    || phases.stream().anyMatch(v -> !v.matches("[A-Za-z0-9_.-]{1,96}"))) invalid();
        }
    }
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(32768).maxNestingDepth(16)
            .maxStringLength(512).maxNumberLength(16).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> EVENTS = Set.of("eventId", "buildId", "runId", "instanceId", "component", "gitSha",
        "sourceFingerprint", "timestamp", "severity", "eventName", "correlationId", "context");
    private BrowserLogJson() { }
    public static Map<String, Object> read(HttpRequest<?> request, InputStream input, int maximum) {
        if (request.getHeaders().getAll("Content-Length").size() > 1 || request.getHeaders().getAll("Transfer-Encoding").size() > 1
                || (!request.getHeaders().getAll("Content-Length").isEmpty() && !request.getHeaders().getAll("Transfer-Encoding").isEmpty())
                || !request.getHeaders().getAll("Content-Encoding").isEmpty()
                || request.getHeaders().getAll("Content-Type").size() != 1
                || !request.getContentType().map(t -> t.getName().equals(MediaType.APPLICATION_JSON)).orElse(false)) invalid();
        if (request.getHeaders().contentLength().orElse(-1) > maximum) throw new BffFailure(413, "LOG_BATCH_TOO_LARGE");
        if (input == null) invalid();
        try {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new BffFailure(413, "LOG_BATCH_TOO_LARGE");
            return parse(bytes);
        } catch (BffFailure failure) { throw failure; }
        catch (Exception failure) { throw new BffFailure(400, "INVALID_LOG_BATCH"); }
    }
    @SuppressWarnings("unchecked") public static Map<String, Object> parse(byte[] raw) {
        try {
            if (raw == null || raw.length == 0 || raw.length > 32768) invalid();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            Object parsed = JSON.readValue(text, Object.class);
            if (!(parsed instanceof Map<?, ?>)) invalid();
            return (Map<String, Object>) parsed;
        } catch (Exception invalid) { throw new BffFailure(400, "INVALID_LOG_BATCH"); }
    }
    public static void keys(Map<?, ?> value, Set<String> expected) { if (!value.keySet().equals(expected)) invalid(); }
    @SuppressWarnings("unchecked") public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) invalid(); return (Map<String, Object>) value;
    }
    public static String text(Map<?, ?> value, String key) {
        if (!(value.get(key) instanceof String text) || text.isBlank() || text.length() > 512) { invalid(); return null; }
        return (String) value.get(key);
    }
    public static UUID id(Object value) {
        try {
            if (!(value instanceof String text) || !text.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) invalid();
            UUID parsed = UUID.fromString((String) value); if (parsed.equals(new UUID(0, 0))) invalid(); return parsed;
        } catch (Exception invalid) { throw new BffFailure(400, "INVALID_LOG_BATCH"); }
    }
    public static List<UUID> ids(Object value) {
        if (!(value instanceof List<?> rows) || rows.isEmpty() || rows.size() > 32) { invalid(); return null; }
        var result = new ArrayList<UUID>(); var unique = new HashSet<UUID>();
        for (Object row : (List<?>) value) { UUID id = id(row); if (!unique.add(id)) invalid(); result.add(id); }
        return List.copyOf(result);
    }
    public static Map<String, Object> firstEvent(Map<String, Object> batch) {
        keys(batch, Set.of("schemaVersion", "events"));
        if (!Integer.valueOf(1).equals(batch.get("schemaVersion")) || !(batch.get("events") instanceof List<?> rows)
                || rows.isEmpty() || rows.size() > 32) invalid();
        return object(((List<?>) batch.get("events")).getFirst());
    }
    public static byte[] batch(Map<String, Object> batch, BrowserLogContexts.Capture capture, Catalog catalog) {
        firstEvent(batch); var seen = new HashSet<UUID>();
        var artifact = capture.grant().artifact(); String run = capture.grant().runId().toString();
        for (Object value : (List<?>) batch.get("events")) {
            var event = object(value); var allowed = new HashSet<>(EVENTS);
            if (event.containsKey("traceId")) allowed.add("traceId");
            if (event.containsKey("spanId")) allowed.add("spanId"); keys(event, allowed);
            if (!seen.add(id(event.get("eventId"))) || !artifact.buildId().toString().equals(event.get("buildId"))
                    || !run.equals(event.get("runId")) || !artifact.component().equals(event.get("component"))
                    || !artifact.gitSha().equals(event.get("gitSha")) || !artifact.sourceFingerprint().equals(event.get("sourceFingerprint"))) invalid();
            id(event.get("instanceId")); id(event.get("correlationId"));
            timestamp(text(event, "timestamp"), true);
            if (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL").contains(text(event, "severity"))
                    || !catalog.events().contains(text(event, "eventName"))) invalid();
            if (event.containsKey("traceId") && !trace(event.get("traceId"), 32)) invalid();
            if (event.containsKey("spanId") && (!event.containsKey("traceId") || !trace(event.get("spanId"), 16))) invalid();
            var context = object(event.get("context")); keys(context, Set.of("phase", "outcome", "durationMs", "attempt", "attributes"));
            if (!catalog.phases().contains(text(context, "phase"))
                    || !Set.of("STARTED", "SUCCEEDED", "FAILED", "CANCELLED", "RETRYING", "REJECTED", "UNKNOWN").contains(text(context, "outcome"))) invalid();
            number(context.get("durationMs")); number(context.get("attempt"));
            var attributes = object(context.get("attributes")); keys(attributes, Set.of("count", "cleanupSettled"));
            number(attributes.get("count")); if (!(attributes.get("cleanupSettled") instanceof Boolean)) invalid();
            if (encode(event).length > 8192) throw new BffFailure(413, "LOG_BATCH_TOO_LARGE");
        }
        return encode(batch);
    }
    public static byte[] receipts(byte[] raw, List<UUID> requested, boolean committedOnly) {
        var response = parse(raw); keys(response, Set.of("receipts"));
        if (!(response.get("receipts") instanceof List<?> rows) || rows.size() != requested.size()) invalid();
        var seen = new HashSet<UUID>();
        for (Object value : (List<?>) response.get("receipts")) {
            var row = object(value); String state = text(row, "state");
            boolean committed = "COMMITTED".equals(state);
            keys(row, committed ? Set.of("eventId", "state", "receivedAt") : Set.of("eventId", "state"));
            UUID id = id(row.get("eventId"));
            if (!requested.contains(id) || !seen.add(id) || (committedOnly && !committed)
                    || !Set.of("COMMITTED", "UNKNOWN", "EXPIRED").contains(state)) invalid();
            if (committed) timestamp(text(row, "receivedAt"), false);
        }
        return encode(response);
    }
    public static byte[] encode(Object value) {
        byte[] bytes = JSON.writeValueAsBytes(value);
        if (bytes.length > 32768) throw new BffFailure(413, "LOG_BATCH_TOO_LARGE"); return bytes;
    }
    public static void timestamp(String value, boolean utcOnly) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:(?:[0-5][0-9]|60)"
                    + "(?:\\.[0-9]+)?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])") || (utcOnly && !value.endsWith("Z"))) invalid();
            Instant.parse(value);
        } catch (Exception invalid) { invalid(); }
    }
    private static boolean trace(Object value, int length) {
        return value instanceof String text && text.matches("[0-9a-f]{" + length + "}") && !text.equals("0".repeat(length));
    }
    private static void number(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0
                || ((Number) value).longValue() > 9_007_199_254_740_991L) invalid();
    }
    private static void invalid() { throw new BffFailure(400, "INVALID_LOG_BATCH"); }
}
