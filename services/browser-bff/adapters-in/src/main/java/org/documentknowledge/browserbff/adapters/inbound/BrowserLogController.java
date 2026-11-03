package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.documentknowledge.browserbff.application.*;

@Controller(value = "/api/v1/dev-logs", produces = MediaType.APPLICATION_JSON)
@Requires(beans = {BrowserLogGateway.class, BrowserLogContexts.class, BrowserLogCatalogs.class, BrowserLogRequests.class})
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(BrowserLogResponseExecutor.NAME)
public final class BrowserLogController {
    private final BrowserSessions sessions;
    private final BrowserLogContexts contexts;
    private final BrowserLogCatalogs catalogs;
    private final BrowserLogGateway gateway;
    private final BrowserLogRequests requests;
    public BrowserLogController(BrowserSessions sessions, BrowserLogContexts contexts, BrowserLogCatalogs catalogs,
                                BrowserLogGateway gateway, BrowserLogRequests requests) {
        this.sessions = sessions; this.contexts = contexts; this.catalogs = catalogs; this.gateway = gateway; this.requests = requests;
    }
    @Post(value = "/context", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> context(HttpRequest<?> request, @Nullable @Body InputStream input) {
        return execute(request, input, deadline -> {
            var session = sessions.requireSession(request);
            synchronized (session) { sessions.requireLoggingRequest(request, session, null); }
            var body = BrowserLogJson.read(request, input, 1024); BrowserLogJson.keys(body, Set.of("buildId", "runId"));
            var build = BrowserLogJson.id(body.get("buildId")); var run = BrowserLogJson.id(body.get("runId"));
            BrowserLogContexts.Reservation reservation;
            synchronized (session) {
                sessions.requireLoggingRequest(request, session, null);
                reservation = contexts.reserve(sessions.loggingAuthority(session), sessions.loggingSessionExpiry(session), build, run);
                if (!reservation.owner()) return response(200, contextBody(contexts.ready(reservation)));
            }
            try {
                var scope = gateway.delegate(reservation.grant(), deadline);
                synchronized (session) {
                    sessions.requireLoggingRequest(request, session, null);
                    if (!reservation.grant().authority().equals(sessions.loggingAuthority(session))) throw new BffFailure(403, "LOG_CONTEXT_RETIRED");
                    deadline.requireTime();
                    return response(200, contextBody(contexts.activate(reservation, scope)));
                }
            } finally { contexts.failed(reservation); }
        });
    }
    @Post(value = "/beacon", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> beacon(HttpRequest<?> request, @Nullable @Body InputStream input) {
        return execute(request, input, deadline -> {
            var session = sessions.requireSession(request);
            synchronized (session) { sessions.requireLoggingOrigin(request); }
            var body = BrowserLogJson.read(request, input, 32768);
            BrowserLogJson.keys(body, Set.of("csrfToken", "contextEpoch", "captureClass", "batch")); current(body);
            var batch = BrowserLogJson.object(body.get("batch")); var first = BrowserLogJson.firstEvent(batch);
            BrowserLogContexts.Capture capture;
            synchronized (session) {
                sessions.requireLoggingRequest(request, session, BrowserLogJson.text(body, "csrfToken"));
                capture = contexts.capture(sessions.loggingAuthority(session), BrowserLogJson.id(body.get("contextEpoch")),
                    BrowserLogJson.id(first.get("buildId")), BrowserLogJson.id(first.get("runId")));
            }
            byte[] sanitized = BrowserLogJson.batch(batch, capture, catalogs.require(capture.grant().artifact().buildId()));
            var ids = ((List<?>) batch.get("events")).stream().map(v -> BrowserLogJson.id(BrowserLogJson.object(v).get("eventId"))).toList();
            byte[] receipt = gateway.ingest(capture.scope(), sanitized, deadline);
            validReceipt(receipt, ids, true);
            return response(204, null);
        });
    }
    @Post(value = "/beacon/receipts", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> receipts(HttpRequest<?> request, @Nullable @Body InputStream input) {
        return execute(request, input, deadline -> {
            var session = sessions.requireSession(request);
            synchronized (session) { sessions.requireLoggingOrigin(request); }
            var body = BrowserLogJson.read(request, input, 32768);
            BrowserLogJson.keys(body, Set.of("csrfToken", "contextEpoch", "buildId", "runId", "captureClass", "eventIds")); current(body);
            var ids = BrowserLogJson.ids(body.get("eventIds")); BrowserLogContexts.Capture capture;
            synchronized (session) {
                sessions.requireLoggingRequest(request, session, BrowserLogJson.text(body, "csrfToken"));
                capture = contexts.capture(sessions.loggingAuthority(session), BrowserLogJson.id(body.get("contextEpoch")),
                    BrowserLogJson.id(body.get("buildId")), BrowserLogJson.id(body.get("runId")));
            }
            byte[] raw = gateway.receipts(capture.scope(), BrowserLogJson.encode(Map.of("eventIds", ids.stream().map(UUID::toString).toList())), deadline);
            return response(200, validReceipt(raw, ids, false));
        });
    }
    private HttpResponse<?> execute(HttpRequest<?> request, InputStream input, Function<BrowserLogDeadline, HttpResponse<?>> action) {
        var started = new AtomicBoolean();
        try {
            var deadline = BrowserLogRouteDeadline.admitted(request);
            return requests.submit(deadline, budget -> {
                started.set(true);
                try {
                    if (request.getUri().toASCIIString().length() > 4096 || !request.getParameters().asMap().isEmpty()) throw new BffFailure(400, "INVALID_LOG_BATCH");
                    return action.apply(budget);
                } finally { BoundedMetadataBody.close(input); }
            }).await();
        } catch (BffFailure failure) { return response(failure.status(), Map.of("code", failure.code())); }
        finally { if (!started.get()) BoundedMetadataBody.close(input); }
    }
    private static Map<String, Object> contextBody(BrowserLogContexts.Context context) {
        return Map.of("contextEpoch", context.contextEpoch().toString(), "buildId", context.buildId().toString(),
            "runId", context.runId().toString(), "expiresAt", context.expiresAt().toString());
    }
    private static byte[] validReceipt(byte[] raw, List<UUID> ids, boolean committed) {
        try { return BrowserLogJson.receipts(raw, ids, committed); }
        catch (RuntimeException invalid) { throw new BffFailure(503, "LOG_STORE_UNAVAILABLE"); }
    }
    private static void current(Map<?, ?> body) {
        if (!"CURRENT_CONTEXT".equals(body.get("captureClass"))) throw new BffFailure(400, "INVALID_LOG_BATCH");
    }
    private static HttpResponse<?> response(int status, Object body) {
        return HttpResponse.status(HttpStatus.valueOf(status)).body(body).contentType(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff");
    }
}
