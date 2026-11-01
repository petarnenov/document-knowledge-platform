package org.documentknowledge.knowledgebase.adapters.inbound;

import io.micronaut.core.order.Ordered;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import java.util.*;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;

/** The whole eight-second budget starts before authentication, not in a controller. */
@ServerFilter("/api/v1/knowledge/**")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class KnowledgeBoundaryFilter {
    static final String DEADLINE="dkp.kb.request-deadline";
    private static final Set<String> CODES=Set.of("BAD_REQUEST","UNAUTHENTICATED","FORBIDDEN","NOT_FOUND","STALE_REVISION","IDEMPOTENCY_CONFLICT","STALE_POLICY","INVALID_POLICY","DUPLICATE_ACL_CHANGE","ACL_RULE_LIMIT","RESTRICT_SCOPE","TARGET_NOT_GRANTABLE","DELEGATION_CEILING","LIMIT_EXCEEDED","UNAVAILABLE","TOO_MANY_REQUESTS","PAYLOAD_TOO_LARGE","UNSUPPORTED_MEDIA_TYPE","NOT_ACCEPTABLE","DUPLICATE_DOCUMENT","STALE_AUTHORITY");
    private final KnowledgeRequestOwner owner;
    public KnowledgeBoundaryFilter(KnowledgeRequestOwner owner){this.owner=owner;}
    @RequestFilter public org.reactivestreams.Publisher<MutableHttpResponse<?>> begin(HttpRequest<?> request,io.micronaut.http.filter.FilterContinuation<org.reactivestreams.Publisher<MutableHttpResponse<?>>> continuation){Deadline deadline=Deadline.request();request.setAttribute(DEADLINE,deadline);owner.begin(request,deadline);return owner.supervise(request,deadline,continuation.proceed());}
    @ResponseFilter public void finish(HttpRequest<?> request,MutableHttpResponse<?> response){
        try{
        response.getHeaders().set("Cache-Control","no-store");response.getHeaders().remove("ETag");response.getHeaders().remove("Last-Modified");
        int status=response.code();
        if(status<400){try{request.getAttribute(DEADLINE,Deadline.class).orElseThrow(KbFailure::unavailable).check();}catch(RuntimeException expired){response.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code","UNAVAILABLE"));}return;}
        if(status>=500&&status!=503){response.status(HttpStatus.SERVICE_UNAVAILABLE);status=503;}
        Object body=response.getBody().orElse(null);
        if(!(body instanceof Map<?,?> map&&map.size()==1&&map.get("code") instanceof String code&&CODES.contains(code))){
            String code=switch(status){case 401->"UNAUTHENTICATED";case 403->"FORBIDDEN";case 404->"NOT_FOUND";case 406->"NOT_ACCEPTABLE";case 409->"STALE_REVISION";case 413->"PAYLOAD_TOO_LARGE";case 415->"UNSUPPORTED_MEDIA_TYPE";case 429->"TOO_MANY_REQUESTS";case 503->"UNAVAILABLE";default->"BAD_REQUEST";};
            response.body(Map.of("code",code));
        }
        response.contentType(MediaType.APPLICATION_JSON_TYPE);
        }finally{owner.finish(request);}
    }
}
