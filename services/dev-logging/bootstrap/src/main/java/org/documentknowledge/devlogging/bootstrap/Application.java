package org.documentknowledge.devlogging.bootstrap;

import io.micronaut.runtime.Micronaut;

/** Dedicated log service; business authentication and database configuration are never imported. */
public final class Application {
    private Application() { }
    public static void main(String[] args) { Micronaut.run(Application.class, args); }
}
