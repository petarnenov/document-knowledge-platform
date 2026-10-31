package org.documentknowledge.identity;

import java.util.Set;

public record TenantMembership(AuthorityDecision decision, Set<String> tenants) {
    public TenantMembership {
        tenants = Set.copyOf(tenants);
        if (decision == null || (decision != AuthorityDecision.ALLOW && !tenants.isEmpty())) {
            throw new IllegalArgumentException("Invalid membership decision");
        }
    }
    public AuthorityDecision select(String tenant) {
        if (decision != AuthorityDecision.ALLOW) return decision;
        return tenant != null && tenants.contains(tenant) ? AuthorityDecision.ALLOW : AuthorityDecision.DENY;
    }
    public static TenantMembership unavailable() { return new TenantMembership(AuthorityDecision.UNAVAILABLE, Set.of()); }
    public static TenantMembership denied() { return new TenantMembership(AuthorityDecision.DENY, Set.of()); }
}
