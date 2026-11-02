package org.documentknowledge.devlogging.application;

/** Private runtime-observer authority; independent of SQL and all logging producer/read roles. */
@FunctionalInterface
public interface LogObserverAuthentication {
    void authenticate(String token);
}
