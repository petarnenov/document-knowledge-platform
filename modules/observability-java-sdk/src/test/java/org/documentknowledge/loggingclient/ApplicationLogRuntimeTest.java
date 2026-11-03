package org.documentknowledge.loggingclient;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.documentknowledge.observability.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Original artifact/bootstrap policy fixtures, never installed into the actual common store. */
class ApplicationLogRuntimeTest {
    @TempDir static Path pki;
    @TempDir Path directory;
    static SSLContext serverTls;
    static byte[] ca;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String TOKEN=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
    Map<String,Object> manifest,config;
    byte[] raw;
    Path configuration;
    HttpsServer server;
    ExecutorService workers;

    @BeforeAll static void certificate() throws Exception {
        Path store=pki.resolve("fixture.p12");String password=UUID.randomUUID().toString();
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/keytool").toString(),"-genkeypair","-alias","local",
            "-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1",
            "-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password)
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)) {keys.load(input,password.toCharArray());}
        ca=keys.getCertificate("local").getEncoded();
        var manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());manager.init(keys,password.toCharArray());
        serverTls=SSLContext.getInstance("TLS");serverTls.init(manager.getKeyManagers(),null,null);
    }
    @BeforeEach void prepare() throws Exception {
        directory=directory.toRealPath();
        var vocabulary=Map.of("events",Arrays.stream(EventCode.values()).map(EventCode::wireName).toList(),
            "phases",List.of("STARTUP","SHUTDOWN"),"errorCodes",List.of(),"dependencies",List.of(),"exceptionClasses",List.of(),"frames",List.of());
        manifest=new LinkedHashMap<>(Map.of("schemaVersion",1,"buildId",UUID.randomUUID().toString(),"component","knowledge-search",
            "gitSha","a".repeat(40),"sourceFingerprint","b".repeat(64),"compiledAt",Instant.now().toString(),
            "toolchainFingerprint","c".repeat(64),"dependencyFingerprint","d".repeat(64),"vocabularySha256","e".repeat(64),"vocabulary",vocabulary));
        manifest.put("sourceFingerprintAlgorithm","dkp-public-inputs-v1");manifest.put("dirty",true);raw=JSON.writeValueAsBytes(manifest);
        Path certificate=directory.resolve("ca.crt");write(certificate,ca);
        config=new LinkedHashMap<>(Map.of("schemaVersion",1,"manifestSha256",digest(raw),"runId",UUID.randomUUID().toString(),
            "instanceId",UUID.randomUUID().toString(),"endpoint","https://localhost:1/api/v1/dev-logs","producerToken",TOKEN,
            "caFile",certificate.toString(),"spoolDirectory",directory.resolve("queue").toString()));
        configuration=directory.resolve("producer.json");save();
    }
    static String digest(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static void write(Path path,byte[] bytes) throws Exception {Files.write(path,bytes);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
    void save() throws Exception {write(configuration,JSON.writeValueAsBytes(config));}
    ApplicationLogRuntime open() throws Exception {return ApplicationLogRuntime.open("knowledge-search",new ByteArrayInputStream(raw),configuration);}
    void rejected() {var failure=assertThrows(IOException.class,this::open);assertEquals("LOG_APPLICATION_BOOTSTRAP_INVALID",failure.getMessage());assertNull(failure.getCause());}
    @AfterEach void cleanup() {if(server!=null)server.stop(0);if(workers!=null)workers.shutdownNow();}

    @Test void originalArtifactLifecycleReachesRealVerifiedTlsWithHonestCommitAndBoundedClose() throws Exception {
        var captured=new CopyOnWriteArrayList<Map<?,?>>();
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),8);server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        workers=Executors.newFixedThreadPool(2);server.setExecutor(workers);
        server.createContext("/",exchange->{try(exchange) {
            assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));
            var batch=JSON.readValue(exchange.getRequestBody().readNBytes(32769),Map.class);
            var events=(List<?>)batch.get("events");var receipts=new ArrayList<Map<String,Object>>();
            for(Object item:events) {var row=(Map<?,?>)item;captured.add(row);receipts.add(Map.of("eventId",row.get("eventId"),"state","COMMITTED","receivedAt",Instant.now().toString()));}
            byte[] reply=JSON.writeValueAsBytes(Map.of("receipts",receipts));exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
        }});server.start();config.put("endpoint","https://localhost:"+server.getAddress().getPort()+"/api/v1/dev-logs");save();
        var runtime=open();
        try {
            assertEquals(manifest.get("buildId"),runtime.execution().build().buildId().toString());
            assertEquals(config.get("runId"),runtime.execution().runId().toString());
            assertEquals(LogProducer.Admission.ACCEPTED,runtime.started());assertEquals(LogProducer.Admission.DUPLICATE,runtime.started());
            committed(runtime,1);assertEquals("process.started",captured.getFirst().get("eventName"));
            assertEquals(LogProducer.Admission.ACCEPTED,runtime.stopping());assertEquals(LogProducer.Admission.DUPLICATE,runtime.stopping());
            committed(runtime,2);assertEquals("process.stopped",captured.getLast().get("eventName"));
            for(var row:captured) {assertEquals(manifest.get("buildId"),row.get("buildId"));assertEquals(config.get("runId"),row.get("runId"));}
            assertFalse(new String(Files.readAllBytes(directory.resolve("queue/pending.bin")),StandardCharsets.ISO_8859_1).contains(TOKEN));
        } finally {runtime.close();assertTrue(runtime.awaitClosed(Duration.ofSeconds(2)));}
        assertTrue(runtime.status().closed());assertEquals(2,runtime.status().committed());
    }
    @Test void capturedOperationUsesOriginalCatalogAndReturnsWhileTlsResponseIsHeld() throws Exception {
        String symbol=getClass().getName()+".capturedOperationUsesOriginalCatalogAndReturnsWhileTlsResponseIsHeld";
        String source="modules/observability-java-sdk/src/test/java/org/documentknowledge/loggingclient/ApplicationLogRuntimeTest.java";
        manifest.put("vocabulary",Map.of("events",Arrays.stream(EventCode.values()).map(EventCode::wireName).toList(),
            "phases",List.of("STARTUP","SHUTDOWN","DATABASE"),"errorCodes",List.of("DB_UNAVAILABLE"),
            "dependencies",List.of(Map.of("name","postgresql","version","42.7.7","vendorBuildProvenance","UNKNOWN")),
            "exceptionClasses",List.of(IOException.class.getName()),"frames",List.of(Map.of("symbol",symbol,"source",source))));
        raw=JSON.writeValueAsBytes(manifest);config.put("manifestSha256",digest(raw));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var captured=new CopyOnWriteArrayList<Map<?,?>>();
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),8);server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        workers=Executors.newFixedThreadPool(2);server.setExecutor(workers);
        server.createContext("/",exchange->{try(exchange) {
            assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));
            var batch=JSON.readValue(exchange.getRequestBody().readNBytes(32769),Map.class);
            var row=(Map<?,?>)((List<?>)batch.get("events")).getFirst();captured.add(row);entered.countDown();
            try {assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return;}
            byte[] reply=JSON.writeValueAsBytes(Map.of("receipts",List.of(Map.of("eventId",row.get("eventId"),"state","COMMITTED","receivedAt",Instant.now().toString()))));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
        }});server.start();config.put("endpoint","https://localhost:"+server.getAddress().getPort()+"/api/v1/dev-logs");save();
        var runtime=open();
        try {
            IOException actual;
            try {throw new IOException("private-operation-content-canary",new IOException("private-cause-canary"));}
            catch(IOException failure) {actual=failure;}
            var failure=runtime.capture(actual);assertEquals(IOException.class.getName(),failure.failure().exceptionClass());
            assertEquals(List.of(IOException.class.getName()),failure.failure().causeClasses());assertFalse(failure.failure().stackFrames().isEmpty());
            var metadata=runtime.metadata(Severity.ERROR,EventCode.OPERATION_STARTED,UUID.randomUUID(),new TraceContext("a".repeat(32),"b".repeat(16)));
            assertEquals(runtime.execution(),metadata.execution());
            var dependency=new LogDiagnosticCatalog.Dependency("postgresql","42.7.7",null,LogDiagnosticCatalog.Provenance.UNKNOWN);
            var diagnostics=new LogDiagnostics(503,500L,"DB_UNAVAILABLE","08006",dependency,failure.failure(),new LogDiagnostics.Attributes(null,1L,false),failure.counts());
            var event=new LifecycleEvent(metadata,"DATABASE",LifecycleEvent.Outcome.FAILED,1,1,1,true,diagnostics);
            assertEquals(LogProducer.Admission.ACCEPTED,runtime.emit(event));
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(0,runtime.status().committed());
            // Emit has returned and the real TLS peer still holds its response.
            assertEquals(1,release.getCount());assertEquals(LogProducer.Admission.DUPLICATE,runtime.emit(event));
            release.countDown();committed(runtime,1);
            var row=captured.getFirst();assertEquals(metadata.eventId().toString(),row.get("eventId"));
            var context=(Map<?,?>)row.get("context");assertEquals("DB_UNAVAILABLE",context.get("errorCode"));assertEquals("08006",context.get("sqlState"));
            assertEquals(List.of(IOException.class.getName()),context.get("causeClasses"));
            assertFalse(JSON.writeValueAsString(row).contains("canary"));assertEquals(manifest.get("buildId"),row.get("buildId"));
        } finally {release.countDown();runtime.close();assertTrue(runtime.awaitClosed(Duration.ofSeconds(2)));}
    }
    static void committed(ApplicationLogRuntime runtime,long count) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(runtime.status().committed()!=count && System.nanoTime()<until)Thread.sleep(10);
        assertEquals(count,runtime.status().committed());
    }
    @Test void requestObservationReusesOriginalExecutionAndTheExistingBoundedQueue() throws Exception {
        var vocabulary=new LinkedHashMap<Object,Object>((Map<?,?>)manifest.get("vocabulary"));
        vocabulary.put("phases",List.of("STARTUP","SHUTDOWN","REQUEST"));manifest.put("vocabulary",vocabulary);
        raw=JSON.writeValueAsBytes(manifest);config.put("manifestSha256",digest(raw));save();
        try(var runtime=open()) {
            var request=runtime.requests().started(UUID.randomUUID().toString(),null);
            assertEquals(LogProducer.Admission.ACCEPTED,runtime.requests().completed(request,200,null));
            assertEquals(2,runtime.status().pendingEvents());assertEquals(0,runtime.status().committed());
            assertEquals(config.get("runId"),runtime.execution().runId().toString());
        }
    }
    @Test void mismatchedArtifactComponentVocabularyAndOriginalIdentityRefuse() throws Exception {
        byte[] original=raw;
        for(var entry:Map.<String,Object>of("component","browser-bff","schemaVersion",2,"sourceFingerprintAlgorithm","other",
                "dirty","false","toolchainFingerprint","invalid","buildId","1-1-1-1-1","compiledAt","invalid").entrySet()) {
            var value=new LinkedHashMap<>(manifest);value.put(entry.getKey(),entry.getValue());raw=JSON.writeValueAsBytes(value);config.put("manifestSha256",digest(raw));save();rejected();
        }
        raw=original;config.put("manifestSha256","0".repeat(64));save();rejected();
        config.put("manifestSha256",digest(raw));config.put("runId","1-1-1-1-1");save();rejected();
        var value=new LinkedHashMap<>(manifest);value.put("vocabulary",Map.of("events",List.of("raw.text"),"phases",List.of()));raw=JSON.writeValueAsBytes(value);config.put("manifestSha256",digest(raw));save();rejected();
    }
    @Test void privateConfigurationStrictBoundsAndTlsFailuresNeverEchoInputs() throws Exception {
        config.put("extra",TOKEN);save();rejected();config.remove("extra");save();
        write(configuration,("{\"schemaVersion\":1,\"schemaVersion\":1}").getBytes(StandardCharsets.UTF_8));rejected();save();
        Files.setPosixFilePermissions(configuration,PosixFilePermissions.fromString("rw-r--r--"));rejected();save();
        Path original=configuration;configuration=directory.resolve("link.json");Files.createSymbolicLink(configuration,original);rejected();configuration=original;
        write(configuration,new byte[8193]);rejected();write(configuration,new byte[]{(byte)0xff});rejected();save();
        config.put("endpoint","http://localhost:1/api/v1/dev-logs");save();rejected();
        config.put("endpoint","https://localhost:1/api/v1/dev-logs");config.put("producerToken","secret\r\nunsafe");save();rejected();
        config.put("producerToken",TOKEN);save();write(Path.of((String)config.get("caFile")),new byte[]{1,2,3});rejected();
        raw=new byte[1048577];config.put("manifestSha256",digest(raw));save();rejected();
    }
}
