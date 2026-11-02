package org.documentknowledge.observabilityfixtures;

import io.micronaut.context.ApplicationContext;

public final class FrameworkLeak {
    public ApplicationContext forbiddenDependency;
}
