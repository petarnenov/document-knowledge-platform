package org.documentknowledge.loggingclientfixtures;

/** Negative architecture fixture: a producer must not acquire a logging service dependency. */
public final class ServiceLeak {
    public org.documentknowledge.devlogging.domain.LogFailure serviceFailure;
}
