package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.documentknowledge.browserbff.application.Readiness;

@Factory
public final class ReadinessFactory {
    @Singleton
    public Readiness readiness() {
        return new Readiness();
    }
}
