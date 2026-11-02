package org.documentknowledge.devlogging.adapters.inbound;

import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.documentknowledge.devlogging.adapters.inbound.requests.DeadlineLogRequests;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.Severity;

/** Authenticated common query route. The separate local viewer must call this same boundary. */
@Controller("/api/v1/dev-logs")
@ExecuteOn(TaskExecutors.BLOCKING)
public final class LogQueryController {
    private static final Set<String> PARAMETERS = Set.of("buildId", "runId", "component", "minSeverity",
        "correlationId", "traceId", "from", "to", "limit", "cursor");
    private final LogReaderAuthentication authentication;
    private final LogQueries queries;
    private final DeadlineLogRequests requests;
    private final JsonMapper json;

    public LogQueryController(LogReaderAuthentication authentication, LogQueries queries,
                              DeadlineLogRequests requests, JsonMapper json) {
        this.authentication = authentication; this.queries = queries; this.requests = requests; this.json = json;
    }

    @Get("/events")
    public HttpResponse<?> events(HttpRequest<?> request) {
        try {
            Object body = requests.submit(deadline -> {
                var headers = request.getHeaders().getAll(HttpHeaders.AUTHORIZATION);
                if (headers.size() != 1 || !headers.getFirst().regionMatches(true, 0, "Bearer ", 0, 7))
                    throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
                var reader = authentication.authenticate(headers.getFirst().substring(7));
                var parameters = parameters(request);
                LogQuery query;
                try {
                    query = new LogQuery(id(parameters.get("buildId")), id(parameters.get("runId")), parameters.get("component"),
                        parameters.containsKey("minSeverity") ? Severity.valueOf(parameters.get("minSeverity")) : null,
                        id(parameters.get("correlationId")), parameters.get("traceId"),
                        Instant.parse(parameters.getOrDefault("from", "")), Instant.parse(parameters.getOrDefault("to", "")),
                        pageSize(parameters.getOrDefault("limit", "50")));
                } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
                    throw new LogFailure(LogFailure.Kind.INVALID);
                }
                var page = queries.query(reader, query, parameters.get("cursor"), deadline);
                return wire(page);
            }).await();
            return response(200, body);
        } catch (LogFailure failure) {
            int status = switch (failure.kind()) {
                case INVALID -> 400; case UNAUTHENTICATED -> 401;
                case FORBIDDEN, EXPIRED -> 403; case RATE_LIMITED -> 429; default -> 503;
            };
            String code = switch (failure.kind()) {
                case INVALID -> "INVALID_LOG_QUERY"; case UNAUTHENTICATED -> "AUTHENTICATION_REQUIRED";
                case FORBIDDEN -> "LOG_SCOPE_DENIED"; case EXPIRED -> "LOG_GRANT_EXPIRED";
                case RATE_LIMITED -> "LOG_RATE_LIMITED"; default -> "LOG_STORE_UNAVAILABLE";
            };
            return response(status, Map.of("code", code));
        }
    }

    private static Map<String, String> parameters(HttpRequest<?> request) {
        if (request.getUri().toASCIIString().length() > 4096) throw new LogFailure(LogFailure.Kind.INVALID);
        var result = new HashMap<String, String>();
        request.getParameters().asMap().forEach((name, values) -> {
            if (!PARAMETERS.contains(name) || values.size() != 1) throw new LogFailure(LogFailure.Kind.INVALID);
            result.put(name, values.getFirst());
        });
        return result;
    }

    private static UUID id(String value) {
        if (value == null) return null;
        if (!value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new LogFailure(LogFailure.Kind.INVALID);
        return UUID.fromString(value);
    }
    private static int pageSize(String value) {
        if (!value.matches("[0-9]{1,3}")) throw new LogFailure(LogFailure.Kind.INVALID);
        return Integer.parseInt(value);
    }

    private Object wire(LogQueries.Page page) {
        var events = new ArrayList<Map<String, Object>>();
        for (var stored : page.events()) {
            var scope = new HashMap<String, Object>(); scope.put("scopeKind", stored.scope().kind().name());
            if (stored.scope().issuer() != null) { scope.put("issuer", stored.scope().issuer()); scope.put("subject", stored.scope().subject()); }
            if (stored.scope().tenantId() != null) scope.put("tenantId", stored.scope().tenantId());
            try {
                events.add(Map.of("event", json.readValue(stored.payload(), Map.class), "producerId", stored.producerId().toString(),
                    "scopeId", stored.scope().id().toString(), "scope", scope, "receivedAt", stored.receivedAt().toString()));
            } catch (IOException invalidStoredPayload) { throw new LogFailure(LogFailure.Kind.UNAVAILABLE); }
        }
        var result = new HashMap<String, Object>(); result.put("events", events);
        if (page.nextCursor() != null) result.put("nextCursor", page.nextCursor());
        return result;
    }
    private static HttpResponse<?> response(int status, Object body) {
        return HttpResponse.status(HttpStatus.valueOf(status)).body(body).contentType(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff");
    }
}
