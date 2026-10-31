package org.documentknowledge.identity;

@FunctionalInterface
public interface TenantMembershipPort {
    TenantMembership memberships(Actor actor);
}
