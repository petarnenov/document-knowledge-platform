package org.documentknowledge.loggingclient;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class JdkLogTransportTest {
    @TempDir static Path temporary;
    private static SSLContext tls;
    private static final String TOKEN=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
    private HttpsServer server;
    private ExecutorService threads;
    @BeforeAll static void certificate() throws Exception {
        Path store=temporary.resolve("tls.p12");String password=UUID.randomUUID().toString();
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/keytool").toString(),"-genkeypair","-alias","local",
            "-keyalg","RSA","-keysize","2048","-validity","1","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1",
            "-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password)
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        var keys=KeyStore.getInstance("PKCS12");try(var stream=Files.newInputStream(store)) {keys.load(stream,password.toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
    }
    private URI start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),8);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        threads=Executors.newFixedThreadPool(2);server.setExecutor(threads);server.createContext("/",handler);server.start();
        return URI.create("https://localhost:"+server.getAddress().getPort()+"/api/v1/dev-logs");
    }
    @AfterEach void stop() {if(server!=null)server.stop(0);if(threads!=null)threads.shutdownNow();}

    @Test void fixedAuthenticatedRoutesAndNoRedirects() throws Exception {
        var requests=new CopyOnWriteArrayList<String>();
        var endpoint=start(exchange->{
            try(exchange) {
                requests.add(exchange.getRequestURI().getPath());
                assertEquals("POST",exchange.getRequestMethod());assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("application/json",exchange.getRequestHeaders().getFirst("Content-Type"));
                assertEquals("{}",new String(exchange.getRequestBody().readNBytes(32769),StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(exchange.getRequestURI().getPath().endsWith("/receipts"))exchange.getResponseHeaders().set("Location","/redirected");
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/receipts")?302:200,2);
                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            }
        });
        var transport=new JdkLogTransport(endpoint,TOKEN,tls);
        try {
            var first=transport.post(LogTransport.Route.BATCHES,"{}".getBytes(StandardCharsets.UTF_8));assertEquals(200,first.status());
            byte[] body=first.body();body[0]=0;assertEquals('{',first.body()[0]);
            assertEquals(302,transport.post(LogTransport.Route.RECEIPTS,"{}".getBytes(StandardCharsets.UTF_8)).status());
            assertEquals(List.of("/api/v1/dev-logs/batches","/api/v1/dev-logs/receipts"),requests);
            assertThrows(IOException.class,()->transport.post(LogTransport.Route.BATCHES,new byte[32769]));
            assertThrows(IOException.class,()->transport.post(LogTransport.Route.BATCHES,new byte[0]));
            assertThrows(IOException.class,()->transport.post(LogTransport.Route.BATCHES,null));
        } finally {transport.close();transport.close();}
        assertThrows(IOException.class,()->transport.post(LogTransport.Route.BATCHES,new byte[]{1}));
        for(String invalid:List.of("http://localhost/api/v1/dev-logs","https://user:secret@localhost/api/v1/dev-logs",
                "https://localhost/api/v1/dev-logs?other=true","https://localhost/api/v1/dev-logs#fragment","https://localhost/other"))
            assertThrows(IllegalArgumentException.class,()->new JdkLogTransport(URI.create(invalid),TOKEN,tls));
        assertThrows(IllegalArgumentException.class,()->new JdkLogTransport(endpoint,"unsafe\r\nvalue",tls));
    }

    @Test void realDelayedHttpsDoesNotBlockProducerAndCommitsOnlyAfterReply() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        URI endpoint=start(exchange->{
            try(exchange) {
                Map<?,?> batch=JsonMapper.builder().build().readValue(exchange.getRequestBody().readNBytes(32769),Map.class);
                var events=((List<?>)batch.get("events")).stream().map(row->new LogWire.Prepared(UUID.fromString((String)((Map<?,?>)row).get("eventId")),new byte[0])).toList();
                entered.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS))return;}catch(InterruptedException stopped) {Thread.currentThread().interrupt();return;}
                byte[] reply=LogWireTest.receipt(events,"COMMITTED");exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
            }
        });
        var producer=new LogProducer(LogWireTest.EXECUTION,LogWireTest.VOCABULARY,new JdkLogTransport(endpoint,TOKEN,tls));
        try {
            producer.emit(LogWireTest.event(UUID.randomUUID()));assertTrue(entered.await(3,TimeUnit.SECONDS));
            var caller=CompletableFuture.supplyAsync(()->producer.emit(LogWireTest.event(UUID.randomUUID())));
            assertEquals(LogProducer.Admission.ACCEPTED,caller.get(1,TimeUnit.SECONDS));assertEquals(0,producer.status().committed());
            assertEquals(2,producer.status().pendingEvents());release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(producer.status().committed()!=2 && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(2,producer.status().committed());
        } finally {release.countDown();producer.close();assertTrue(producer.awaitClosed(Duration.ofSeconds(2)));}
    }

    @Test void untrustedTlsAndOversizedOrWronglyTypedResponsesFailVisibly() throws Exception {
        URI endpoint=start(exchange->{
            try(exchange) {
                byte[] bytes=new byte[32769];exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }
        });
        try(var trusted=new JdkLogTransport(endpoint,TOKEN,tls);var untrusted=new JdkLogTransport(endpoint,TOKEN,SSLContext.getDefault())) {
            assertThrows(IOException.class,()->trusted.post(LogTransport.Route.BATCHES,new byte[]{1}));
            assertThrows(IOException.class,()->untrusted.post(LogTransport.Route.BATCHES,new byte[]{1}));
        }
        server.stop(0);threads.shutdownNow();
        URI wrong=start(exchange->{try(exchange) {exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write(new byte[]{'{','}'});}});
        try(var transport=new JdkLogTransport(wrong,TOKEN,tls)) {assertThrows(IOException.class,()->transport.post(LogTransport.Route.BATCHES,new byte[]{1}));}
    }

    @Test void responseSubscriberCapsAllocationAndCancelsOverflow() {
        var requests=new AtomicInteger();var cancelled=new AtomicInteger();
        var subscription=new Flow.Subscription() {public void request(long count) {requests.incrementAndGet();}public void cancel() {cancelled.incrementAndGet();}};
        var body=new JdkLogTransport.LimitedBody();body.onSubscribe(subscription);body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[32768])));body.onComplete();
        assertEquals(32768,body.getBody().toCompletableFuture().join().length);assertEquals(2,requests.get());assertEquals(1,cancelled.get());
        body.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        var overflow=new JdkLogTransport.LimitedBody();overflow.onSubscribe(subscription);overflow.onNext(List.of(ByteBuffer.wrap(new byte[32769])));
        assertThrows(CompletionException.class,()->overflow.getBody().toCompletableFuture().join());assertEquals(2,cancelled.get());
        var error=new JdkLogTransport.LimitedBody();error.onError(new IOException("not logged"));error.onSubscribe(subscription);
        assertThrows(CompletionException.class,()->error.getBody().toCompletableFuture().join());assertEquals(3,cancelled.get());
    }
}
