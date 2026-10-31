package org.documentknowledge.knowledgesearch.adapters.inbound;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.authentication.Authentication;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.security.TrustedTenantResolver;
import java.util.Map;

@Controller("/api/v1/security/context")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class SecurityContextController {
    private final TrustedTenantResolver resolver;
    public SecurityContextController(TrustedTenantResolver resolver) { this.resolver = resolver; }
    @Get
    public HttpResponse<?> context(Authentication authentication, @Header(value = "X-DKP-Tenant", defaultValue = "") String tenant) {
        AuthorityDecision decision = resolver.resolve(authentication.getAttributes()).select(tenant);
        if (decision != AuthorityDecision.ALLOW) {
            return HttpResponse.status(decision == AuthorityDecision.UNAVAILABLE ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN)
                .body(Map.of("code", decision == AuthorityDecision.UNAVAILABLE ? "AUTHORITY_UNAVAILABLE" : "TENANT_DENIED"))
                .header("Cache-Control", "no-store");
        }
        return HttpResponse.ok(Map.of("service", "search", "issuer", authentication.getAttributes().get("iss"),
                "subject", authentication.getAttributes().get("sub"), "tenant", tenant))
            .header("Cache-Control", "no-store");
    }
}
