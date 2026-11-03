package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.BrowserLogDeadline;
import org.documentknowledge.browserbff.application.BrowserLogGateway;

/** Stamp trusted time before offload/authentication/body parsing; a client cannot select this budget. */
@ServerFilter("/api/v1/dev-logs/**")
@Requires(beans = BrowserLogGateway.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class BrowserLogRouteDeadline {
    private static final String ATTRIBUTE = BrowserLogRouteDeadline.class.getName() + ".deadline";
    @RequestFilter public void admit(HttpRequest<?> request) {
        if (request.getAttribute(ATTRIBUTE, BrowserLogDeadline.class).isEmpty())
            request.getAttributes().put(ATTRIBUTE, BrowserLogDeadline.start());
    }
    public static BrowserLogDeadline admitted(HttpRequest<?> request) {
        return request.getAttribute(ATTRIBUTE, BrowserLogDeadline.class)
            .orElseThrow(() -> new BffFailure(503, "LOG_STORE_UNAVAILABLE"));
    }
}
