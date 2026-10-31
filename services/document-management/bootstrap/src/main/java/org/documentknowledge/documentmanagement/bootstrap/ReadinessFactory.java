package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.documentknowledge.documentmanagement.application.Readiness;

@Factory
public final class ReadinessFactory {
    @Singleton
    public Readiness readiness() {
        return new Readiness();
    }
}
