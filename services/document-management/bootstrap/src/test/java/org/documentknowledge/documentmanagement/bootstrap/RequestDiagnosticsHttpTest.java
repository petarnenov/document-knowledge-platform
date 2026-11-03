package org.documentknowledge.documentmanagement.bootstrap;

import com.sun.net.httpserver.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/filter/TLS composition fixture; the test manifest is not an original build attestation. */
class RequestDiagnosticsHttpTest {
    @TempDir Path temporary;
    static final JsonMapper JSON=JsonMapper.builder().build();
    @Controller("/test/request-failure")
    @Requires(property="dkp.test.request-logging",value="true")
    @Secured(SecurityRule.IS_ANONYMOUS)
    static final class FailedOperation {
        @Get String fail() {throw new IllegalStateException("private-failure-content-canary");}
    }
    static void write(Path path,byte[] bytes) throws Exception {
        Files.write(path,bytes);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
    @Test void realHttpCompletesWhileCollectorIsHeldAndReportsSuccessRejectionAndActualFailure() throws Exception {
        Path root=temporary.toRealPath(),store=root.resolve("fixture.p12");String password=UUID.randomUUID().toString();
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/keytool").toString(),"-genkeypair","-alias","local",
            "-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1",
            "-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(command.waitFor(15,TimeUnit.SECONDS));assertEquals(0,command.exitValue());
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)) {keys.load(input,password.toCharArray());}
        var manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());manager.init(keys,password.toCharArray());
        var tls=SSLContext.getInstance("TLS");tls.init(manager.getKeyManagers(),null,null);
        Path ca=root.resolve("ca.der");write(ca,keys.getCertificate("local").getEncoded());
        String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        var captured=new CopyOnWriteArrayList<Map<?,?>>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var collector=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),8);collector.setHttpsConfigurator(new HttpsConfigurator(tls));
        var executor=Executors.newFixedThreadPool(2);collector.setExecutor(executor);
        collector.createContext("/",exchange->{try(exchange) {
            if(!("Bearer "+token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {exchange.sendResponseHeaders(401,-1);return;}
            var batch=JSON.readValue(exchange.getRequestBody().readNBytes(32769),Map.class);var receipts=new ArrayList<Map<String,Object>>();
            for(Object item:(List<?>)batch.get("events")) {var row=(Map<?,?>)item;captured.add(row);
                receipts.add(Map.of("eventId",row.get("eventId"),"state","COMMITTED","receivedAt",Instant.now().toString()));}
            entered.countDown();try {if(!release.await(10,TimeUnit.SECONDS))return;}catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return;}
            byte[] reply=JSON.writeValueAsBytes(Map.of("receipts",receipts));exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
        }});collector.start();
        try {
            byte[] manifest;try(var input=Application.class.getResourceAsStream("/META-INF/dkp-logging/manifest.json")) {manifest=Objects.requireNonNull(input).readAllBytes();}
            String run=UUID.randomUUID().toString();Path configuration=root.resolve("producer.json");
            write(configuration,JSON.writeValueAsBytes(Map.of("schemaVersion",1,"manifestSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest)),
                "runId",run,"instanceId",UUID.randomUUID().toString(),"endpoint","https://localhost:"+collector.getAddress().getPort()+"/api/v1/dev-logs",
                "producerToken",token,"caFile",ca.toString(),"spoolDirectory",root.resolve("queue").toString())));
            var settings=new SecurityIntegrationTest().settings("standalone");settings.put("dkp.logging.producer-config",configuration.toString());settings.put("dkp.test.request-logging",true);
            try(var server=ApplicationContext.run(EmbeddedServer.class,settings);var client=HttpClient.newHttpClient()) {
                var runtime=server.getApplicationContext().getBean(DevelopmentLogging.class).runtime();
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                var correlations=new ArrayList<String>();
                for(var expected:Map.of("/health/liveness?private-query-canary",200,"/api/v1/security/context",401,"/test/request-failure",500).entrySet()) {
                    String correlation=UUID.randomUUID().toString();correlations.add(correlation);
                    var request=HttpRequest.newBuilder(server.getURI().resolve(expected.getKey())).header("X-Correlation-Id",correlation)
                        .header("traceparent","private-trace-canary").header("X-DKP-Tenant","private-tenant-canary").GET().build();
                    var response=client.sendAsync(request,HttpResponse.BodyHandlers.ofString()).get(3,TimeUnit.SECONDS);
                    assertEquals(expected.getValue(),response.statusCode());
                }
                assertEquals(1,release.getCount());assertEquals(0,runtime.status().committed());
                release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(runtime.status().committed()<7 && System.nanoTime()<deadline)Thread.sleep(10);
                assertEquals(7,runtime.status().committed());
                for(String correlation:correlations) {
                    var rows=captured.stream().filter(row->correlation.equals(row.get("correlationId"))).toList();assertEquals(2,rows.size());
                    assertEquals("operation.started",rows.getFirst().get("eventName"));assertEquals(run,rows.getLast().get("runId"));
                    assertFalse(rows.getLast().containsKey("traceId"));
                }
                var terminal=captured.stream().filter(row->((Map<?,?>)row.get("context")).containsKey("status")).toList();assertEquals(3,terminal.size());
                assertEquals(Set.of(200,401,500),new HashSet<>(terminal.stream().map(row->((Map<?,?>)row.get("context")).get("status")).toList()));
                var failure=terminal.stream().filter(row->Integer.valueOf(500).equals(((Map<?,?>)row.get("context")).get("status"))).findFirst().orElseThrow();
                assertEquals("java.lang.IllegalStateException",((Map<?,?>)failure.get("context")).get("exceptionClass"));
                String json=JSON.writeValueAsString(captured);assertFalse(json.contains("canary"));assertFalse(json.contains(token));
            }
        } finally {release.countDown();collector.stop(0);executor.shutdownNow();}
    }
}
