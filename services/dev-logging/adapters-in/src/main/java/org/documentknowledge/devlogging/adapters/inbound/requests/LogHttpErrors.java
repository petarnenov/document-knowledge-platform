package org.documentknowledge.devlogging.adapters.inbound.requests;

import io.micronaut.http.*;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

/** Public framework SPI also covers synchronous dispatch failures before a handler flow exists. */
@Singleton
public final class LogHttpErrors implements ErrorResponseProcessor<Map<String, String>> {
    @Override
    public MutableHttpResponse<Map<String, String>> processResponse(ErrorContext context, MutableHttpResponse<?> response) {
        Throwable cause = context.getRootCause().orElse(null);
        for (int depth = 0; depth < 4 && (cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null; depth++) cause = cause.getCause();
        int status = cause instanceof RejectedExecutionException ? 429 : response.code();
        if (status >= 500 || status < 400) status = 503;
        String code = switch (status) {
            case 400, 404, 405 -> "INVALID_LOG_QUERY";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "LOG_SCOPE_DENIED";
            case 413 -> "LOG_BATCH_TOO_LARGE";
            case 429 -> "LOG_RATE_LIMITED";
            default -> "LOG_STORE_UNAVAILABLE";
        };
        response.status(HttpStatus.valueOf(status)).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff");
        if (context.getRequest().getMethod() == HttpMethod.HEAD) return response.body(null);
        return response.body(Map.of("code", code)).contentType(MediaType.APPLICATION_JSON_TYPE);
    }
}
