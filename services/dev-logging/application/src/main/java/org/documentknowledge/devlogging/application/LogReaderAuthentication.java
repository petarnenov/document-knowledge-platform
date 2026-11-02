package org.documentknowledge.devlogging.application;

import org.documentknowledge.devlogging.domain.LogReader;

/** Dedicated read credentials only; business sessions and producer tokens grant no query authority. */
@FunctionalInterface
public interface LogReaderAuthentication {
    LogReader authenticate(String token);
}
