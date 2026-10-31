package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.context.ApplicationContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.documentknowledge.documentmanagement.application.Readiness;
import static org.junit.jupiter.api.Assertions.*;

class ProbeIntegrationTest {
    @Test
    void probesAreMinimalAndReadinessCanChangeWithoutKillingLiveness() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class,
                Map.of("micronaut.server.port", -1, "micronaut.server.host", "127.0.0.1"))) {
            HttpClient client = HttpClient.newHttpClient();
            assertProbe(client, server.getURI(), "/health/liveness", 200, "{\"status\":\"UP\"}");
            assertProbe(client, server.getURI(), "/health/readiness", 200, "{\"status\":\"UP\"}");
            Readiness readiness = server.getApplicationContext().getBean(Readiness.class);
            readiness.markUnavailable();
            assertProbe(client, server.getURI(), "/health/readiness", 503, "{\"status\":\"DOWN\"}");
            assertProbe(client, server.getURI(), "/health/liveness", 200, "{\"status\":\"UP\"}");
            readiness.markAvailable();
            assertProbe(client, server.getURI(), "/health/readiness", 200, "{\"status\":\"UP\"}");
        }
    }

    @Test
    void inventedTenantHeadersCannotUnlockBusinessOrManagementEndpoints() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class,
                Map.of("micronaut.server.port", -1, "micronaut.server.host", "127.0.0.1"))) {
            HttpClient client = HttpClient.newHttpClient();
            for (String path : new String[]{"/api/v1/documents", "/api/v1/knowledge/collections", "/api/v1/search", "/env", "/beans", "/routes", "/health", "/login", "/logout", "/oauth/access_token"}) {
                var response = client.send(HttpRequest.newBuilder(server.getURI().resolve(path))
                        .header("X-Tenant-Id", "invented-tenant").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, path);
                assertFalse(response.body().contains("invented-tenant"));
            }
        }
    }

    private static void assertProbe(HttpClient client, URI base, String path, int status, String body) throws Exception {
        var response = client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode());
        assertEquals(body, response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }
}
