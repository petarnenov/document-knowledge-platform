package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import io.micronaut.session.http.CookieHttpSessionStrategy;
import io.micronaut.session.http.HttpSessionConfiguration;
import org.documentknowledge.browserbff.adapters.inbound.BrowserSessionCookieStrategy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionCookieStrategyTest {
    private static final String COOKIE = "__Host-DKP-SESSION";

    @Test void actualReplacementPreservesFrameworkCodecForNewIdentifiersAndSuppressesRetiredOnes() {
        try (var context = ApplicationContext.run()) {
            var configuration = context.getBean(HttpSessionConfiguration.class);
            assertFalse(configuration.isRememberMe());
            assertTrue(configuration.getCookieMaxAge().isEmpty());
            var strategy = context.getBean(CookieHttpSessionStrategy.class);
            assertInstanceOf(BrowserSessionCookieStrategy.class, strategy);
            assertEquals(1, context.getBeansOfType(CookieHttpSessionStrategy.class).size());
            Session session = context.getBean(SessionStore.class).newSession();
            var request = HttpRequest.GET("/api/v1/session");
            var response = HttpResponse.ok();
            strategy.encodeId(request, response, session);
            var cookie = response.getCookie(COOKIE).orElseThrow();
            assertTrue(session.getId().equals(cookie.getValue()), "Original opaque codec is retained");
            assertTrue(cookie.isSecure());
            assertTrue(cookie.isHttpOnly());
            assertEquals("/", cookie.getPath());
            assertNull(cookie.getDomain());
            assertEquals(io.micronaut.http.cookie.SameSite.Lax, cookie.getSameSite().orElseThrow());

            var matching = HttpRequest.GET("/api/v1/session").cookie(Cookie.of(COOKIE, session.getId()));
            var unchanged = HttpResponse.ok();
            strategy.encodeId(matching, unchanged, session);
            assertTrue(unchanged.getCookies().getAll().isEmpty());

            var different = HttpRequest.GET("/api/v1/session").cookie(Cookie.of(COOKIE, "synthetic-prior-id"));
            var rotated = HttpResponse.ok();
            strategy.encodeId(different, rotated, session);
            assertTrue(session.getId().equals(rotated.getCookie(COOKIE).orElseThrow().getValue()));

            session.put("dkp.retired", true);
            var retired = HttpResponse.ok();
            strategy.encodeId(request, retired, session);
            assertTrue(retired.getCookies().getAll().isEmpty(), "Retired authority is never newly issued");
        }
    }

    @Test void persistentCookieConfigurationCannotSilentlyInheritSessionOnlySemantics() {
        var remember = new HttpSessionConfiguration();
        remember.setRememberMe(true);
        assertThrows(IllegalArgumentException.class, () -> new BrowserSessionCookieStrategy(remember));
        var maxAge = new HttpSessionConfiguration();
        maxAge.setCookieMaxAge(java.time.Duration.ofSeconds(300));
        assertThrows(IllegalArgumentException.class, () -> new BrowserSessionCookieStrategy(maxAge));
    }
}
