package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Value;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.oauth2.endpoint.authorization.state.State;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdAuthenticationMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.VerifiedIdentityFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/** This hook runs only after the framework validates the signed ID token and OIDC transaction. */
@Singleton
@Named("keycloak")
public final class VerifiedOpenIdMapper implements OpenIdAuthenticationMapper {
    public static final String IDENTITY_ATTRIBUTE = "dkp.browser.identity";
    private final VerifiedIdentityFactory identities;
    private final org.documentknowledge.identity.TenantMembershipPort entitlements;
    public VerifiedOpenIdMapper(@Value("${dkp.bff.issuer:}") String issuer,
                                @Value("${dkp.bff.client-id:dkp-bff}") String clientId,
                                @Value("${dkp.bff.profile:standalone}") String profile, org.documentknowledge.identity.TenantMembershipPort entitlements) {
        if (!List.of("standalone", "external").contains(profile)) throw new IllegalArgumentException("Unsupported identity profile");
        this.entitlements = entitlements;
        identities = new VerifiedIdentityFactory(issuer, clientId, profile.equals("standalone"), Clock.systemUTC());
    }
    @Override public Publisher<AuthenticationResponse> createAuthenticationResponse(String providerName,
            OpenIdTokenResponse tokens, OpenIdClaims claims, State state) {
        try {
            var verified = new java.util.HashMap<String, Object>(claims.getClaims());
            verified.put("exp", claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant().getEpochSecond());
            var identity = identities.create(verified, tokens.getAccessToken(), tokens.getExpiresIn());
            if (!identity.membershipAvailable()) {
                var membership = entitlements.memberships(new org.documentknowledge.identity.Actor(identity.issuer(), identity.subject()));
                var status = switch (membership.decision()) {
                    case ALLOW -> org.documentknowledge.browserbff.domain.MembershipState.AVAILABLE;
                    case DENY -> org.documentknowledge.browserbff.domain.MembershipState.DENIED;
                    case UNAVAILABLE -> org.documentknowledge.browserbff.domain.MembershipState.UNAVAILABLE;
                };
                var tenants = membership.tenants().stream().sorted().toList();
                if (tenants.size() > 100 || tenants.stream().anyMatch(t -> t.isBlank() || t.length() > 256 || !t.equals(t.strip()) || t.chars().anyMatch(Character::isISOControl))) throw new BffFailure(401, "INVALID_MEMBERSHIP");
                identity = new org.documentknowledge.browserbff.domain.BrowserIdentity(identity.issuer(), identity.subject(), identity.displayName(), tenants, status, identity.accessToken(), identity.expiresAt());
            }
            // Deliberately retain neither the ID token nor any refresh token.
            return Mono.just(AuthenticationResponse.success(identity.subject(), List.of(), Map.of(IDENTITY_ATTRIBUTE, identity)));
        } catch (BffFailure rejected) {
            return Mono.just(AuthenticationResponse.failure("Invalid identity"));
        }
    }
}
