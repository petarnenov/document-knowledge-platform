package org.documentknowledge.security;

import jakarta.inject.Singleton;
import org.documentknowledge.identity.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

@Singleton
public class TrustedTenantResolver {
    private final TrustConfiguration configuration;
    private final TenantMembershipPort external;
    @jakarta.inject.Inject
    public TrustedTenantResolver(TrustConfiguration configuration) { this(configuration, DefaultAuthorities.entitlements()); }
    TrustedTenantResolver(TrustConfiguration configuration, TenantMembershipPort external) { this.configuration = configuration; this.external = external; }
    public TenantMembership resolve(Map<String, Object> verifiedClaims) {
        if (!configuration.usable() || !configuration.getIssuer().equals(verifiedClaims.get("iss"))
            || !(verifiedClaims.get("sub") instanceof String subject) || subject.isBlank()) return TenantMembership.denied();
        Actor actor = new Actor(configuration.getIssuer(), subject);
        if ("external".equals(configuration.getProfile())) return external.memberships(actor);
        Object raw = verifiedClaims.get("dkp_tenants");
        if (!(raw instanceof Collection<?> values) || values.isEmpty() || values.size() > 100) return TenantMembership.denied();
        HashSet<String> tenants = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String tenant) || tenant.isBlank() || tenant.length() > 256
                || tenant.chars().anyMatch(Character::isISOControl) || !tenants.add(tenant)) return TenantMembership.denied();
        }
        return new TenantMembership(AuthorityDecision.ALLOW, tenants);
    }
}
