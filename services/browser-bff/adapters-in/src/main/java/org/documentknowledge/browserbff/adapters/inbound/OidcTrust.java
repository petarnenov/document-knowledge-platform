package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.net.URI;
import java.util.Set;

/** Configuration validation occurs before discovery, and again on the final discovered endpoints. */
@Singleton
public final class OidcTrust {
    private final String issuer;
    private final boolean enabled;
    private final boolean loopbackTest;
    public OidcTrust(@Value("${dkp.bff.issuer:}") String issuer,
                     @Value("${micronaut.security.oauth2.clients.keycloak.enabled:false}") boolean enabled,
                     @Value("${dkp.bff.client-id:dkp-bff}") String clientId,
                     @Value("${micronaut.security.oauth2.clients.keycloak.client-secret:}") String secret,
                     @Value("${micronaut.security.oauth2.callback-uri:}") String callback,
                     @Value("${dkp.bff.portal-origin:}") String portal, Environment environment) {
        this.issuer = issuer; this.enabled = enabled;
        loopbackTest = environment.getActiveNames().contains("test-oidc-loopback");
        if (enabled) {
            safe(issuer); safe(callback); safe(portal);
            if (clientId.isBlank() || clientId.length() > 256 || secret.isBlank()
                || !origin(URI.create(callback)).equals(origin(URI.create(portal)))) reject();
        }
    }
    public void configuration(String actualIssuer, String jwksOverride) {
        if (!enabled) return;
        if (!issuer.equals(actualIssuer)) reject();
        safe(actualIssuer);
        if (jwksOverride != null) endpoint(jwksOverride);
    }
    public void metadata(String actualIssuer, String authorization, String token, String jwks) {
        if (!enabled) return;
        if (!issuer.equals(actualIssuer)) reject();
        endpoint(authorization); endpoint(token); endpoint(jwks);
    }
    private void endpoint(String value) {
        URI parsed = safe(value);
        if (!origin(parsed).equals(origin(URI.create(issuer)))) reject();
    }
    private URI safe(String value) {
        try {
            URI uri = URI.create(value);
            boolean https = "https".equals(uri.getScheme());
            boolean test = loopbackTest && "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
            if ((!https && !test) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) reject();
            return uri;
        } catch (RuntimeException unsafe) { throw new IllegalArgumentException("Unsafe OIDC trust configuration"); }
    }
    private static String origin(URI uri) { return uri.getScheme() + "://" + uri.getHost() + ":" + (uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort()); }
    private static void reject() { throw new IllegalArgumentException("Unsafe OIDC trust configuration"); }
}
