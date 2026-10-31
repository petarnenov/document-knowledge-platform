package org.documentknowledge.browserbff.bootstrap;

import com.nimbusds.jwt.JWTClaimsSet;
import io.micronaut.security.oauth2.endpoint.token.response.JWTOpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.documentknowledge.browserbff.adapters.inbound.VerifiedOpenIdMapper;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.SessionPolicy;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.identity.TenantMembership;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class OidcMapperPolicyTest {
    @Test void externalAuthorityDecisionsOverrideAllLocalStyleClaims() {
        var claims=new JWTOpenIdClaims(new JWTClaimsSet.Builder().issuer("https://issuer.example").subject("staff-1").audience("dkp-bff").expirationTime(Date.from(Instant.now().plusSeconds(180))).claim("dkp_tenants",List.of("forged-local")).build());
        var tokens=new OpenIdTokenResponse();tokens.setAccessToken("synthetic-access");tokens.setExpiresIn(120);
        for(var decision:AuthorityDecision.values()) {
            var mapper=new VerifiedOpenIdMapper("https://issuer.example","dkp-bff","external",actor->{assertEquals("https://issuer.example",actor.issuer());assertEquals("staff-1",actor.subject());return new TenantMembership(decision,decision==AuthorityDecision.ALLOW?Set.of("trusted-external"):Set.of());});
            var result=Mono.from(mapper.createAuthenticationResponse("keycloak",tokens,claims,null)).block();assertNotNull(result);
            var identity=(BrowserIdentity)result.getAuthentication().orElseThrow().getAttributes().get(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE);assertFalse(identity.tenants().contains("forged-local"));
            if(decision==AuthorityDecision.ALLOW)new SessionPolicy(Clock.systemUTC()).requireTenant(identity,"trusted-external");else assertEquals(decision==AuthorityDecision.DENY?403:503,assertThrows(BffFailure.class,()->new SessionPolicy(Clock.systemUTC()).requireActive(identity)).status());
        }
        var bad=new VerifiedOpenIdMapper("https://issuer.example","dkp-bff","external",actor->new TenantMembership(AuthorityDecision.ALLOW,Set.of("x".repeat(257))));assertTrue(Mono.from(bad.createAuthenticationResponse("keycloak",tokens,claims,null)).block().getAuthentication().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new VerifiedOpenIdMapper("https://issuer.example","dkp-bff","unknown",actor->TenantMembership.unavailable()));
    }
}
