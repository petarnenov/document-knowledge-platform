package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;
import org.documentknowledge.inspector.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.ArtifactSource;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedInspectionAdapterTest {
    @TempDir Path temp;
    private SSLContext serverTls,clientTls;
    private ArtifactSource source;
    private static final String IMAGE="sha256:"+"1".repeat(64);
    @BeforeEach void setup() throws Exception {
        Path store=temp.resolve("server.p12");String password="synthetic-test-only";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","worker","-keyalg","EC","-groupname","secp256r1","-validity","2","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-noprompt").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();assertTrue(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,password.toCharArray());}var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());serverTls=SSLContext.getInstance("TLS");serverTls.init(km.getKeyManagers(),null,null);
        var trust=KeyStore.getInstance("PKCS12");trust.load(null,null);trust.setCertificateEntry("worker",keys.getCertificate("worker"));var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);clientTls=SSLContext.getInstance("TLS");clientTls.init(null,tm.getTrustManagers(),null);
        byte[] bytes=docx();Path path=temp.resolve("source.docx");Files.write(path,bytes);source=new ArtifactSource(path,UploadFormat.DOCX,bytes.length,StandardDefinitions.hex(StandardDefinitions.sha().digest(bytes)));
    }
    private byte[] docx() throws Exception {
        Map<String,String> xml=Map.of("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>","_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>","word/document.xml","<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p/></w:body></w:document>");
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var entry:xml.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}}return out.toByteArray();
    }
    private URI endpoint(int port){return URI.create("https://localhost:"+port+"/internal/v1/inspect");}
    private IsolatedInspectionAdapter adapter(int port){return new IsolatedInspectionAdapter(endpoint(port),clientTls,new byte[32],StructureInspector.PROFILE_DIGEST,IMAGE,VerificationFixtures.CLOCK);}
    @Test void realIsolatedTlsInspectorIndependentlyBindsBytesProfileAndTime() throws Exception {
        try(var worker=new InspectorServer(new InetSocketAddress("127.0.0.1",0),serverTls,new byte[32],new StructureInspector(temp))) {
            worker.start();try(var adapter=adapter(worker.port())){var evidence=adapter.inspect(source);assertEquals(source.format(),evidence.format());assertEquals(source.sha256(),evidence.sha256());assertEquals(source.sizeBytes(),evidence.sizeBytes());assertEquals(source.format().mime(),evidence.mimeType());assertNull(evidence.pageCount());assertEquals(IMAGE,evidence.imageDigest());assertEquals(StructureInspector.PROFILE_DIGEST,evidence.profileDigest());assertEquals(VerificationFixtures.CLOCK.instant(),evidence.inspectedAt());}
        }
    }
    @Test void wrongCaKeyProfileSourceAndUnavailableWorkerAreNotEvidence() throws Exception {
        try(var worker=new InspectorServer(new InetSocketAddress("127.0.0.1",0),serverTls,new byte[32],new StructureInspector(temp))) {
            worker.start();byte[] wrongKey=new byte[32];wrongKey[0]=1;
            try(var wrong=new IsolatedInspectionAdapter(endpoint(worker.port()),clientTls,wrongKey,StructureInspector.PROFILE_DIGEST,IMAGE,VerificationFixtures.CLOCK)){assertThrows(DmFailure.class,()->wrong.inspect(source));}
            try(var wrong=new IsolatedInspectionAdapter(endpoint(worker.port()),SSLContext.getDefault(),new byte[32],StructureInspector.PROFILE_DIGEST,IMAGE,VerificationFixtures.CLOCK)){assertThrows(DmFailure.class,()->wrong.inspect(source));}
            try(var wrong=new IsolatedInspectionAdapter(endpoint(worker.port()),clientTls,new byte[32],"0".repeat(64),IMAGE,VerificationFixtures.CLOCK)){assertThrows(DmFailure.class,()->wrong.inspect(source));}
            try(var adapter=adapter(worker.port())) {
                assertThrows(DmFailure.class,()->adapter.inspect(new ArtifactSource(source.path(),source.format(),source.sizeBytes(),"0".repeat(64))));assertThrows(DmFailure.class,()->adapter.inspect(new ArtifactSource(source.path(),source.format(),source.sizeBytes()+1,source.sha256())));
                Files.writeString(source.path(),"malformed body");var bad=new ArtifactSource(source.path(),source.format(),14,StandardDefinitions.hex(StandardDefinitions.sha().digest("malformed body".getBytes())));assertEquals(422,assertThrows(DmFailure.class,()->adapter.inspect(bad)).status());
            }
        }
        for(String uri:List.of("http://localhost/internal/v1/inspect","https://user@localhost/internal/v1/inspect","https://localhost/internal/v1/inspect?url=x","https://localhost/internal/v1/inspect#fragment","https://localhost/other","https:/internal/v1/inspect"))assertThrows(IllegalArgumentException.class,()->new IsolatedInspectionAdapter(URI.create(uri),clientTls,new byte[32],StructureInspector.PROFILE_DIGEST,IMAGE,VerificationFixtures.CLOCK));
        assertThrows(IllegalArgumentException.class,()->new IsolatedInspectionAdapter(endpoint(8443),clientTls,new byte[31],StructureInspector.PROFILE_DIGEST,IMAGE,VerificationFixtures.CLOCK));
        assertThrows(IllegalArgumentException.class,()->new IsolatedInspectionAdapter(endpoint(8443),clientTls,new byte[32],"invalid",IMAGE,VerificationFixtures.CLOCK));
        assertThrows(IllegalArgumentException.class,()->new IsolatedInspectionAdapter(endpoint(8443),clientTls,new byte[32],StructureInspector.PROFILE_DIGEST,"invalid",VerificationFixtures.CLOCK));
    }
    @Test void closedMalformedDuplicateOversizeAndConfusedResponseFactsFailClosed() throws Exception {
        HttpsServer fake=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),1);fake.setHttpsConfigurator(new HttpsConfigurator(serverTls));var response=new java.util.concurrent.atomic.AtomicReference<>("{}");var status=new java.util.concurrent.atomic.AtomicInteger(200);
        fake.createContext("/internal/v1/inspect",exchange->{try(var input=exchange.getRequestBody()){input.transferTo(OutputStream.nullOutputStream());}byte[] bytes=response.get().getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});fake.start();
        try(var adapter=adapter(fake.getAddress().getPort())) {
            for(String invalid:List.of("{}","[]","null","{\"schemaVersion\":1,\"schemaVersion\":1}","{}{}","x".repeat(4097))) {response.set(invalid);assertEquals(503,assertThrows(DmFailure.class,()->adapter.inspect(source)).status());}
            var base=new HashMap<String,Object>();base.put("schemaVersion",1);base.put("format","DOCX");base.put("mimeType",source.format().mime());base.put("sizeBytes",source.sizeBytes());base.put("sha256",source.sha256());base.put("pageCount",null);base.put("inspectorVersion","1");base.put("profileDigest",StructureInspector.PROFILE_DIGEST);
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            for(var bad:List.of(Map.entry("schemaVersion",(Object)4294967297L),Map.entry("schemaVersion",(Object)0),Map.entry("schemaVersion",(Object)"1"),Map.entry("format",(Object)"PDF"),Map.entry("format",(Object)false),Map.entry("mimeType",(Object)"application/pdf"),Map.entry("sha256",(Object)"0".repeat(64)),Map.entry("profileDigest",(Object)"0".repeat(64)),Map.entry("inspectorVersion",(Object)"2"),Map.entry("sizeBytes",(Object)(source.sizeBytes()+1)),Map.entry("sizeBytes",(Object)0.5),Map.entry("pageCount",(Object)0.5),Map.entry("pageCount",(Object)2147483648L),Map.entry("pageCount",(Object)1))) {var facts=new HashMap<>(base);facts.put(bad.getKey(),bad.getValue());response.set(json.writeValueAsString(facts));assertEquals(503,assertThrows(DmFailure.class,()->adapter.inspect(source)).status());}
            var unknown=new HashMap<>(base);unknown.put("bucket","forged");response.set(json.writeValueAsString(unknown));assertEquals(503,assertThrows(DmFailure.class,()->adapter.inspect(source)).status());
            response.set("{\"code\":\"INVALID_FILE\"}");status.set(422);assertEquals(422,assertThrows(DmFailure.class,()->adapter.inspect(source)).status());status.set(302);assertEquals(503,assertThrows(DmFailure.class,()->adapter.inspect(source)).status());
        } finally {fake.stop(0);}
    }
    @Test void responseHeadersCannotDisableTheCompleteBodyDeadlineOrLeaveAnUnboundedWait() throws Exception {
        var sent=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        HttpsServer fake=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),1);fake.setHttpsConfigurator(new HttpsConfigurator(serverTls));var executor=java.util.concurrent.Executors.newSingleThreadExecutor();fake.setExecutor(executor);
        fake.createContext("/internal/v1/inspect",exchange->{try {try(var input=exchange.getRequestBody()){input.transferTo(OutputStream.nullOutputStream());}exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();sent.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);exchange.getResponseBody().write('}');}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(IOException expectedCanceledTransport){}finally{exchange.close();}});fake.start();
        try(var adapter=new IsolatedInspectionAdapter(endpoint(fake.getAddress().getPort()),clientTls,new byte[32],StructureInspector.PROFILE_DIGEST,IMAGE,VerificationFixtures.CLOCK,java.time.Duration.ofSeconds(1))) {
            long start=System.nanoTime();var failure=assertThrows(DmFailure.class,()->adapter.inspect(source));assertEquals(503,failure.status());assertEquals(0,sent.getCount(),"actual response headers and partial body were delivered before the deadline");assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).compareTo(java.time.Duration.ofSeconds(3))<0,"body timeout must not wait for the stalled peer");
        }finally{release.countDown();fake.stop(0);executor.shutdownNow();}
        for(var duration:List.of(java.time.Duration.ZERO,java.time.Duration.ofSeconds(-1),java.time.Duration.ofSeconds(61)))assertThrows(IllegalArgumentException.class,()->new IsolatedInspectionAdapter(endpoint(8443),clientTls,new byte[32],StructureInspector.PROFILE_DIGEST,IMAGE,VerificationFixtures.CLOCK,duration));
    }

}
