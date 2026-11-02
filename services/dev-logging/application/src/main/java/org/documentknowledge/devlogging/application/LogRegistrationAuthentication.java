package org.documentknowledge.devlogging.application;

/** Dedicated server/operator credentials; never producer or reader authority. */
public interface LogRegistrationAuthentication {
    LogProducerRegistry.Registrar registrar(String token);
    LogProducerRegistry.Delegator delegator(String token);
}
