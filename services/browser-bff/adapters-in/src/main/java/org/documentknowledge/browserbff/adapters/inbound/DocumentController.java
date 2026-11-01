package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.HashMap;
import java.io.InputStream;
import io.micronaut.core.annotation.Nullable;
import java.util.Map;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.DocumentGateway;
import org.documentknowledge.browserbff.application.DocumentWire;

@Controller(value = "/api/v1/dm/documents", produces = MediaType.APPLICATION_JSON)
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class DocumentController {
    private final BrowserSessions sessions;
    private final DocumentGateway documents;
    public DocumentController(BrowserSessions sessions, DocumentGateway documents) { this.sessions = sessions; this.documents = documents; }
    @Get public HttpResponse<?> list(HttpRequest<?> request) { return exchange(request, null); }
    @Post(consumes = MediaType.APPLICATION_JSON) public HttpResponse<?> create(HttpRequest<?> request, @Nullable @Body InputStream body) { return exchange(request, body); }
    @Get("/{documentId}") public HttpResponse<?> detail(HttpRequest<?> request, String documentId) { return exchange(request, null); }
    @Patch(value = "/{documentId}", consumes = MediaType.APPLICATION_JSON) public HttpResponse<?> update(HttpRequest<?> request, String documentId, @Nullable @Body InputStream body) { return exchange(request, body); }
    @Get("/{documentId}/acl") public HttpResponse<?> acl(HttpRequest<?> request, String documentId) { return exchange(request, null); }
    @Patch(value = "/{documentId}/acl", consumes = MediaType.APPLICATION_JSON) public HttpResponse<?> changeAcl(HttpRequest<?> request, String documentId, @Nullable @Body InputStream body) { return exchange(request, body); }
    @Get("/{documentId}/legal-holds") public HttpResponse<?> holds(HttpRequest<?> request, String documentId) { return exchange(request, null); }
    @Post(value = "/{documentId}/legal-holds", consumes = MediaType.APPLICATION_JSON) public HttpResponse<?> hold(HttpRequest<?> request, String documentId, @Nullable @Body InputStream body) { return exchange(request, body); }
    @Post(value = "/{documentId}/versions", consumes = MediaType.APPLICATION_JSON) public HttpResponse<?> version(HttpRequest<?> request, String documentId, @Nullable @Body InputStream body) { return exchange(request, body); }
    private HttpResponse<?> exchange(HttpRequest<?> request, InputStream source) {
        try {
            var session = sessions.requireSession(request);
            if (request.getMethod() != io.micronaut.http.HttpMethod.GET) sessions.requireMutation(request, session);
            var identity = sessions.selectedIdentity(session);
            Map<String, Object> body = request.getMethod() == io.micronaut.http.HttpMethod.GET ? null : BoundedMetadataBody.decode(request, source);
            String raw = request.getUri().getRawPath();
            if (raw.contains("%") || raw.contains("//") || !raw.startsWith("/api/v1/dm/documents")) throw new BffFailure(404, "NOT_FOUND");
            String path = raw.replaceFirst("/api/v1/dm/", "/api/v1/");
            var query = new HashMap<String, String>();
            request.getParameters().asMap().forEach((name, values) -> {
                if (values.size() != 1) throw new BffFailure(400, "INVALID_REQUEST");
                query.put(name, values.getFirst());
            });
            if (request.getHeaders().getAll("Idempotency-Key").size() > 1 || request.getHeaders().getAll("Origin").size() > 1 || request.getHeaders().getAll("X-CSRF-TOKEN").size() > 1) throw new BffFailure(400, "INVALID_REQUEST");
            String key = request.getHeaders().get("Idempotency-Key");
            DocumentWire.request(request.getMethodName(), path, query, body, key);
            var result = documents.exchange(request.getMethodName(), path, query, body, key, identity, sessions.selected(session));
            // Recheck session expiry and selection before returning independently authorized DM results.
            sessions.selectedIdentity(session);
            return HttpResponse.status(HttpStatus.valueOf(result.status())).body(result.body()).header("Cache-Control", "no-store");
        } catch (BffFailure failure) { return HttpResponse.status(HttpStatus.valueOf(failure.status())).body(Map.of("code", failure.code())).header("Cache-Control", "no-store"); }
        finally { BoundedMetadataBody.close(source); }
    }
}
