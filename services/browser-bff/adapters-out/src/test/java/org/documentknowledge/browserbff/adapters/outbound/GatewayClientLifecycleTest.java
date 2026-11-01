package org.documentknowledge.browserbff.adapters.outbound;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatewayClientLifecycleTest {
    private static final List<Class<?>> GATEWAYS = List.of(
        FixedContextGateway.class, FixedDocumentGateway.class, FixedUploadGateway.class);

    @Test void contextDestructionTerminatesOnlyItsOwnRealClients() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        var first = ApplicationContext.builder().build();
        var second = ApplicationContext.builder().build();
        var owned = new ArrayList<HttpClient>();
        var independent = new ArrayList<HttpClient>();
        try {
            server.start();
            first.registerSingleton(new org.documentknowledge.browserbff.application.Readiness());
            second.registerSingleton(new org.documentknowledge.browserbff.application.Readiness());
            first.start();
            second.start();
            var target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            for (var type : GATEWAYS) {
                owned.add(client(first.getBean(type)));
                independent.add(client(second.getBean(type)));
            }
            for (int i = 0; i < owned.size(); i++) {
                assertNotSame(owned.get(i), independent.get(i));
                exchange(owned.get(i), target);
                exchange(independent.get(i), target);
                assertFalse(owned.get(i).isTerminated());
                assertFalse(independent.get(i).isTerminated());
            }
            first.close();
            assertAll(owned.stream().map(client -> (org.junit.jupiter.api.function.Executable) () -> {
                assertTrue(client.awaitTermination(Duration.ofSeconds(5)),
                    "Application destruction must terminate its owned idle HTTP client");
                assertTrue(client.isTerminated());
                assertThrows(java.io.IOException.class, () -> exchange(client, target));
            }));
            for (var client : independent) {
                assertFalse(client.isTerminated());
                exchange(client, target);
            }
            second.close();
            for (var client : independent) {
                assertTrue(client.awaitTermination(Duration.ofSeconds(5)));
                assertTrue(client.isTerminated());
            }
        } finally {
            first.close();
            second.close();
            // Failure cleanup is deliberate: the pre-fix clients have no DI lifecycle hook.
            owned.forEach(HttpClient::shutdownNow);
            independent.forEach(HttpClient::shutdownNow);
            server.stop(0);
        }
    }

    private static HttpClient client(Object gateway) throws Exception {
        // Test-only identity observation: no production reflection or injectable mock transport.
        var field = gateway.getClass().getDeclaredField("client");
        field.setAccessible(true);
        return (HttpClient) field.get(gateway);
    }

    private static void exchange(HttpClient client, URI target) throws Exception {
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).GET().build();
        assertEquals(204, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }
}
