package org.documentknowledge.devlogging.adapters.inbound.requests;

import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.LogFailure;

/** Event-loop filter: stamp before dispatch; never wait for logging workers or a dependency. */
@ServerFilter("/api/v1/dev-logs/**")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class LogRouteDeadline {
    private static final String ATTRIBUTE = LogRouteDeadline.class.getName() + ".deadline";

    @RequestFilter
    public void admit(HttpRequest<?> request) {
        if (request.getAttribute(ATTRIBUTE, RequestDeadline.class).isEmpty())
            request.getAttributes().put(ATTRIBUTE, RequestDeadline.start());
    }

    public static RequestDeadline admitted(HttpRequest<?> request) {
        return request.getAttribute(ATTRIBUTE, RequestDeadline.class)
            .orElseThrow(() -> new LogFailure(LogFailure.Kind.UNAVAILABLE));
    }
}
