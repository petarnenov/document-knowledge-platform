package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.configuration.jdbc.hikari.DatasourceConfiguration;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

/** Bound runtime acquisition before Hikari constructs its managed pool, without creating one. */
@Singleton
public final class RuntimePoolBounds implements BeanCreatedEventListener<DatasourceConfiguration> {
    @Override
    public DatasourceConfiguration onCreated(BeanCreatedEvent<DatasourceConfiguration> event) {
        DatasourceConfiguration configuration = event.getBean();
        if ("default".equals(configuration.getName())) {
            configuration.setConnectionTimeout(1000);
            configuration.setValidationTimeout(500);
        }
        return configuration;
    }
}
