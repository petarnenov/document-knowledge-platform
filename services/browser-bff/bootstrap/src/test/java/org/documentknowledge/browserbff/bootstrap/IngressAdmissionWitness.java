package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/** Holds real one-byte HTTP requests without replacing framework queues or disposal. */
@Controller("/dkp222")
@Requires(property = "dkp222.admission-witness", value = "true")
@Secured(SecurityRule.IS_ANONYMOUS)
final class IngressAdmissionWitness {
    final BlockingQueue<CompletableFuture<String>> held = new LinkedBlockingQueue<>();

    @Post(value = "/hold", consumes = MediaType.APPLICATION_OCTET_STREAM, produces = MediaType.TEXT_PLAIN)
    CompletableFuture<String> hold(@Body byte[] body) {
        if (body.length != 1) throw new IllegalArgumentException("TEST_REQUIRES_ONE_BYTE");
        var response = new CompletableFuture<String>();
        held.add(response);
        return response;
    }
}
