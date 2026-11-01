package org.documentknowledge.documentmanagement.adapters.inbound;

import io.micronaut.core.order.Ordered;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.*;
import java.time.Duration;
import java.util.Map;
import org.documentknowledge.documentmanagement.application.sharing.SharingDeadline;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/** Starts ownership before SecurityFilter/JWKS. Timeout never renews ownership on late authentication. */
@Filter({"/api/v1/documents/knowledge-share-assessments","/api/v1/documents/knowledge-share-eligibility"})
public final class SharingRequestDeadlineFilter implements HttpServerFilter {
    static final String ATTRIBUTE="dkp.dm.sharing.deadline";
    @Override public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
    @Override public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request,ServerFilterChain chain){
        SharingDeadline deadline=SharingDeadline.request();request.setAttribute(ATTRIBUTE,deadline);
        return Mono.<MutableHttpResponse<?>>defer(()->Mono.from(chain.proceed(request)))
            .timeout(Duration.ofNanos(deadline.remainingNanos()))
            . <MutableHttpResponse<?>>map(response->{deadline.check();return response;})
            .onErrorResume(failure->{deadline.cancel();return Mono.just(HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store").body(Map.of("code","UNAVAILABLE")));})
            .doOnCancel(deadline::cancel);
    }
}
