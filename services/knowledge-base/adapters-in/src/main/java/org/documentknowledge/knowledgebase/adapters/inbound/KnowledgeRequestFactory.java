package org.documentknowledge.knowledgebase.adapters.inbound;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.knowledgebase.application.KnowledgeService;
import org.documentknowledge.knowledgebase.domain.KbModels.Context;

/** Request-scoped original verified identity and transport, never a browser authority DTO. */
public interface KnowledgeRequestFactory {
    RequestHandle open(Context verified,Map<String,Object> verifiedClaims,String originalVerifiedAuthorization);
    final class RequestHandle implements AutoCloseable {
        private final KnowledgeService service;private final Runnable current;private final Runnable cleanup;private final AtomicBoolean closed=new AtomicBoolean();
        public RequestHandle(KnowledgeService service,Runnable current,Runnable cleanup){this.service=Objects.requireNonNull(service);this.current=Objects.requireNonNull(current);this.cleanup=Objects.requireNonNull(cleanup);}
        public KnowledgeService service(){return service;}
        public void revalidate(){current.run();}
        @Override public void close(){if(closed.compareAndSet(false,true))try{cleanup.run();}catch(RuntimeException ignored){/* Cleanup never changes an already established command outcome. */}}
    }
}
