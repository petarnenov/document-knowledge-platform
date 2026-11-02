package org.documentknowledge.browserbff.adapters.outbound;

import jakarta.inject.Singleton;
import java.io.InputStream;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Shared only for application-owned upload sources; never substitutes for domain upload slots. */
@Singleton
final class UploadReadAdmission {
    static final int CAPACITY = 64;
    private final IdentityHashMap<InputStream, Lease> owners = new IdentityHashMap<>();

    synchronized Lease tryRetain(InputStream source) {
        Objects.requireNonNull(source);
        if (owners.size() >= CAPACITY || owners.containsKey(source)) return null;
        var lease = new Lease(source);
        owners.put(source, lease);
        return lease;
    }

    synchronized int retained() { return owners.size(); }

    final class Lease {
        private final InputStream source;
        private Lease(InputStream source) { this.source = source; }

        void release() {
            synchronized (UploadReadAdmission.this) {
                if (owners.get(source) == this) owners.remove(source);
            }
        }
    }
}
