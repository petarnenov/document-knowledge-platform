package org.documentknowledge.browserbff.application;

import java.time.Clock;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

public final class SessionPolicy {
    private final Clock clock;
    public SessionPolicy(Clock clock) { this.clock = clock; }
    public void requireActive(BrowserIdentity identity) {
        if (identity == null || !identity.expiresAt().isAfter(clock.instant())) throw new BffFailure(401, "AUTHENTICATION_REQUIRED");
        if (identity.membershipState() == org.documentknowledge.browserbff.domain.MembershipState.DENIED) throw new BffFailure(403, "TENANT_DENIED");
        if (!identity.membershipAvailable()) throw new BffFailure(503, "MEMBERSHIP_UNAVAILABLE");
    }
    public void requireTenant(BrowserIdentity identity, String tenant) {
        requireActive(identity);
        if (tenant == null || !identity.tenants().contains(tenant)) throw new BffFailure(403, "TENANT_DENIED");
    }
    public SessionView view(BrowserIdentity identity, String tenant, String csrf) {
        requireActive(identity);
        return new SessionView(identity.issuer(), identity.subject(), identity.displayName(), identity.tenants(), tenant, csrf);
    }
}
