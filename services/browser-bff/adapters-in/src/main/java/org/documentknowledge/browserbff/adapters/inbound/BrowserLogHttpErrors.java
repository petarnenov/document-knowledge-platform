package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.*;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import java.util.Map;
import org.documentknowledge.browserbff.application.BrowserLogGateway;

/** Handles only this logging executor's capacity refusal; ordinary framework errors retain their processor. */
@Singleton
@Requires(beans = BrowserLogGateway.class)
public final class BrowserLogHttpErrors implements ExceptionHandler<BrowserLogResponseExecutor.CapacityException, MutableHttpResponse<?>> {
    @Override public MutableHttpResponse<?> handle(HttpRequest request, BrowserLogResponseExecutor.CapacityException failure) {
        return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(Map.of("code", "LOG_RATE_LIMITED"))
            .contentType(MediaType.APPLICATION_JSON_TYPE).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff");
    }
}
