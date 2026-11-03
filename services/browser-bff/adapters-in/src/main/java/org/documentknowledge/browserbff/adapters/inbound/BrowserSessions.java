package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.csrf.CsrfConfiguration;
import io.micronaut.security.csrf.generator.CsrfTokenGenerator;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import io.micronaut.session.http.HttpSessionFilter;
import io.micronaut.session.http.SessionForRequest;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.documentknowledge.browserbff.application.BrowserLogContexts;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.SessionPolicy;
import org.documentknowledge.browserbff.application.SessionView;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

@Singleton
public final class BrowserSessions {
    private static final String SELECTED = "dkp.selected.tenant";
    private final SessionStore<Session> store;
    private final CsrfTokenGenerator<HttpRequest<?>> csrf;
    private final CsrfConfiguration csrfConfig;
    private final String origin;
    private final Optional<BrowserLogContexts> logContexts;
    private static final String LOG_KEY = "dkp.logging.session-key";
    private final SessionPolicy policy = new SessionPolicy(Clock.systemUTC());
    public BrowserSessions(SessionStore<Session> store, CsrfTokenGenerator<HttpRequest<?>> csrf,
                           CsrfConfiguration csrfConfig, @Value("${dkp.bff.portal-origin:}") String origin,
                           Optional<BrowserLogContexts> logContexts) {
        this.store = store; this.csrf = csrf; this.csrfConfig = csrfConfig; this.origin = origin;
        this.logContexts = logContexts;
    }
    public Session requireSession(HttpRequest<?> request) {
        Session session = SessionForRequest.find(request).orElseThrow(() -> new BffFailure(401, "AUTHENTICATION_REQUIRED"));
        if (session.get("dkp.retired", Boolean.class).orElse(false)) throw new BffFailure(401, "AUTHENTICATION_REQUIRED");
        policy.requireActive(identity(session));
        return session;
    }
    public BrowserIdentity identity(Session session) {
        return session.get(SecurityFilter.AUTHENTICATION, Authentication.class)
            .map(authentication -> authentication.getAttributes().get(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE))
            .filter(BrowserIdentity.class::isInstance).map(BrowserIdentity.class::cast)
            .orElseThrow(() -> new BffFailure(401, "AUTHENTICATION_REQUIRED"));
    }
    public String selected(Session session) { return session.get(SELECTED, String.class).orElse(null); }
    public SessionView view(Session session) {
        return policy.view(identity(session), selected(session), session.get(csrfConfig.getHttpSessionName(), String.class)
            .orElseThrow(() -> new BffFailure(401, "AUTHENTICATION_REQUIRED")));
    }
    public void requireMutation(HttpRequest<?> request, Session session) {
        String supplied = request.getHeaders().get("X-CSRF-TOKEN");
        String expected = session.get(csrfConfig.getHttpSessionName(), String.class).orElse("");
        if (origin.isBlank() || !origin.equals(request.getHeaders().get("Origin")) || supplied == null || supplied.length() > 512 || expected.isBlank()
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BffFailure(403, "REQUEST_DENIED");
        }
    }
    public SessionView select(HttpRequest<?> request, Session previous, String tenant) {
        synchronized (previous) {
            if (previous.get("dkp.retired", Boolean.class).orElse(false)) throw new BffFailure(401, "AUTHENTICATION_REQUIRED");
            policy.requireTenant(identity(previous), tenant);
            Authentication authentication = previous.get(SecurityFilter.AUTHENTICATION, Authentication.class).orElseThrow();
            // Delete before replacing: a failed store operation cannot grant a new session.
            delete(previous);
            retireLogging(previous);
            previous.clear();
            previous.put("dkp.retired", true);
            Session next = SessionForRequest.create(store, request);
            next.put(SecurityFilter.AUTHENTICATION, authentication);
            next.put(SELECTED, tenant);
            next.setMaxInactiveInterval(Duration.ofSeconds(300));
            next.put(csrfConfig.getHttpSessionName(), csrf.generateCsrfToken(request));
            return view(next);
        }
    }
    public void logout(HttpRequest<?> request, Session session) {
        synchronized (session) {
            delete(session);
            retireLogging(session);
            session.clear();
            session.put("dkp.retired", true);
            request.getAttributes().remove(HttpSessionFilter.SESSION_ATTRIBUTE);
        }
    }
    private void delete(Session session) {
        try { store.deleteSession(session.getId()).get(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BffFailure(503, "SESSION_UNAVAILABLE");
        }
    }
    public BrowserIdentity selectedIdentity(Session session) {
        BrowserIdentity identity = identity(session); policy.requireTenant(identity, selected(session)); return identity;
    }
    /** Logging callers hold this same session monitor through validation and capture, never through I/O. */
    public void requireLoggingOrigin(HttpRequest<?> request) {
        if (origin.isBlank() || request.getHeaders().getAll("Origin").size() != 1
                || !origin.equals(request.getHeaders().get("Origin"))) throw new BffFailure(403, "REQUEST_DENIED");
    }
    public void requireLoggingRequest(HttpRequest<?> request, Session session, String bodyCsrf) {
        if (requireSession(request) != session || session.isExpired()) throw new BffFailure(401, "AUTHENTICATION_REQUIRED");
        requireLoggingOrigin(request);
        var headers = request.getHeaders().getAll("X-CSRF-TOKEN");
        if (headers.size() > 1) throw new BffFailure(403, "REQUEST_DENIED");
        String supplied = bodyCsrf == null ? request.getHeaders().get("X-CSRF-TOKEN") : bodyCsrf;
        String expected = session.get(csrfConfig.getHttpSessionName(), String.class).orElse("");
        if (supplied == null || supplied.isBlank() || supplied.length() > 512 || expected.isBlank()
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new BffFailure(403, "REQUEST_DENIED");
    }
    public BrowserLogContexts.Authority loggingAuthority(Session session) {
        var identity = identity(session); String tenant = selected(session);
        if (tenant == null) policy.requireActive(identity); else policy.requireTenant(identity, tenant);
        // Independent in-memory nonce; the actual session cookie/id is never sent to logging.
        String key = session.get(LOG_KEY, String.class).orElseGet(() -> {
            String next = UUID.randomUUID().toString(); session.put(LOG_KEY, next); return next;
        });
        return new BrowserLogContexts.Authority(key, identity.issuer(), identity.subject(), tenant, identity.expiresAt());
    }
    public Instant loggingSessionExpiry(Session session) {
        return session.getLastAccessedTime().plus(session.getMaxInactiveInterval());
    }
    private void retireLogging(Session session) {
        session.get(LOG_KEY, String.class).ifPresent(key -> logContexts.ifPresent(contexts -> contexts.retire(key)));
    }
}
