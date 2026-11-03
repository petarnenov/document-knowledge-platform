package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.web.router.RouteAttributes;
import org.documentknowledge.loggingclient.LogRequestDiagnostics;

/** Bootstrap observation only; request authorization and business behavior remain owned by their adapters. */
@ServerFilter("/**")
@Requires(property="dkp.logging.producer-config")
@Order(Ordered.HIGHEST_PRECEDENCE+25)
public final class RequestDiagnosticsFilter {
    private static final String TOKEN=RequestDiagnosticsFilter.class.getName()+".token";
    private final LogRequestDiagnostics requests;
    public RequestDiagnosticsFilter(DevelopmentLogging logging) {requests=logging.runtime().requests();}
    @RequestFilter public void started(HttpRequest<?> request) {
        request.setAttribute(TOKEN,requests.started(request.getHeaders().get("X-Correlation-Id"),request.getHeaders().get("traceparent")));
    }
    @ResponseFilter public void completed(HttpRequest<?> request,HttpResponse<?> response) {
        request.getAttribute(TOKEN,LogRequestDiagnostics.Token.class).ifPresent(token->
            requests.completed(token,response.code(),RouteAttributes.getException(response).orElse(null)));
    }
}
