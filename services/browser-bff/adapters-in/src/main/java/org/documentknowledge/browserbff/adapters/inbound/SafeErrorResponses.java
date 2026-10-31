package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.util.Map;

/** Framework binding/route/provider failures must follow the same safe public error contract. */
@ServerFilter({"/api/v1/**", "/oauth/**"})
public final class SafeErrorResponses {
    @ResponseFilter public void sanitize(HttpRequest<?> request, MutableHttpResponse<?> response) {
        response.header("Cache-Control", "no-store");
        int status = response.getStatus().getCode();
        if (status < 400) return;
        if (request.getPath().startsWith("/oauth/") && status >= 500) response.status(HttpStatus.SERVICE_UNAVAILABLE);
        Object body = response.getBody().orElse(null);
        if (!(body instanceof Map<?, ?> map && map.size() == 1 && map.get("code") instanceof String code
            && code.length() <= 64 && code.matches("[A-Z][A-Z0-9_]*"))) {
            String code = switch (response.getStatus().getCode()) {
                case 400 -> "INVALID_REQUEST";
                case 401 -> "AUTHENTICATION_REQUIRED";
                case 403 -> "REQUEST_DENIED";
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 413 -> "REQUEST_TOO_LARGE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                case 503 -> "DEPENDENCY_UNAVAILABLE";
                default -> "REQUEST_REJECTED";
            };
            response.body(Map.of("code", code));
        }
        response.contentType(MediaType.APPLICATION_JSON_TYPE);
    }
}
