package org.documentknowledge.browserbff.application;
import java.util.Map;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
/** Fixed KB-only destination; original verified bearer and server-selected tenant. */
public interface KnowledgeGateway {
    record Result(int status, Map<String,Object> body) { }
    Result exchange(String method,String path,Map<String,String> query,Map<String,Object> body,String key,BrowserIdentity identity,String tenant,KnowledgeDeadline deadline);
}
