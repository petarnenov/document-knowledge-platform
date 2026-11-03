package org.documentknowledge.browserbff.adapters.inbound;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.http.HttpRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.Test;

final class BrowserLogJsonTest {
    static final UUID BUILD = UUID.randomUUID(), RUN = UUID.randomUUID(), EVENT = UUID.randomUUID();
    static final BrowserLogContexts.Artifact ARTIFACT = new BrowserLogContexts.Artifact(BUILD, "admin-portal", "a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64));
    static final BrowserLogJson.Catalog CATALOG = new BrowserLogJson.Catalog(Set.of("operation.started"), Set.of("HTTP"));
    static final BrowserLogContexts.Capture CAPTURE = new BrowserLogContexts.Capture(new BrowserLogGateway.Grant(UUID.randomUUID(), ARTIFACT, RUN,
        new BrowserLogContexts.Authority("opaque", "https://issuer.example", "staff", "tenant-a", Instant.now().plusSeconds(300)), Instant.now().plusSeconds(300)), new BrowserLogGateway.Scope() { });
    static Map<String, Object> event(UUID build, UUID run, UUID event) {
        var row = new HashMap<String, Object>();
        row.put("eventId", event.toString()); row.put("buildId", build.toString()); row.put("runId", run.toString()); row.put("instanceId", UUID.randomUUID().toString());
        row.put("component", "admin-portal"); row.put("gitSha", "a".repeat(40)); row.put("sourceFingerprint", "b".repeat(64));
        row.put("timestamp", "2026-11-03T04:00:00Z"); row.put("severity", "DEBUG"); row.put("eventName", "operation.started"); row.put("correlationId", UUID.randomUUID().toString());
        row.put("context", new HashMap<>(Map.of("phase", "HTTP", "outcome", "STARTED", "durationMs", 0, "attempt", 1,
            "attributes", new HashMap<>(Map.of("count", 1, "cleanupSettled", true))))); return row;
    }
    static Map<String, Object> batch(Map<String, Object> event) { return new HashMap<>(Map.of("schemaVersion", 1, "events", List.of(event))); }
    static byte[] json(Object value) { return BrowserLogJson.encode(value); }
    private static void rejected(Runnable action) { assertThrows(BffFailure.class, action::run); }
    @Test void exactPrimitiveBatchTraceCatalogAndReceiptsRetainOriginalIdentity() {
        var event = event(BUILD, RUN, EVENT); event.put("traceId", "1".repeat(32)); event.put("spanId", "2".repeat(16));
        var batch = batch(event); assertEquals(batch, BrowserLogJson.parse(BrowserLogJson.batch(batch, CAPTURE, CATALOG)));
        var catalog = new BrowserLogCatalogs(Map.of(BUILD, CATALOG)); assertSame(CATALOG, catalog.require(BUILD)); rejected(() -> catalog.require(UUID.randomUUID()));
        for (String state : List.of("COMMITTED", "UNKNOWN", "EXPIRED")) {
            var receipt = new HashMap<String, Object>(Map.of("eventId", EVENT.toString(), "state", state));
            if (state.equals("COMMITTED")) receipt.put("receivedAt", "2026-11-03T04:00:01Z");
            byte[] raw = json(Map.of("receipts", List.of(receipt)));
            assertArrayEquals(raw, BrowserLogJson.receipts(raw, List.of(EVENT), false));
            if (state.equals("COMMITTED")) assertArrayEquals(raw, BrowserLogJson.receipts(raw, List.of(EVENT), true));
            else rejected(() -> BrowserLogJson.receipts(raw, List.of(EVENT), true));
        }
    }
    @Test void rejectsEveryAuthorityProvenanceAndRawTextInjection() {
        for (String field : List.of("actor", "tenantId", "message", "stack", "csrfToken", "body")) {
            var event = event(BUILD, RUN, EVENT); event.put(field, "synthetic-secret"); rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
        }
        for (String field : List.of("eventId", "instanceId", "correlationId")) {
            var event = event(BUILD, RUN, EVENT); event.put(field, "not-uuid"); rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
        }
        for (String field : List.of("buildId", "runId", "component", "gitSha", "sourceFingerprint", "eventName", "severity", "timestamp")) {
            var event = event(BUILD, RUN, EVENT); event.put(field, "wrong"); rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
            event.put(field, null); rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
        }
        var duplicate = Map.<String, Object>of("schemaVersion", 1, "events", List.of(event(BUILD, RUN, EVENT), event(BUILD, RUN, EVENT)));
        rejected(() -> BrowserLogJson.batch(duplicate, CAPTURE, CATALOG));
        for (String trace : List.of("0".repeat(32), "X".repeat(32), "short")) {
            var event = event(BUILD, RUN, EVENT); event.put("traceId", trace); rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
        }
        var span = event(BUILD, RUN, EVENT); span.put("spanId", "1".repeat(16)); rejected(() -> BrowserLogJson.batch(batch(span), CAPTURE, CATALOG));
        span.put("traceId", "1".repeat(32)); span.put("spanId", "0".repeat(16)); rejected(() -> BrowserLogJson.batch(batch(span), CAPTURE, CATALOG));
    }
    @Test void rejectsContextUnknownKeysTypesAndNonfiniteNumericClaims() {
        for (String field : List.of("phase", "outcome", "durationMs", "attempt", "attributes")) {
            var event = event(BUILD, RUN, EVENT); var context = BrowserLogJson.object(event.get("context"));
            context.put(field, "bad"); rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
        }
        for (Object value : List.of(-1, 0.5, 9_007_199_254_740_992L, "1")) {
            var event = event(BUILD, RUN, EVENT); BrowserLogJson.object(event.get("context")).put("durationMs", value);
            rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
        }
        var event = event(BUILD, RUN, EVENT); var attributes = BrowserLogJson.object(BrowserLogJson.object(event.get("context")).get("attributes"));
        attributes.put("extra", true); rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG)); attributes.remove("extra");
        attributes.put("cleanupSettled", "true"); rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
        rejected(() -> BrowserLogJson.firstEvent(Map.of("schemaVersion", 2, "events", List.of(event))));
        rejected(() -> BrowserLogJson.firstEvent(Map.of("schemaVersion", 1, "events", List.of())));
        rejected(() -> BrowserLogJson.firstEvent(Map.of("schemaVersion", 1, "events", Collections.nCopies(33, event))));
        rejected(() -> BrowserLogJson.ids(List.of())); rejected(() -> BrowserLogJson.ids("bad"));
        rejected(() -> BrowserLogJson.ids(Collections.nCopies(33, EVENT.toString())));
        rejected(() -> BrowserLogJson.ids(List.of(EVENT.toString(), EVENT.toString())));
        rejected(() -> BrowserLogJson.id(new UUID(0, 0).toString())); rejected(() -> BrowserLogJson.id(null));
        rejected(() -> BrowserLogJson.text(Map.of("x", " "), "x")); rejected(() -> BrowserLogJson.text(Map.of("x", "x".repeat(513)), "x"));
    }
    @Test void strictUtf8DuplicateDepthFramingAndByteBoundsAreIndependentOfFramework() {
        for (String bad : List.of("[]", "{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "{}{}", "[".repeat(20) + "0" + "]".repeat(20)))
            rejected(() -> BrowserLogJson.parse(bad.getBytes(StandardCharsets.UTF_8)));
        rejected(() -> BrowserLogJson.parse(new byte[]{(byte) 0xc3, 0x28})); rejected(() -> BrowserLogJson.parse(null));
        rejected(() -> BrowserLogJson.parse(new byte[0])); rejected(() -> BrowserLogJson.parse(new byte[32769]));
        rejected(() -> BrowserLogJson.encode("字".repeat(11000)));
        var good = HttpRequest.POST("/api/v1/dev-logs/context", "{}").contentType("application/json");
        assertEquals(Map.of(), BrowserLogJson.read(good, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 10));
        rejected(() -> BrowserLogJson.read(good, null, 10));
        rejected(() -> BrowserLogJson.read(good, new ByteArrayInputStream(new byte[11]), 10));
        rejected(() -> BrowserLogJson.read(good, new InputStream() { @Override public int read() throws IOException { throw new IOException("synthetic-secret"); } }, 10));
        for (var bad : List.of(HttpRequest.POST("/", "{}"), HttpRequest.POST("/", "{}").contentType("text/plain"),
                HttpRequest.POST("/", "{}").contentType("application/json").header("Content-Encoding", "gzip"),
                HttpRequest.POST("/", "{}").contentType("application/json").header("Content-Length", "11"),
                HttpRequest.POST("/", "{}").contentType("application/json").header("Content-Length", "2").header("Transfer-Encoding", "chunked")))
            rejected(() -> BrowserLogJson.read(bad, new ByteArrayInputStream(new byte[0]), 10));
    }
    @Test void scopedReceiptsRejectWrongIdsStatesDuplicatesAndExtraFields() {
        for (Object row : List.of(Map.of("eventId", UUID.randomUUID().toString(), "state", "UNKNOWN"),
                Map.of("eventId", EVENT.toString(), "state", "SAVED"), Map.of("eventId", EVENT.toString(), "state", "COMMITTED", "receivedAt", "bad"),
                Map.of("eventId", EVENT.toString(), "state", "UNKNOWN", "tenantId", "injection"), "bad"))
            rejected(() -> BrowserLogJson.receipts(json(Map.of("receipts", List.of(row))), List.of(EVENT), false));
        rejected(() -> BrowserLogJson.receipts(json(Map.of("receipts", List.of())), List.of(EVENT), false));
        var row = Map.of("eventId", EVENT.toString(), "state", "UNKNOWN");
        rejected(() -> BrowserLogJson.receipts(json(Map.of("receipts", List.of(row, row))), List.of(EVENT, UUID.randomUUID()), false));
        rejected(() -> new BrowserLogJson.Catalog(Set.of(), Set.of("HTTP")));
        rejected(() -> new BrowserLogJson.Catalog(Set.of("wrong raw event"), Set.of("HTTP")));
        rejected(() -> new BrowserLogJson.Catalog(Set.of("operation.started"), Set.of("bad phase")));
    }
    @Test void duplicateTransferEncodingIsRejectedBeforeBodyParse() {
        var request = HttpRequest.POST("/api/v1/dev-logs/beacon", "{}").contentType("application/json")
            .header("Transfer-Encoding", "chunked").header("Transfer-Encoding", "chunked");
        assertEquals(2, request.getHeaders().getAll("Transfer-Encoding").size());
        assertThrows(BffFailure.class, () -> BrowserLogJson.read(request,
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 32768));
    }
    @Test void eventTimestampRejectsInstantNormalizationOutsideSelectedRfc3339Subset() {
        for (String stamp : List.of("2026-11-03T24:00:00Z", "+10000-11-03T04:00:00Z", "2026-02-30T04:00:00Z")) {
            var event = event(BUILD, RUN, EVENT); event.put("timestamp", stamp);
            rejected(() -> BrowserLogJson.batch(batch(event), CAPTURE, CATALOG));
        }
    }
    @Test void receiptTimestampRejectsInstantNormalizationOutsideSelectedRfc3339Subset() {
        for (String stamp : List.of("2026-11-03T24:00:00Z", "+10000-11-03T04:00:00Z", "2026-02-30T04:00:00Z")) {
            var raw = json(Map.of("receipts", List.of(Map.of("eventId", EVENT.toString(), "state", "COMMITTED", "receivedAt", stamp))));
            rejected(() -> BrowserLogJson.receipts(raw, List.of(EVENT), true));
        }
        var raw = json(Map.of("receipts", List.of(Map.of("eventId", EVENT.toString(), "state", "COMMITTED", "receivedAt", "2026-11-03T06:00:00+02:00"))));
        assertArrayEquals(raw, BrowserLogJson.receipts(raw, List.of(EVENT), true));
    }

}
