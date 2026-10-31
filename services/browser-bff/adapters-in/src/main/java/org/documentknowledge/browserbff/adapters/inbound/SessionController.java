package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.ContextGateway;

@Controller(value = "/api/v1", produces = MediaType.APPLICATION_JSON)
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class SessionController {
    private final BrowserSessions sessions;
    private final ContextGateway contexts;
    public SessionController(BrowserSessions sessions, ContextGateway contexts) { this.sessions = sessions; this.contexts = contexts; }
    @Get("/session") public HttpResponse<?> session(HttpRequest<?> request) {
        return safely(() -> HttpResponse.ok(sessions.view(sessions.requireSession(request))));
    }
    @Post(value = "/session/tenant", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> select(HttpRequest<?> request, @Body Map<String, Object> body) {
        return safely(() -> {
            var session = sessions.requireSession(request); sessions.requireMutation(request, session);
            if (body.size() != 1 || !(body.get("tenantId") instanceof String tenant) || tenant.isBlank() || tenant.length() > 256) throw new BffFailure(400, "INVALID_REQUEST");
            return HttpResponse.ok(sessions.select(request, session, (String) body.get("tenantId")));
        });
    }
    @Post(value = "/session/logout", consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<?> logout(HttpRequest<?> request, @Body Map<String, Object> body) {
        return safely(() -> {
            var session = sessions.requireSession(request); sessions.requireMutation(request, session);
            if (!body.isEmpty()) throw new BffFailure(400, "INVALID_REQUEST");
            sessions.logout(request, session);
            return HttpResponse.noContent().cookie(Cookie.of("__Host-DKP-SESSION", "").httpOnly(true).secure(true).sameSite(SameSite.Lax).path("/").maxAge(0));
        });
    }
    @Get("/security/context/{service}") public HttpResponse<?> context(HttpRequest<?> request, String service) {
        return safely(() -> {
            if (!Set.of("dm", "kb", "search").contains(service)) throw new BffFailure(404, "NOT_FOUND");
            var session = sessions.requireSession(request);
            return HttpResponse.ok(contexts.context(service, sessions.selectedIdentity(session), sessions.selected(session)));
        });
    }
    private static HttpResponse<?> safely(Supplier<MutableHttpResponse<?>> action) {
        try { return action.get().header("Cache-Control", "no-store"); }
        catch (BffFailure failure) { return HttpResponse.status(HttpStatus.valueOf(failure.status())).body(Map.of("code", failure.code())).header("Cache-Control", "no-store"); }
    }
}
