package org.documentknowledge.browserbff.application;

import java.util.Map;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

/** DM remains the independent authority; this port never accepts a browser destination or headers. */
public interface DocumentGateway {
    record Result(int status, Map<String, Object> body) { }
    Result exchange(String method, String path, Map<String, String> query, Map<String, Object> body,
                    String idempotencyKey, BrowserIdentity identity, String tenant);
}
