package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.documentknowledge.identity.DefaultAuthorities;
import org.documentknowledge.identity.TenantMembershipPort;

@Factory
public final class EntitlementsFactory {
    @Singleton public TenantMembershipPort entitlements() { return DefaultAuthorities.entitlements(); }
}
