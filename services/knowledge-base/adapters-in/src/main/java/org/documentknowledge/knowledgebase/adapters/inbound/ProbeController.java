package org.documentknowledge.knowledgebase.adapters.inbound;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import org.documentknowledge.knowledgebase.application.Readiness;

/** Intentionally minimal anonymous operational surface. */
@io.micronaut.security.annotation.Secured(io.micronaut.security.rules.SecurityRule.IS_ANONYMOUS)
@Controller(value = "/health", produces = MediaType.APPLICATION_JSON)
public final class ProbeController {
    private final Readiness readiness;
    private final java.util.function.BooleanSupplier dependencies;

    public ProbeController(Readiness readiness,@jakarta.inject.Named("kb-readiness") java.util.function.BooleanSupplier dependencies) {
        this.readiness = readiness;
        this.dependencies = dependencies;
    }

    @Get("/liveness")
    public HttpResponse<String> liveness() {
        return HttpResponse.ok("{\"status\":\"UP\"}");
    }

    @Get("/readiness")
    @io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
    public HttpResponse<String> readiness() {
        return readiness.isAcceptingWork() && dependencies.getAsBoolean()
                ? HttpResponse.ok("{\"status\":\"UP\"}")
                : HttpResponse.<String>status(HttpStatus.SERVICE_UNAVAILABLE).body("{\"status\":\"DOWN\"}");
    }
}
