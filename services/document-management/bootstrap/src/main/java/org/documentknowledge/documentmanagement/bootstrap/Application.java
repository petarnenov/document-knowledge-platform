package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.runtime.Micronaut;

public final class Application {
    private Application() { }

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
