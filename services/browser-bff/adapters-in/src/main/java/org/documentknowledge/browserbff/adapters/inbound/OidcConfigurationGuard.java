package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.security.oauth2.configuration.OpenIdClientConfiguration;
import jakarta.inject.Singleton;

@Singleton
public final class OidcConfigurationGuard implements BeanCreatedEventListener<OpenIdClientConfiguration> {
    private final OidcTrust trust;
    public OidcConfigurationGuard(OidcTrust trust) { this.trust = trust; }
    @Override public OpenIdClientConfiguration onCreated(BeanCreatedEvent<OpenIdClientConfiguration> event) {
        var config = event.getBean();
        trust.configuration(config.getIssuer().map(Object::toString).orElse(""), config.getJwksUri().orElse(null));
        return config;
    }
}
