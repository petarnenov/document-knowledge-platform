package org.documentknowledge.documentmanagement.bootstrap;

import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real held TLS collector fixture; its COMMITTED replies do not attest shared-store persistence. */
final class DatabaseLogCollector implements AutoCloseable {
    static final JsonMapper JSON=JsonMapper.builder().build();
    final List<Map<?,?>> rows=new CopyOnWriteArrayList<>();
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    final ExecutorService executor=Executors.newFixedThreadPool(2);
    final HttpsServer server;
    final Path configuration;
    final String run=UUID.randomUUID().toString();
    DatabaseLogCollector(Path root) throws Exception {
        Path store=root.resolve("diagnostic-tls.p12");String password=UUID.randomUUID().toString();
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/keytool").toString(),"-genkeypair","-alias","local",
            "-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1",
            "-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(command.waitFor(15,TimeUnit.SECONDS));assertEquals(0,command.exitValue());
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,password.toCharArray());}
        var manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());manager.init(keys,password.toCharArray());
        var tls=SSLContext.getInstance("TLS");tls.init(manager.getKeyManagers(),null,null);
        Path ca=root.resolve("diagnostic-ca.der");write(ca,keys.getCertificate("local").getEncoded());
        String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),8);server.setHttpsConfigurator(new HttpsConfigurator(tls));server.setExecutor(executor);
        server.createContext("/",exchange->{try(exchange){
            if(!("Bearer "+token).equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(401,-1);return;}
            var body=JSON.readValue(exchange.getRequestBody().readNBytes(32769),Map.class);var receipts=new ArrayList<Map<String,Object>>();
            if(exchange.getRequestURI().getPath().endsWith("/batches"))for(Object item:(List<?>)body.get("events")){
                var row=(Map<?,?>)item;rows.add(row);receipts.add(Map.of("eventId",row.get("eventId"),"state","COMMITTED","receivedAt",Instant.now().toString()));
            }else for(Object id:(List<?>)body.get("eventIds"))receipts.add(Map.of("eventId",id,"state","COMMITTED","receivedAt",Instant.now().toString()));
            entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))return;}catch(InterruptedException failure){Thread.currentThread().interrupt();return;}
            byte[] reply=JSON.writeValueAsBytes(Map.of("receipts",receipts));exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
        }});
        byte[] manifest;try(var input=Application.class.getResourceAsStream("/META-INF/dkp-logging/manifest.json")){manifest=Objects.requireNonNull(input).readAllBytes();}
        configuration=root.resolve("diagnostic-producer.json");
        write(configuration,JSON.writeValueAsBytes(Map.of("schemaVersion",1,"manifestSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest)),
            "runId",run,"instanceId",UUID.randomUUID().toString(),"endpoint","https://localhost:"+server.getAddress().getPort()+"/api/v1/dev-logs",
            "producerToken",token,"caFile",ca.toString(),"spoolDirectory",root.resolve("diagnostic-queue").toString())));
        server.start();
    }
    static void write(Path path,byte[] bytes)throws Exception{Files.write(path,bytes);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
    public void close(){release.countDown();server.stop(0);executor.shutdownNow();}
}
