package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class IngressBinaryBoundsTest {
    @Test void fixedAndChunkedBodiesPreserveOneSixteenKiBAndHundredMillionExactByteBounds()throws Exception {
        IngressFailureWitness.failure.set(null);
        var count=new AtomicLong();var receivedHash=new AtomicReference<String>();var calls=new AtomicInteger();
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);
        upstream.createContext("/api/v1/documents",exchange->{try {
            calls.incrementAndGet();var digest=MessageDigest.getInstance("SHA-256");long bytes=0;byte[] buffer=new byte[8192];int n;
            try(var input=exchange.getRequestBody()){while((n=input.read(buffer))!=-1){bytes+=n;digest.update(buffer,0,n);}}
            count.set(bytes);receivedHash.set(HexFormat.of().formatHex(digest.digest()));
            byte[] response=("{\"uploadId\":\""+UploadHttpTest.ID+"\"}").getBytes(StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            try(var output=exchange.getResponseBody()){output.write(response);}
        }catch(Exception failure){exchange.close();}});upstream.start();
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            var server=ApplicationContext.run(EmbeddedServer.class,Map.of("dkp338.failure-witness","true","micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.bff.portal-origin",UploadHttpTest.ORIGIN,"dkp.bff.upstreams.dm","http://127.0.0.1:"+upstream.getAddress().getPort()))) {
            var fixture=new UploadHttpTest();var session=fixture.seed(server);String cookie="__Host-DKP-SESSION="+session.getId();
            for(long size:List.of(1L,16384L,16385L,100000000L))for(boolean chunked:List.of(false,true)) {
                var publisher=HttpRequest.BodyPublishers.ofInputStream(()->zeros(size));
                if(!chunked)publisher=HttpRequest.BodyPublishers.fromPublisher(publisher,size);
                var request=HttpRequest.newBuilder(server.getURI().resolve(UploadHttpTest.BASE+"/uploads/"+UploadHttpTest.ID+"/content"))
                    .timeout(Duration.ofSeconds(310)).header("Cookie",cookie).header("Origin",UploadHttpTest.ORIGIN)
                    .header("X-CSRF-TOKEN",UploadHttpTest.CSRF).header("Idempotency-Key","synthetic-bounds")
                    .header("Content-Type","application/octet-stream").PUT(publisher).build();
                var result=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,result.statusCode(),"size="+size+" chunked="+chunked);
                assertEquals(size,count.get());assertEquals(hashZeros(size),receivedHash.get());
            }
            assertEquals(8,calls.get());
            // Declared oversized length is rejected before any upstream request or full body allocation.
            var oversized=HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(()->zeros(100000001L)),100000001L);
            var rejected=client.send(HttpRequest.newBuilder(server.getURI().resolve(UploadHttpTest.BASE+"/uploads/"+UploadHttpTest.ID+"/content"))
                .timeout(Duration.ofSeconds(310)).header("Cookie",cookie).header("Origin",UploadHttpTest.ORIGIN)
                .header("X-CSRF-TOKEN",UploadHttpTest.CSRF).header("Idempotency-Key","synthetic-too-large")
                .header("Content-Type","application/octet-stream").PUT(oversized).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(413,rejected.statusCode());assertEquals(8,calls.get());
            var unknown=client.send(HttpRequest.newBuilder(server.getURI().resolve(UploadHttpTest.BASE+"/uploads/"+UploadHttpTest.ID+"/content"))
                .timeout(Duration.ofSeconds(310)).header("Cookie",cookie).header("Origin",UploadHttpTest.ORIGIN)
                .header("X-CSRF-TOKEN",UploadHttpTest.CSRF).header("Idempotency-Key","synthetic-unknown-too-large")
                .header("Content-Type","application/octet-stream").PUT(HttpRequest.BodyPublishers.ofInputStream(()->zeros(100000001L))).build(),HttpResponse.BodyHandlers.ofString());
            boolean typedLimit=false;
            for(Throwable cause=IngressFailureWitness.failure.get();cause!=null;cause=cause.getCause())
                if(cause instanceof io.micronaut.http.exceptions.ContentLengthExceededException)typedLimit=true;
            assertTrue(typedLimit,"Actual framework body stream must expose the typed total-length cause");
            assertEquals(413,unknown.statusCode(),"Unknown-length max+1 must not become a generic dependency failure");

        } finally {upstream.stop(0);}
    }
    static InputStream zeros(long size) { return new InputStream(){long left=size;
        @Override public int read(){if(left==0)return -1;left--;return 0;}
        @Override public int read(byte[] b,int offset,int length){if(left==0)return -1;int n=(int)Math.min(length,left);Arrays.fill(b,offset,offset+n,(byte)0);left-=n;return n;}
    };}
    static String hashZeros(long size)throws Exception {var digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[8192];while(size>0){int n=(int)Math.min(size,bytes.length);digest.update(bytes,0,n);size-=n;}return HexFormat.of().formatHex(digest.digest());}
}
