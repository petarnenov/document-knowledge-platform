package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.util.Map;
import org.documentknowledge.browserbff.application.UploadWire;

/** Framework binding/route/provider failures must follow the same safe public error contract. */
@io.micronaut.core.annotation.Order(io.micronaut.core.order.Ordered.HIGHEST_PRECEDENCE)
@ServerFilter({"/api/v1/**", "/oauth/**"})
public final class SafeErrorResponses {
    @ResponseFilter public void sanitize(HttpRequest<?> request, MutableHttpResponse<?> response) {
        if (request.getUri().getRawPath().startsWith("/api/v1/kb/")) response.getHeaders().set("Cache-Control", "no-store");
        else response.header("Cache-Control", "no-store");
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
        String path=request.getUri().getRawPath().replaceFirst("/api/v1/dm/","/api/v1/");
        boolean upload=UploadWire.jsonRoute(request.getMethodName(),path)||UploadWire.transferRoute(path)||UploadWire.contentRoute(path);
        if(upload && status >= 500){ response.status(HttpStatus.SERVICE_UNAVAILABLE); response.body(Map.of("code","UNAVAILABLE")); }
        if(upload && response.getBody().orElse(null) instanceof Map<?,?> error && error.get("code") instanceof String code && !UploadWire.ERRORS.contains(code)){
            String canonical=switch(status){case 401->"UNAUTHENTICATED";case 403->"OPERATION_DENIED";case 413->"PAYLOAD_TOO_LARGE";case 503->"UNAVAILABLE";default->"INVALID_REQUEST";};
            response.body(Map.of("code",canonical));
        }
        response.contentType(MediaType.APPLICATION_JSON_TYPE);
    }
}
