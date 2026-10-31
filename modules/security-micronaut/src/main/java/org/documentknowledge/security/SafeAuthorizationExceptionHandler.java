package org.documentknowledge.security;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.*;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.authentication.DefaultAuthorizationExceptionHandler;
import jakarta.inject.Singleton;
import java.util.Map;

@Singleton
@Replaces(DefaultAuthorizationExceptionHandler.class)
public class SafeAuthorizationExceptionHandler implements ExceptionHandler<AuthorizationException, MutableHttpResponse<?>> {
    @Override
    public MutableHttpResponse<?> handle(HttpRequest request, AuthorizationException exception) {
        boolean forbidden = exception.isForbidden();
        return HttpResponse.status(forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED)
            .body(Map.of("code", forbidden ? "ACCESS_DENIED" : "UNAUTHENTICATED"))
            .header("Cache-Control", "no-store").header("WWW-Authenticate", "Bearer");
    }
}
