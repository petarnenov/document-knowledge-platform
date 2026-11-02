package org.documentknowledge.devlogging.bootstrap;

import io.micronaut.runtime.Micronaut;

/** Dedicated log service; business authentication and database configuration are never imported. */
public final class Application {
    private Application() { }
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("install-manifest")) {
            int result = PrivateManifestCommand.run(args, System.out);
            if (result != 0) System.exit(result);
            return;
        }
        Micronaut.run(Application.class, args);
    }
}
