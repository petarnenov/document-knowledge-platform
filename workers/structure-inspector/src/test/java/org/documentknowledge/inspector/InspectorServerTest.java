package org.documentknowledge.inspector;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class InspectorServerTest {
    @TempDir Path temp;
    private TlsFixture tls;
    private InspectorServer server;
    private HttpClient client;
    private String base;
    private final byte[] key=new byte[32];
    @BeforeEach void start() throws Exception {
        tls=new TlsFixture(temp);Path spool=Files.createDirectory(temp.resolve("spool"));
        server=new InspectorServer(new InetSocketAddress("127.0.0.1",0),tls.server,key,new StructureInspector(spool));server.start();base="https://localhost:"+server.port();
        client=HttpClient.newBuilder().sslContext(tls.client).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterEach void close(){client.shutdownNow();server.close();}
    private HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5));}
    private HttpResponse<String> post(String path,String auth,String media,String format,byte[] bytes) throws Exception {
        var request=request(path);if(auth!=null)request.header("Authorization",auth);if(media!=null)request.header("Content-Type",media);if(format!=null)request.header("X-DKP-Format",format);return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String auth(){return "Bearer "+Base64.getEncoder().encodeToString(key);}
    @Test void realTlsFourFormatsProduceExactlyClosedIndependentEvidence() throws Exception {
        for(Format format:Format.values()) {
            byte[] bytes=format==Format.PDF?Fixtures.pdf(1):Fixtures.zip(Fixtures.office(format));var response=post("/internal/v1/inspect",auth(),"application/octet-stream",format.name(),bytes);assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            var json=JsonMapper.builder().build().readTree(response.body());Set<String> fields=new HashSet<>();json.fieldNames().forEachRemaining(fields::add);assertEquals(Set.of("schemaVersion","format","mimeType","sizeBytes","sha256","pageCount","inspectorVersion","profileDigest"),fields);assertEquals(bytes.length,json.get("sizeBytes").longValue());assertEquals(StructureInspector.PROFILE_DIGEST,json.get("profileDigest").textValue());
        }
        for(String path:List.of("/health/readiness","/health/liveness")){var response=client.send(request(path).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertEquals("{\"status\":\"UP\"}",response.body());}
    }
    @Test void authFormatMethodPathMediaAndUnknownSelectorFailClosed() throws Exception {
        byte[] pdf=Fixtures.pdf(1);
        assertThrows(IllegalArgumentException.class,()->new InspectorServer(new InetSocketAddress("127.0.0.1",0),tls.server,new byte[31],new StructureInspector(temp)));
        assertEquals(404,client.send(request("/internal/v1/inspect").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400,post("/internal/v1/inspect",auth(),"application/octet-stream",null,pdf).statusCode());
        assertEquals(415,post("/internal/v1/inspect",auth(),null,"PDF",pdf).statusCode());
        for(String bad:Arrays.asList(null,"Bearer wrong"))assertEquals(401,post("/internal/v1/inspect",bad,"application/octet-stream","PDF",pdf).statusCode());
        assertEquals(415,post("/internal/v1/inspect",auth(),"application/pdf","PDF",pdf).statusCode());assertEquals(400,post("/internal/v1/inspect",auth(),"application/octet-stream","DOCM",pdf).statusCode());assertEquals(400,post("/internal/v1/inspect?url=evil",auth(),"application/octet-stream","PDF",pdf).statusCode());assertEquals(404,post("/other",auth(),"application/octet-stream","PDF",pdf).statusCode());
        var duplicate=request("/internal/v1/inspect").header("Authorization",auth()).header("Authorization",auth()).header("Content-Type","application/octet-stream").header("X-DKP-Format","PDF").POST(HttpRequest.BodyPublishers.ofByteArray(pdf));assertEquals(400,client.send(duplicate.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var forged=request("/internal/v1/inspect").header("Authorization",auth()).header("Content-Type","application/octet-stream").header("X-DKP-Format","PDF").header("X-DKP-Path","/etc/passwd").POST(HttpRequest.BodyPublishers.ofByteArray(pdf));assertEquals(400,client.send(forged.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(422,post("/internal/v1/inspect",auth(),"application/octet-stream","PDF","%PDF-malformed".getBytes()).statusCode());assertEquals(400,post("/internal/v1/inspect",auth(),"application/octet-stream","PDF",new byte[0]).statusCode());
        try(var untrusted=HttpClient.newHttpClient()){assertThrows(javax.net.ssl.SSLHandshakeException.class,()->untrusted.send(request("/health/liveness").GET().build(),HttpResponse.BodyHandlers.ofString()));}
    }
    private javax.net.ssl.SSLSocket partialRequest(byte[] pdf) throws Exception {
        var socket=(javax.net.ssl.SSLSocket)tls.client.getSocketFactory().createSocket("localhost",server.port());socket.setSoTimeout(5000);var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);socket.startHandshake();
        String headers="POST /internal/v1/inspect HTTP/1.1\r\nHost: localhost\r\nAuthorization: "+auth()+"\r\nContent-Type: application/octet-stream\r\nX-DKP-Format: PDF\r\nContent-Length: "+pdf.length+"\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(pdf,0,1);socket.getOutputStream().flush();return socket;
    }
    private void finish(javax.net.ssl.SSLSocket socket,byte[] pdf) throws Exception {
        socket.getOutputStream().write(pdf,1,pdf.length-1);socket.getOutputStream().flush();byte[] response=socket.getInputStream().readNBytes(8193);assertTrue(response.length<=8192);assertTrue(new String(response,java.nio.charset.StandardCharsets.UTF_8).startsWith("HTTP/1.1 200"));
    }
    @Test void inspectorAdmissionHasNoUnboundedQueueAndHealthStaysResponsive() throws Exception {
        byte[] pdf=Fixtures.pdf(1);
        try(var socket=partialRequest(pdf)) {
            long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();boolean entered=false;
            do{try(var paths=Files.list(temp.resolve("spool"))){entered=paths.findAny().isPresent();}if(!entered)Thread.sleep(10);}while(!entered&&System.nanoTime()<deadline);assertTrue(entered,"first parser owns a bounded private spool");
            assertEquals(503,post("/internal/v1/inspect",auth(),"application/octet-stream","PDF",pdf).statusCode());assertEquals(200,client.send(request("/health/liveness").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());finish(socket,pdf);
        }
    }
    @Test void hardDeadlineWatchdogFiresForUnresponsiveBodyWithoutRuntimeOverride() throws Exception {
        server.close();var expired=new CountDownLatch(1);
        server=new InspectorServer(new InetSocketAddress("127.0.0.1",0),tls.server,key,new StructureInspector(temp.resolve("spool")),Duration.ofMillis(20),expired::countDown);server.start();base="https://localhost:"+server.port();
        byte[] pdf=Fixtures.pdf(1);try(var socket=partialRequest(pdf)){assertTrue(expired.await(2,TimeUnit.SECONDS));finish(socket,pdf);}
    }
}
