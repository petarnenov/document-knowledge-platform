package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.security.oauth2.client.DefaultOpenIdProviderMetadata;
import jakarta.inject.Singleton;

@Singleton
public final class OidcMetadataGuard implements BeanCreatedEventListener<DefaultOpenIdProviderMetadata> {
    private final OidcTrust trust;
    public OidcMetadataGuard(OidcTrust trust) { this.trust = trust; }
    @Override public DefaultOpenIdProviderMetadata onCreated(BeanCreatedEvent<DefaultOpenIdProviderMetadata> event) {
        var metadata = event.getBean();
        trust.metadata(metadata.getIssuer(), metadata.getAuthorizationEndpoint(), metadata.getTokenEndpoint(), metadata.getJwksUri());
        return metadata;
    }
}
