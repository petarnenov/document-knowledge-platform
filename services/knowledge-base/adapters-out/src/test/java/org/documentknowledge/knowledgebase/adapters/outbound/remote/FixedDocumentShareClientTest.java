package org.documentknowledge.knowledgebase.adapters.outbound.remote;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.application.DocumentShareBatchAuthorityPort.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class FixedDocumentShareClientTest {
    @TempDir static Path directory;
    static SSLContext tls; static Path ca;
    HttpsServer server; URI base; FixedDocumentShareClient client;
    Context actor=new Context(new Principal("https://issuer.example","staff"),"tenant-a");
    UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
    volatile int status=200; volatile String contentType="application/json",body; volatile boolean compressed; volatile HttpExchange seen; volatile byte[] received;
    final CountDownLatch release=new CountDownLatch(1); volatile boolean stall;
    @BeforeAll static void certificates()throws Exception {
        Path store=directory.resolve("test.p12");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/keytool").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=localhost","-ext","SAN=dns:localhost","-storetype","PKCS12","-keystore",store.toString(),"-storepass","synthetic-test-only","-keypass","synthetic-test-only","-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,process.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,"synthetic-test-only".toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"synthetic-test-only".toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        ca=directory.resolve("ca.crt");Files.writeString(ca,"-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(keys.getCertificate("test").getEncoded())+"\n-----END CERTIFICATE-----\n");Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-------"));
    }
    @BeforeEach void start()throws Exception {
        server=HttpsServer.create(new InetSocketAddress("localhost",0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/",exchange->{
            seen=exchange;received=exchange.getRequestBody().readAllBytes();
            try {
                exchange.getResponseHeaders().set("Content-Type",contentType);if(compressed)exchange.getResponseHeaders().set("Content-Encoding","gzip");
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
                if(stall)release.await(3,TimeUnit.SECONDS);
                exchange.getResponseBody().write(bytes);
            } catch(Exception ignored) { } finally {exchange.close();}
        });server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();
        base=URI.create("https://localhost:"+server.getAddress().getPort());client=new FixedDocumentShareClient(base,tls,actor,"Bearer verified-token");body=strict();
    }
    @AfterEach void stop(){release.countDown();client.close();server.stop(0);}
    Deadline deadline(){return new Deadline(System::nanoTime,Duration.ofSeconds(2));}
    String row(){return "{\"documentId\":\""+id+"\",\"documentRevision\":7,\"classification\":\"development-restricted\",\"classificationPolicyRevision\":1}";}
    String binding(){return "\"actor\":{\"issuer\":\"https://issuer.example\",\"subject\":\"staff\"},\"tenant\":\"tenant-a\",\"action\":\"document.share-to-collection\"";}
    String strict(){return "{"+binding()+",\"assessments\":["+row()+"]}";}
    String eligible(){return "{"+binding()+",\"requestedSetDigest\":\""+BatchEvidence.requestedDigest(List.of(id))+"\",\"eligible\":["+row()+"]}";}
    void failure(int code,Runnable call){assertEquals(code,assertThrows(KbFailure.class,call::run).status());}
    void assess(){client.assess(actor,List.of(new Expectation(id,null)),deadline());}
    @Test void genuineTlsStrictAndEligibilityRelayOnlyOriginalAuthorityToExactPaths() {
        assertEquals(7,client.assess(actor,List.of(new Expectation(id,7L)),deadline()).assessments().getFirst().documentRevision());
        assertEquals("/api/v1/documents/knowledge-share-assessments",seen.getRequestURI().toString());assertEquals("POST",seen.getRequestMethod());assertEquals("{\"documents\":[{\"documentId\":\""+id+"\",\"expectedDocumentRevision\":7}]}",new String(received,StandardCharsets.UTF_8));
        assertEquals("Bearer verified-token",seen.getRequestHeaders().getFirst("Authorization"));assertEquals("tenant-a",seen.getRequestHeaders().getFirst("X-DKP-Tenant"));assertNull(seen.getRequestHeaders().getFirst("Cookie"));assertNull(seen.getRequestHeaders().getFirst("X-Actor"));
        body=eligible();assertEquals(1,client.discover(actor,List.of(id),deadline()).eligible().size());assertEquals("/api/v1/documents/knowledge-share-eligibility",seen.getRequestURI().toString());assertEquals("{\"documentIds\":[\""+id+"\"]}",new String(received,StandardCharsets.UTF_8));
        body=eligible().replace(row(),"");assertTrue(client.discover(actor,List.of(id),deadline()).eligible().isEmpty());
        failure(503,this::assess);body=strict();failure(503,()->client.discover(actor,List.of(id),deadline()));
        failure(409,()->client.assess(actor,List.of(new Expectation(id,8L)),deadline()));
        try(var lower=new FixedDocumentShareClient(base,tls,actor,"bearer original-token")){lower.assess(actor,List.of(new Expectation(id,null)),deadline());assertEquals("bearer original-token",seen.getRequestHeaders().getFirst("Authorization"));assertFalse(new String(received,StandardCharsets.UTF_8).contains("expectedDocumentRevision"));}
    }
    @Test void closedBindingsAndFiniteRowsRejectEveryMalformedOrPartialResponse() {
        for(String invalid:List.of("null","[]",strict()+"{}",strict().replace("\"tenant-a\"","\"tenant-b\""),strict().replace("\"staff\"","\"other\""),strict().replace("https://issuer.example","https://other.example"),strict().replace("document.share-to-collection","document.read"),strict().replace("\"assessments\":","\"extra\":true,\"assessments\":"),strict().replace(row(),""),strict().replace(row(),row()+","+row()),strict().replace("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002"),strict().replace("\"documentRevision\":7","\"documentRevision\":7.0"),strict().replace("\"documentRevision\":7","\"documentRevision\":-1"),strict().replace("\"documentRevision\":7","\"documentRevision\":9007199254740992"),strict().replace("development-restricted","public"),strict().replace("\"classificationPolicyRevision\":1","\"classificationPolicyRevision\":2"),strict().replace("\"documentRevision\":7","\"documentRevision\":\"7\""),strict().replace("\"documentRevision\":7","\"documentRevision\":7,\"documentRevision\":7"),strict().replace("\"documentId\":","\"unknown\":true,\"documentId\":"),"x".repeat(32769))) {body=invalid;failure(503,this::assess);}
        body=eligible().replace(BatchEvidence.requestedDigest(List.of(id)),"0".repeat(64));failure(503,()->client.discover(actor,List.of(id),deadline()));
        body=eligible().replace(id.toString(),"00000000-0000-0000-0000-000000000002");failure(503,()->client.discover(actor,List.of(id),deadline()));
        body=strict().replace("["+row()+"]","{}");failure(503,this::assess);
        body=strict().replace("["+row()+"]","["+String.join(",",Collections.nCopies(101,row()))+"]");failure(503,this::assess);
        body=strict().replace("\"subject\":\"staff\"","\"subject\":\"\"");failure(503,this::assess);
        body=strict();contentType="text/plain";failure(503,this::assess);contentType="application/json";compressed=true;failure(503,this::assess);
    }
    @Test void statusMappingNeverFollowsRedirectsOrExposesProviderBody() {
        for(int value:List.of(403,404)){status=value;failure(404,this::assess);}
        status=409;failure(409,this::assess);
        for(int value:List.of(201,301,302,307,400,401,422,429,500,503)){status=value;failure(503,this::assess);}
    }
    @Test void actualPeerStatusIsLoggedWithoutBodyAndMappingsRemainUnchanged(){
        var logger=java.util.logging.Logger.getLogger("org.documentknowledge.knowledgebase.failure");
        var lines=new ArrayList<String>();var thrown=new ArrayList<Throwable>();
        var handler=new java.util.logging.Handler(){public void publish(java.util.logging.LogRecord record){if(record.getMessage().startsWith("kb_share_response ")){lines.add(record.getMessage());thrown.add(record.getThrown());}}public void flush(){}public void close(){}};
        boolean parents=logger.getUseParentHandlers();var level=logger.getLevel();logger.setUseParentHandlers(false);logger.setLevel(java.util.logging.Level.ALL);logger.addHandler(handler);
        try{
            body="synthetic-private-token tenant document https://private.invalid/\nforged-log";
            status=404;failure(404,this::assess);status=409;failure(409,this::assess);
            status=503;failure(503,this::assess);status=418;failure(503,this::assess);
            assertEquals(List.of("kb_share_response status=NOT_FOUND","kb_share_response status=CONFLICT","kb_share_response status=UNAVAILABLE","kb_share_response status=OTHER"),lines);
            assertTrue(thrown.stream().allMatch(Objects::isNull));
            status=200;body=strict();assess();assertEquals(4,lines.size());
        }finally{logger.removeHandler(handler);logger.setUseParentHandlers(parents);logger.setLevel(level);}
    }
    @Test void malformedLocalInputCannotSendAndDeadlineCoversResponseBody() {
        failure(503,()->client.discover(new Context(actor.actor(),"tenant-b"),List.of(id),deadline()));
        failure(503,()->client.discover(actor,List.of(),deadline()));failure(503,()->client.discover(actor,List.of(id,id),deadline()));
        failure(503,()->client.discover(actor,Collections.nCopies(101,id),deadline()));assertNull(seen);
        failure(503,()->client.assess(actor,List.of(new Expectation(id,-1L)),deadline()));assertNull(seen);
        stall=true;long before=System.nanoTime();failure(503,()->client.assess(actor,List.of(new Expectation(id,null)),new Deadline(System::nanoTime,Duration.ofMillis(250))));assertTrue(System.nanoTime()-before<Duration.ofSeconds(2).toNanos());
        release.countDown();server.stop(0);failure(503,this::assess);
    }
    @Test void operationalProbeIsFixedCredentialFreeAndRequiresClosedFreshUp() {
        var readiness=new FixedDocumentShareReadiness(base,tls);body="{\"status\":\"UP\"}";assertTrue(readiness.ready());
        assertEquals("GET",seen.getRequestMethod());assertEquals("/health/readiness",seen.getRequestURI().toString());assertEquals(0,received.length);
        assertNull(seen.getRequestHeaders().getFirst("Authorization"));assertNull(seen.getRequestHeaders().getFirst("X-DKP-Tenant"));assertNull(seen.getRequestHeaders().getFirst("Cookie"));
        for(String invalid:List.of("null","[]","{}","{\"status\":\"DOWN\"}","{\"status\":\"UP\",\"tenant\":\"secret\"}","{\"status\":\"UP\",\"status\":\"UP\"}","{\"status\":\"UP\"}{}","x".repeat(2049))) {body=invalid;assertFalse(readiness.ready());}
        body="{\"status\":\"UP\"}";status=503;assertFalse(readiness.ready());status=302;assertFalse(readiness.ready());status=200;assertTrue(readiness.ready());
        compressed=true;assertFalse(readiness.ready());compressed=false;contentType="text/plain";assertFalse(readiness.ready());
    }
    @Test void operationalProbeIncludesCompleteBodyInFiveHundredMillisecondBudget() {
        body="{\"status\":\"UP\"}";stall=true;long before=System.nanoTime();assertFalse(new FixedDocumentShareReadiness(base,tls).ready());assertTrue(System.nanoTime()-before<Duration.ofSeconds(2).toNanos());
        release.countDown();server.stop(0);assertFalse(new FixedDocumentShareReadiness(base,tls).ready());
    }
    @Test void operationalProbeRejectsWrongCaHostnameAndUnconfiguredOrArbitraryOrigins()throws Exception {
        body="{\"status\":\"UP\"}";
        assertFalse(new FixedDocumentShareReadiness(base,SSLContext.getDefault()).ready());
        assertFalse(new FixedDocumentShareReadiness(URI.create("https://127.0.0.1:"+server.getAddress().getPort()),tls).ready());
        for(String origin:List.of("http://localhost","https://localhost/path","https://user@localhost","https://localhost?x=1","https://localhost#x")) assertFalse(new FixedDocumentShareReadiness(URI.create(origin),tls).ready());
        assertFalse(FixedDocumentShareReadiness.connect(null,ca).ready());
        assertFalse(FixedDocumentShareReadiness.connect(base,ca.resolveSibling("missing.crt")).ready());
        var production=FixedDocumentShareReadiness.connect(base,ca);
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            assertTrue(production.ready());String original=Files.readString(ca);try{ca.toFile().setWritable(true);Files.writeString(ca,"malformed");assertFalse(production.ready());}finally{Files.writeString(ca,original);Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-------"));}assertTrue(production.ready());
        } else assertFalse(production.ready());
    }
    @Test void wrongCaHostnameHttpAndEscapedOriginsNeverReachAuthorizedEndpoint()throws Exception {
        failure(503,()->new FixedDocumentShareClient(URI.create("http://localhost"),tls,actor,"Bearer verified-token"));
        for(String origin:List.of("https://user@localhost","https://localhost/path","https://localhost?target=x","https://localhost#fragment")) failure(503,()->new FixedDocumentShareClient(URI.create(origin),tls,actor,"Bearer verified-token"));
        for(String bearer:List.of("","Basic x","Bearer a\r\nInjected: yes"))failure(503,()->new FixedDocumentShareClient(base,tls,actor,bearer));
        var wrong=new FixedDocumentShareClient(base,SSLContext.getDefault(),actor,"Bearer verified-token");failure(503,()->wrong.assess(actor,List.of(new Expectation(id,null)),deadline()));
        var hostname=new FixedDocumentShareClient(URI.create("https://127.0.0.1:"+server.getAddress().getPort()),tls,actor,"Bearer verified-token");failure(503,()->hostname.assess(actor,List.of(new Expectation(id,null)),deadline()));assertNull(seen);
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) assertEquals(7,FixedDocumentShareClient.connect(base,ca,actor,"Bearer verified-token").assess(actor,List.of(new Expectation(id,null)),deadline()).assessments().getFirst().documentRevision());
        else failure(503,()->FixedDocumentShareClient.connect(base,ca,actor,"Bearer verified-token"));
        failure(503,()->FixedDocumentShareClient.connect(base,ca.resolveSibling("missing.crt"),actor,"Bearer verified-token"));
    }
}
