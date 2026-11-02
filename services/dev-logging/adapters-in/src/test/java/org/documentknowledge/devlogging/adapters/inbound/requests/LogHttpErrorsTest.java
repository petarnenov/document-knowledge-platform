package org.documentknowledge.devlogging.adapters.inbound.requests;

import io.micronaut.http.*;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogHttpErrorsTest {
    @Test void onlyActualOverloadBecomes429AndNoFrameworkErrorTextEscapes() {
        var processor = new LogHttpErrors();
        for (Throwable cause : new Throwable[]{new RejectedExecutionException("PRIVATE"),
                new CompletionException(new RejectedExecutionException("PRIVATE")),
                new ExecutionException(new RejectedExecutionException("PRIVATE"))}) {
            var response = processor.processResponse(ErrorContext.builder(HttpRequest.GET("/api/v1/dev-logs/events"))
                .cause(cause).errorMessage("PRIVATE").build(), HttpResponse.serverError());
            assertEquals(429, response.code()); assertEquals(Map.of("code", "LOG_RATE_LIMITED"), response.body());
            assertEquals("no-store", response.header("Cache-Control"));
        }
        var context = ErrorContext.builder(HttpRequest.GET("/api/v1/dev-logs/events")).cause(new IllegalStateException("PRIVATE")).build();
        assertEquals(503, processor.processResponse(context, HttpResponse.serverError()).code());
        assertEquals(503, processor.processResponse(context, HttpResponse.ok()).code());
        for (int status : new int[]{400, 401, 403, 404, 405, 413, 429, 503}) {
            var response = processor.processResponse(context, HttpResponse.status(HttpStatus.valueOf(status)));
            assertEquals(status, response.code()); assertFalse(response.body().toString().contains("PRIVATE"));
        }
        var head = processor.processResponse(ErrorContext.builder(HttpRequest.HEAD("/api/v1/dev-logs/events")).build(), HttpResponse.serverError());
        assertEquals(503, head.code()); assertNull(head.body());
    }
}
