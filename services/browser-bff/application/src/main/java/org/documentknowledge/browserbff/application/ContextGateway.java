package org.documentknowledge.browserbff.application;

import org.documentknowledge.browserbff.domain.BrowserIdentity;

public interface ContextGateway {
    SecurityContext context(String service, BrowserIdentity identity, String tenant);
}
