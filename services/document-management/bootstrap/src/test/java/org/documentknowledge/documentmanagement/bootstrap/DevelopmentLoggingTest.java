package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.documentknowledge.loggingclient.ApplicationLogRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class DevelopmentLoggingTest {
    @TempDir Path directory;
    @Test void realServerLifecycleUsesExplicitPrivateProducerConfigWithoutOpeningSecurity() throws Exception {
        directory=directory.toRealPath();
        // Public default trust-anchor bytes only; no private test key or operator credential.
        var store=KeyStore.getInstance(Path.of(System.getProperty("java.home"),"lib/security/cacerts").toFile(),(char[])null);
        Path ca=directory.resolve("ca.crt");Files.write(ca,store.getCertificate(store.aliases().nextElement()).getEncoded());
        Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-------"));
        byte[] manifest;
        try(var input=Application.class.getResourceAsStream(ApplicationLogRuntime.RESOURCE)) {manifest=Objects.requireNonNull(input).readAllBytes();}
        Path config=directory.resolve("producer.json");
        var value=Map.of("schemaVersion",1,"manifestSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest)),
            "runId",UUID.randomUUID().toString(),"instanceId",UUID.randomUUID().toString(),
            "endpoint","https://localhost:1/api/v1/dev-logs","producerToken",UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),
            "caFile",ca.toString(),"spoolDirectory",directory.resolve("queue").toString());
        Files.write(config,JsonMapper.builder().build().writeValueAsBytes(value));Files.setPosixFilePermissions(config,PosixFilePermissions.fromString("rw-------"));
        ApplicationLogRuntime runtime;
        try(var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.host","127.0.0.1","micronaut.server.port",-1,
                "dkp.logging.producer-config",config.toString()))) {
            runtime=server.getApplicationContext().getBean(DevelopmentLogging.class).runtime();
            assertEquals(value.get("runId"),runtime.execution().runId().toString());
            assertEquals("document-management",runtime.execution().build().component());
            assertEquals(1,runtime.status().pendingEvents());assertEquals(0,runtime.status().committed());
            try(var client=java.net.http.HttpClient.newHttpClient()) {
                var response=client.send(java.net.http.HttpRequest.newBuilder(server.getURI().resolve("/api/v1/security/context")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(401,response.statusCode());
            }
        }
        assertTrue(runtime.status().closed());assertTrue(runtime.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(0,runtime.status().committed()); // Enqueue/shutdown is never represented as database persistence.
    }
}
