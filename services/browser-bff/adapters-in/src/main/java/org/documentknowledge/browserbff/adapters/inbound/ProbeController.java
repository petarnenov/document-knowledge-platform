package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.http.HttpResponse;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import org.documentknowledge.browserbff.application.Readiness;

/** Intentionally minimal anonymous operational surface. */
@Secured(SecurityRule.IS_ANONYMOUS)
@Controller(value = "/health", produces = MediaType.APPLICATION_JSON)
public final class ProbeController {
    private final Readiness readiness;

    public ProbeController(Readiness readiness) {
        this.readiness = readiness;
    }

    @Get("/liveness")
    public HttpResponse<String> liveness() {
        return HttpResponse.ok("{\"status\":\"UP\"}");
    }

    @Get("/readiness")
    public HttpResponse<String> readiness() {
        return readiness.isAcceptingWork()
                ? HttpResponse.ok("{\"status\":\"UP\"}")
                : HttpResponse.<String>status(HttpStatus.SERVICE_UNAVAILABLE).body("{\"status\":\"DOWN\"}");
    }
}
