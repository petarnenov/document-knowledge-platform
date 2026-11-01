package org.documentknowledge.browserbff.adapters.outbound;

import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.DocumentGateway;
import org.documentknowledge.browserbff.application.DocumentWire;
import org.documentknowledge.browserbff.application.UploadWire;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

@Singleton
public final class FixedDocumentGateway implements DocumentGateway {
    private final String upstream;
    private final JsonMapper json;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
    public FixedDocumentGateway(@Value("${dkp.bff.upstreams.dm:}") String upstream, JsonMapper json) { this.upstream = upstream; this.json = json; }
    @Override @SuppressWarnings("unchecked") public Result exchange(String method, String path, Map<String, String> query, Map<String, Object> body, String key, BrowserIdentity identity, String tenant) {
        DocumentWire.request(method, path, query, body, key);
        try {
            URI base = URI.create(upstream);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))) throw new IllegalArgumentException();
            String parameters = query.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
            var target = base.resolve(path + (parameters.isEmpty() ? "" : "?" + parameters));
            long timeout=path.endsWith("/complete")?300:3;
            var builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(timeout)).header("Authorization", "Bearer " + identity.accessToken()).header("X-DKP-Tenant", tenant).header("Accept", "application/json");
            if (method.equals("GET")) builder.GET();
            else {
                byte[] bytes = json.writeValueAsBytes(body);
                if (bytes.length > 16384) throw new BffFailure(413, "REQUEST_TOO_LARGE");
                builder.header("Content-Type", "application/json").header("Idempotency-Key", key).method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
            }
            var pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 1048576));
            HttpResponse<byte[]> response;
            try { response = pending.get(timeout, TimeUnit.SECONDS); }
            catch (Exception failure) { pending.cancel(true); throw failure; }
            if (!response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new IllegalArgumentException();
            Map<String, Object> fields = json.readValue(response.body(), Map.class);
            int status = response.statusCode();
            if (Set.of(400, 401, 403, 404, 409, 413, 415, 422, 429, 503).contains(status)) {
                if (fields == null || !fields.keySet().equals(Set.of("code")) || !(fields.get("code") instanceof String code) || !code.matches("[A-Z][A-Z0-9_]{0,63}") || UploadWire.jsonRoute(method,path) && !UploadWire.ERRORS.contains(code)) throw new IllegalArgumentException();
                return new Result(status, fields);
            }
            int expected = UploadWire.jsonRoute(method,path)?UploadWire.success(method,path):method.equals("POST") ? 201 : 200;
            if (status != expected || !DocumentWire.response(method, path, fields, tenant, Integer.parseInt(query.getOrDefault("limit", "20")))) throw new IllegalArgumentException();
            return new Result(status, fields);
        } catch (BffFailure rejected) { throw rejected; }
        catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BffFailure(503, UploadWire.jsonRoute(method,path)?"UNAVAILABLE":"UPSTREAM_UNAVAILABLE");
        }
    }
}
