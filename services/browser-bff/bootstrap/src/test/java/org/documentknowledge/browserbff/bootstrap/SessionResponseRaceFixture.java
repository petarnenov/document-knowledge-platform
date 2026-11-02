package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.documentknowledge.browserbff.adapters.inbound.BrowserSessions;

/** Test-only response gate after the real session filter has loaded authority. */
@Controller("/api/v1/session-response-race")
@Requires(property = "dkp.test.session-response-race", value = "true")
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class SessionResponseRaceFixture {
    final CountDownLatch loaded = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final BrowserSessions sessions;

    public SessionResponseRaceFixture(BrowserSessions sessions) { this.sessions = sessions; }

    @Get public HttpResponse<?> held(HttpRequest<?> request) throws InterruptedException {
        var view = sessions.view(sessions.requireSession(request));
        loaded.countDown();
        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test response gate not released");
        return HttpResponse.ok(Map.of("selectedTenant", view.selectedTenant())).header("Cache-Control", "no-store");
    }
}
