package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IngressLateBytesTest {
    @Test void rejectedPartialBodiesCannotTurnLateBytesIntoASecondProtectedOperation()throws Exception {
        for(int expected:List.of(400,401,403)) {
            var calls=new AtomicInteger();var bytes=new AtomicInteger();
            var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);
            upstream.createContext("/api/v1/documents",exchange->{calls.incrementAndGet();bytes.addAndGet(exchange.getRequestBody().readAllBytes().length);
                byte[] result=("{\"uploadId\":\""+UploadHttpTest.ID+"\"}").getBytes(StandardCharsets.US_ASCII);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);try(var out=exchange.getResponseBody()){out.write(result);}});upstream.start();
            try(var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.bff.portal-origin",UploadHttpTest.ORIGIN,"dkp.bff.upstreams.dm","http://127.0.0.1:"+upstream.getAddress().getPort()));
                var socket=new Socket("127.0.0.1",server.getPort())) {
                socket.setSoTimeout(3000);var fixture=new UploadHttpTest();var session=fixture.seed(server);
                String cookie="__Host-DKP-SESSION="+session.getId();String path=UploadHttpTest.BASE+"/uploads/"+UploadHttpTest.ID+"/content";
                String first=headers(path,expected==401?null:cookie,expected==403?"incorrect":UploadHttpTest.CSRF,8,expected==400);
                IngressChannelPolicyTest.send(socket,first+"x");
                var input=new BufferedInputStream(socket.getInputStream());var rejected=response(input);assertNotNull(rejected);assertEquals(expected,rejected.status());assertEquals(0,calls.get());
                // Only the seven outstanding body bytes precede a new independently guarded request.
                try { IngressChannelPolicyTest.send(socket,"xxxxxxx"+headers(path,cookie,UploadHttpTest.CSRF,1,false)+"z"); }
                catch(SocketException closed) { assertEquals(0,calls.get());continue; }
                var next=response(input);
                if(next==null) {assertEquals(0,calls.get());}
                else {assertEquals(200,next.status());assertEquals(1,calls.get());assertEquals(1,bytes.get());}
            }finally{upstream.stop(0);}
        }
    }
    static String headers(String path,String cookie,String csrf,int length,boolean encoded) {
        return "PUT "+path+" HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream\r\nContent-Length: "+length+"\r\nOrigin: "+UploadHttpTest.ORIGIN+"\r\nX-CSRF-TOKEN: "+csrf+"\r\nIdempotency-Key: synthetic-late\r\n"+(cookie==null?"":"Cookie: "+cookie+"\r\n")+(encoded?"Content-Encoding: gzip\r\n":"")+"\r\n";
    }
    record Result(int status) {}
    static Result response(InputStream input)throws Exception {
        String status=line(input);if(status==null)return null;assertTrue(status.startsWith("HTTP/1.1 "));
        int length=-1;boolean chunked=false;String header;
        while(!(header=Objects.requireNonNull(line(input))).isEmpty()) {
            if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).strip());
            if(header.equalsIgnoreCase("transfer-encoding: chunked"))chunked=true;
        }
        if(chunked) {int n;while((n=Integer.parseInt(Objects.requireNonNull(line(input)),16))>0){assertEquals(n,input.readNBytes(n).length);assertEquals("",line(input));}assertEquals("",line(input));}
        else {assertTrue(length>=0&&length<=16384);assertEquals(length,input.readNBytes(length).length);}
        return new Result(Integer.parseInt(status.split(" ")[1]));
    }
    static String line(InputStream input)throws IOException {
        var bytes=new ByteArrayOutputStream();int b;
        while((b=input.read())!=-1){if(b=='\n')return bytes.toString(StandardCharsets.US_ASCII).stripTrailing();bytes.write(b);if(bytes.size()>16384)throw new IOException("TEST_HEADER_BOUND");}
        if(bytes.size()==0)return null;throw new EOFException("Truncated HTTP line");
    }
}
