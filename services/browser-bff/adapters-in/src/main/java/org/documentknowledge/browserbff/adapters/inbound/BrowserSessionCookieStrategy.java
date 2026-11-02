package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.session.Session;
import io.micronaut.session.http.CookieHttpSessionStrategy;
import io.micronaut.session.http.HttpSessionConfiguration;
import jakarta.inject.Singleton;

/** Existing session-only cookies need no renewal; their late responses must not undo rotation. */
@Singleton
@Replaces(CookieHttpSessionStrategy.class)
@Requires(property = "micronaut.session.http.cookie", notEquals = "false")
public final class BrowserSessionCookieStrategy extends CookieHttpSessionStrategy {
    public BrowserSessionCookieStrategy(HttpSessionConfiguration configuration) {
        super(configuration);
        if (configuration.isRememberMe() || configuration.getCookieMaxAge().isPresent()) {
            throw new IllegalArgumentException("BFF requires session-only cookies");
        }
    }

    @Override public void encodeId(HttpRequest<?> request, MutableHttpResponse<?> response, Session session) {
        synchronized (session) {
            if (session.get("dkp.retired", Boolean.class).orElse(false)
                    || resolveIds(request).contains(session.getId())) return;
            // Keep the framework's codec and cookie flags for login and tenant rotation.
            super.encodeId(request, response, session);
        }
    }
}
