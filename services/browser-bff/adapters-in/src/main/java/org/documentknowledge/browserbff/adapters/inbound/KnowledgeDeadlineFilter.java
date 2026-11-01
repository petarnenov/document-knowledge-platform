package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.core.order.Ordered;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.browserbff.application.KnowledgeDeadline;
import org.documentknowledge.browserbff.application.KnowledgeWire;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

@Filter("/api/v1/kb/**")
public final class KnowledgeDeadlineFilter implements HttpServerFilter {
    private final KnowledgeWire wire;private final KnowledgeTransportOwner owners;
    public KnowledgeDeadlineFilter(KnowledgeTransportOwner owners,KnowledgeWire wire){this.wire=wire;this.owners=owners;}
    static final String ATTRIBUTE="dkp.bff.knowledge.deadline";
    @Override public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
    @Override public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request,ServerFilterChain chain){
        var deadline=new KnowledgeDeadline();request.setAttribute(ATTRIBUTE,deadline);
        KnowledgeTransportOwner.Binding binding;
        try{binding=owners.attach(request,deadline);}catch(RuntimeException failure){deadline.cancel();return Mono.just(unavailable());}
        AtomicBoolean emitted=new AtomicBoolean();
        return Mono.<MutableHttpResponse<?>>defer(()->Mono.from(chain.proceed(request))).timeout(Duration.ofNanos(deadline.remainingNanos()))
            .<MutableHttpResponse<?>>map(response->{deadline.check();response.getHeaders().set("Cache-Control","no-store");if(response.code()>=400){Object body=response.body();if(!wire.validate("Error",body)){String code=switch(response.code()){case 400->"BAD_REQUEST";case 401->"UNAUTHENTICATED";case 403->"FORBIDDEN";case 404,405->"NOT_FOUND";case 406->"NOT_ACCEPTABLE";case 413->"PAYLOAD_TOO_LARGE";case 415->"UNSUPPORTED_MEDIA_TYPE";case 429->"TOO_MANY_REQUESTS";default->"UNAVAILABLE";};if(response.code()>=500)response.status(HttpStatus.SERVICE_UNAVAILABLE);if(response.code()==405)response.status(HttpStatus.NOT_FOUND);response.contentType(MediaType.APPLICATION_JSON_TYPE).body(Map.of("code",code));}}emitted.set(true);return response;})
            .onErrorResume(failure->{deadline.cancel();return Mono.just(unavailable());})
            .doOnCancel(()->{if(!emitted.get())deadline.cancel();}).doFinally(ignored->binding.close());
    }
    private static MutableHttpResponse<?> unavailable(){return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON_TYPE).headers(headers->headers.set("Cache-Control","no-store")).body(Map.of("code","UNAVAILABLE"));}
}
