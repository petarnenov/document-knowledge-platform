package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.security.oauth2.endpoint.authorization.response.AuthorizationErrorResponseException;
import io.micronaut.security.oauth2.endpoint.authorization.response.AuthorizationErrorResponseExceptionHandler;
import jakarta.inject.Singleton;
import java.net.URI;

@Singleton
@Replaces(AuthorizationErrorResponseExceptionHandler.class)
public final class SafeCallbackErrors implements ExceptionHandler<AuthorizationErrorResponseException, MutableHttpResponse<?>> {
    @Override public MutableHttpResponse<?> handle(HttpRequest request, AuthorizationErrorResponseException failure) {
        return HttpResponse.seeOther(URI.create("/?authentication=failed")).header("Cache-Control", "no-store");
    }
}
