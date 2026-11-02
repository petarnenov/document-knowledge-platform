package org.documentknowledge.devlogging.application;

import org.documentknowledge.devlogging.domain.ProducerScope;

/** Scoped replay credential only; no registration, delegation, browser or read authority. */
@FunctionalInterface
public interface LogProducerAuthentication {
    ProducerScope authenticate(String token, RequestDeadline deadline);
}
