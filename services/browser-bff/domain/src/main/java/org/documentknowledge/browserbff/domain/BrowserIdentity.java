package org.documentknowledge.browserbff.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable identity created only after framework ID-token verification. Never serialize this object. */
public record BrowserIdentity(String issuer, String subject, String displayName, List<String> tenants,
                              MembershipState membershipState, String accessToken, Instant expiresAt) {
    public BrowserIdentity {
        Objects.requireNonNull(issuer); Objects.requireNonNull(subject); Objects.requireNonNull(displayName);
        tenants = List.copyOf(tenants);
        Objects.requireNonNull(membershipState); Objects.requireNonNull(accessToken); Objects.requireNonNull(expiresAt);
    }
    public BrowserIdentity(String issuer, String subject, String displayName, List<String> tenants,
                           boolean available, String accessToken, Instant expiresAt) {
        this(issuer, subject, displayName, tenants, available ? MembershipState.AVAILABLE : MembershipState.UNAVAILABLE, accessToken, expiresAt);
    }
    public boolean membershipAvailable() { return membershipState == MembershipState.AVAILABLE; }
    @Override public String toString() { return "BrowserIdentity[credentials=REDACTED]"; }
}
