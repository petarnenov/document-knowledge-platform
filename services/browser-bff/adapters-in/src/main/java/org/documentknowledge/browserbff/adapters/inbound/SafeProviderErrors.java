package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import java.util.Map;

/** Never echo provider diagnostics or callback query strings through framework error payloads/logging. */
@Singleton
public final class SafeProviderErrors implements ExceptionHandler<HttpClientResponseException, MutableHttpResponse<?>> {
    @Override public MutableHttpResponse<?> handle(HttpRequest request, HttpClientResponseException failure) {
        boolean rejected = failure.getStatus().getCode() >= 400 && failure.getStatus().getCode() < 500;
        return HttpResponse.status(rejected ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("code", rejected ? "AUTHENTICATION_FAILED" : "IDENTITY_UNAVAILABLE")).header("Cache-Control", "no-store");
    }
}
