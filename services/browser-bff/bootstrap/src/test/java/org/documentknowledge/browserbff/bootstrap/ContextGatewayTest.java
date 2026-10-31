package org.documentknowledge.browserbff.bootstrap;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.browserbff.adapters.outbound.FixedContextGateway;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextGatewayTest {
    private final BrowserIdentity actor=new BrowserIdentity("https://issuer.example","staff-1","Synthetic Staff",List.of("tenant-a"),true,"synthetic-access",Instant.now().plusSeconds(300));
    @Test void proxyReconstructsOnlyTrustedHeadersAndChecksEveryResponseField() throws Exception {
        try(var context=ApplicationContext.run()) {
            var json=context.getBean(JsonMapper.class);var calls=new AtomicInteger();var mode=new AtomicReference<>("valid");
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/security/context",exchange -> {
                calls.incrementAndGet();assertEquals("GET",exchange.getRequestMethod());assertEquals("Bearer synthetic-access",exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("tenant-a",exchange.getRequestHeaders().getFirst("X-DKP-Tenant"));assertNull(exchange.getRequestHeaders().getFirst("X-User-Id"));assertNull(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                String selected=mode.get();int status=200;String body="{\"service\":\"dm\",\"issuer\":\"https://issuer.example\",\"subject\":\"staff-1\",\"tenant\":\"tenant-a\"}";
                if(selected.equals("redirect")){status=302;exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/steal");}
                else if(selected.equals("denied"))status=403;else if(selected.equals("unauthenticated"))status=401;else if(selected.equals("down"))status=503;
                else if(selected.equals("oversize"))body="x".repeat(8193);else if(selected.equals("malformed"))body="not-json";
                else if(selected.equals("wrong-subject"))body=body.replace("staff-1","another-user");else if(selected.equals("wrong-tenant"))body=body.replace("tenant-a","tenant-b");
                else if(selected.equals("wrong-issuer"))body=body.replace("issuer.example","untrusted.example");else if(selected.equals("wrong-service"))body=body.replace("dm","search");
                else if(selected.equals("extra-field"))body=body.replace("}",",\"token\":\"unexpected\"}");else if(selected.equals("null"))body="null";
                exchange.getResponseHeaders().set("Content-Type",selected.equals("html")?"text/html":"application/json; charset=UTF-8");byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
            });server.start();
            try {
                String upstream="http://127.0.0.1:"+server.getAddress().getPort();var gateway=new FixedContextGateway(upstream,upstream,upstream,json);
                var verified=gateway.context("dm",actor,"tenant-a");assertEquals("dm",verified.service());assertEquals(actor.issuer(),verified.issuer());assertEquals(actor.subject(),verified.subject());assertEquals("tenant-a",verified.tenant());
                for(String bad:List.of("redirect","down","oversize","malformed","wrong-subject","wrong-tenant","wrong-issuer","wrong-service","extra-field","null","html")) {mode.set(bad);assertEquals(503,assertThrows(BffFailure.class,()->gateway.context("dm",actor,"tenant-a"),bad).status());}
                mode.set("denied");assertEquals(403,assertThrows(BffFailure.class,()->gateway.context("dm",actor,"tenant-a")).status());mode.set("unauthenticated");assertEquals(401,assertThrows(BffFailure.class,()->gateway.context("dm",actor,"tenant-a")).status());
                int before=calls.get();for(String target:List.of("http://evil.example","../dm","dm/../../steal","unknown"))assertEquals(404,assertThrows(BffFailure.class,()->gateway.context(target,actor,"tenant-a")).status());assertEquals(before,calls.get());
            } finally {server.stop(0);}
        }
    }
    @Test void unsafeOrUnavailableConfiguredDestinationsFailClosed() {
        try(var context=ApplicationContext.run()) {var json=context.getBean(JsonMapper.class);
            for(String upstream:List.of("", "relative", "ftp://host.example", "http://user:password@host.example", "http://host.example/other", "http://host.example?override=evil", "http://host.example#fragment", "http://[", "http://127.0.0.1:1")) {
                var gateway=new FixedContextGateway(upstream,upstream,upstream,json);assertEquals(503,assertThrows(BffFailure.class,()->gateway.context("dm",actor,"tenant-a")).status());
            }
        }
    }
    @Test void upstreamBodyDeadlineIsBoundedEvenAfterResponseHeaders() throws Exception {
        try(var context=ApplicationContext.run()) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/security/context",exchange -> {exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,100);try{Thread.sleep(4000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}exchange.close();});server.start();
            try {String upstream="http://127.0.0.1:"+server.getAddress().getPort();var gateway=new FixedContextGateway(upstream,upstream,upstream,context.getBean(JsonMapper.class));long start=System.nanoTime();assertEquals(503,assertThrows(BffFailure.class,()->gateway.context("dm",actor,"tenant-a")).status());assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()<3800);}finally{server.stop(0);}
        }
    }
}
