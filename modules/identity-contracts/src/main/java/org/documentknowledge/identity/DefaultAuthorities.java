package org.documentknowledge.identity;

/** Explicit incomplete integrations: no header, role or client identifier grants authority. */
public final class DefaultAuthorities {
    private DefaultAuthorities() { }
    public static TenantMembershipPort entitlements() { return actor -> TenantMembership.unavailable(); }
    public static ResourceAuthorizationPort resources() { return (actor, tenant, action, resource) -> AuthorityDecision.DENY; }
    public static WorkloadIdentityPort workloads() { return hint -> AuthorityDecision.UNAVAILABLE; }
}
