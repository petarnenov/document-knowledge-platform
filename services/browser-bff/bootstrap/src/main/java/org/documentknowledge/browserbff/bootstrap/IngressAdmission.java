package org.documentknowledge.browserbff.bootstrap;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Application-instance budget; leases account for ingress ownership, never authorization. */
final class IngressAdmission {
    static final int CONNECTION_LIMIT = 32;
    static final int REQUEST_LIMIT = 64;
    private int connections;
    private int requests;

    synchronized Connection tryOpen() {
        if (connections == CONNECTION_LIMIT) return null;
        connections++;
        return new Connection();
    }

    synchronized Snapshot snapshot() { return new Snapshot(connections, requests); }

    record Snapshot(int connections, int requests) { }

    final class Connection {
        private final IdentityHashMap<Object, Request> retained = new IdentityHashMap<>();
        private boolean tornDown;

        boolean tryRetain(Object nativeRequest) {
            Objects.requireNonNull(nativeRequest);
            synchronized (IngressAdmission.this) {
                if (tornDown || retained.containsKey(nativeRequest) || requests == REQUEST_LIMIT) return false;
                retained.put(nativeRequest, new Request(nativeRequest));
                requests++;
                return true;
            }
        }

        /** Return a disposal callback only for this exact, previously admitted native identity. */
        Runnable bind(Object nativeRequest) {
            synchronized (IngressAdmission.this) {
                Request request = retained.get(nativeRequest);
                if (request == null || request.bound) return null;
                request.bound = true;
                return request::dispose;
            }
        }

        /** Caller must prove completed framework discard and confirmed channel teardown. */
        void confirmTeardown() {
            synchronized (IngressAdmission.this) {
                if (tornDown) return;
                tornDown = true;
                connections--;
                var iterator = retained.values().iterator();
                while (iterator.hasNext()) {
                    Request request = iterator.next();
                    // Bound bodies retain their charge until their own disposal callback.
                    if (!request.bound) {
                        iterator.remove();
                        requests--;
                    }
                }
            }
        }

        private final class Request {
            private final Object identity;
            private boolean bound;

            Request(Object identity) { this.identity = identity; }

            void dispose() {
                synchronized (IngressAdmission.this) {
                    if (retained.remove(identity, this)) requests--;
                }
            }
        }
    }
}
