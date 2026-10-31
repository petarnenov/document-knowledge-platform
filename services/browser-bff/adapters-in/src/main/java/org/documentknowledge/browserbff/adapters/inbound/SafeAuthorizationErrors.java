package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.authentication.DefaultAuthorizationExceptionHandler;
import jakarta.inject.Singleton;
import java.util.Map;

@Singleton
@Replaces(DefaultAuthorizationExceptionHandler.class)
public final class SafeAuthorizationErrors implements ExceptionHandler<AuthorizationException, MutableHttpResponse<?>> {
    @Override public MutableHttpResponse<?> handle(HttpRequest request, AuthorizationException failure) {
        return HttpResponse.status(failure.isForbidden() ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED)
            .body(Map.of("code", failure.isForbidden() ? "REQUEST_DENIED" : "AUTHENTICATION_REQUIRED"))
            .header("Cache-Control", "no-store");
    }
}
