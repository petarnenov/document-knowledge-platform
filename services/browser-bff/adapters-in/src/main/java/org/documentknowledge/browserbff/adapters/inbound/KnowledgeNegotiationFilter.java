package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.*;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.*;
import java.util.Map;
import java.util.Set;
import org.documentknowledge.browserbff.application.BffFailure;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/** Content negotiation must not turn an authenticated fixed KB route into an unknown security route. */
@Filter("/api/v1/kb/**")
public final class KnowledgeNegotiationFilter implements HttpServerFilter {
    private final BrowserSessions sessions;
    public KnowledgeNegotiationFilter(BrowserSessions sessions){this.sessions=sessions;}
    @Override public int getOrder(){return ServerFilterPhase.SESSION.order()+1;}
    @Override public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request,ServerFilterChain chain){
        String accept=request.getHeaders().get("Accept");
        if(accept==null||Set.of("application/json","*/*").contains(accept))return chain.proceed(request);
        try{
            var session=sessions.requireSession(request);sessions.selectedIdentity(session);
            if(request.getMethod()!=HttpMethod.GET)sessions.requireMutation(request,session);
            int status=request.getHeaders().getAll("Accept").size()==1?406:400;
            return Mono.just(error(status,status==406?"NOT_ACCEPTABLE":"BAD_REQUEST"));
        }catch(BffFailure rejected){return Mono.just(error(rejected.status(),rejected.status()==401?"UNAUTHENTICATED":rejected.status()==403?"FORBIDDEN":"UNAVAILABLE"));}
    }
    private static MutableHttpResponse<?> error(int status,String code){return HttpResponse.status(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON_TYPE).body(Map.of("code",code));}
}
