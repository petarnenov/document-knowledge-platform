package org.documentknowledge.browserbff.adapters.outbound;

import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.documentknowledge.browserbff.application.Readiness;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.ContextGateway;
import org.documentknowledge.browserbff.application.SecurityContext;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

/** Fixed route adapter. Request headers/URLs are never copied from an inbound browser request. */
@Singleton
public final class FixedContextGateway implements ContextGateway, AutoCloseable {
    private final Map<String, String> upstreams;
    private final JsonMapper json;
    private final HttpClient client;
    private final OwnedJdkTransport transport;
    private final boolean ownsTransport;
    @jakarta.inject.Inject
    public FixedContextGateway(@Value("${dkp.bff.upstreams.dm:}") String dm,
                               @Value("${dkp.bff.upstreams.kb:}") String kb,
                               @Value("${dkp.bff.upstreams.search:}") String search, JsonMapper json,
                               OwnedJdkTransport transport) {
        this(dm, kb, search, json, transport, false);
    }
    public FixedContextGateway(String dm, String kb, String search, JsonMapper json) {
        this(dm, kb, search, json, new OwnedJdkTransport(new Readiness()), true);
    }
    private FixedContextGateway(String dm, String kb, String search, JsonMapper json,
                                OwnedJdkTransport transport, boolean ownsTransport) {
        this.upstreams = Map.of("dm", dm, "kb", kb, "search", search); this.json = json;
        this.transport = transport; this.ownsTransport = ownsTransport; this.client = transport.newClient();
    }
    @Override public SecurityContext context(String service, BrowserIdentity identity, String tenant) {
        if (!upstreams.containsKey(service)) throw new BffFailure(404, "NOT_FOUND");
        URI target = target(upstreams.get(service));
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer " + identity.accessToken()).header("X-DKP-Tenant", tenant)
            .header("Accept", "application/json").GET().build();
        try (var exchange = transport.reserve(client, 3000)) {
            var response = exchange.send(request);
            byte[] bytes = response.readBounded(exchange, 8192, 3000);
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new BffFailure(response.statusCode(), "upstream_denied");
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json")) unavailable();
            Map<?, ?> fields = json.readValue(bytes, Map.class);
            if (fields == null || !fields.keySet().equals(Set.of("service", "issuer", "subject", "tenant"))
                || !service.equals(fields.get("service")) || !identity.issuer().equals(fields.get("issuer"))
                || !identity.subject().equals(fields.get("subject")) || !tenant.equals(fields.get("tenant"))) unavailable();
            return new SecurityContext(service, identity.issuer(), identity.subject(), tenant);
        } catch (BffFailure denied) { throw denied; }
        catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BffFailure(503, "UPSTREAM_UNAVAILABLE");
        }
    }
    private static URI target(String configured) {
        try {
            URI base = URI.create(configured);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))) unavailable();
            return base.resolve("/api/v1/security/context");
        } catch (IllegalArgumentException invalid) { throw new BffFailure(503, "UPSTREAM_UNAVAILABLE"); }
    }
    private static void unavailable() { throw new BffFailure(503, "UPSTREAM_UNAVAILABLE"); }
    /** Requests shutdown of this bean's client; active-exchange settlement is not implied. */
    @jakarta.annotation.PreDestroy
    @Override public void close() { client.shutdownNow(); if (ownsTransport) transport.close(); }
}
