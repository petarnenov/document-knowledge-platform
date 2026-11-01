package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Genuine local Micronaut BFF HTTP and fixed gateway, controlled HTTP upstream, no SQL/S3/provider.
 * Payload is byte-identical to upload-job-fixture.ts sourcePdf(), not a reduced surrogate.
 */
class BoundedIngressLargeTransferTest {
 static byte[] sourcePdf(){
  String content="%"+"Public synthetic scan work ".repeat(1_200_000)+"\n";
  String[] objects={"<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 4 0 R >>","<< /Length "+content.length()+" >>\nstream\n"+content+"endstream"};
  StringBuilder text=new StringBuilder("%PDF-1.7\n");List<Integer> offsets=new ArrayList<>();
  for(int i=0;i<objects.length;i++){offsets.add(text.length());text.append(i+1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");}
  int xref=text.length();text.append("xref\n0 5\n0000000000 65535 f \n");for(int offset:offsets)text.append(String.format(Locale.ROOT,"%010d 00000 n \n",offset));
  text.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");return text.toString().getBytes(StandardCharsets.US_ASCII);
 }
 @Test void actualLargeFixedAndChunkedBrowserBodiesTraverseProductionBffBoundaryWithExactBytes()throws Exception{
  byte[] bytes=sourcePdf();assertTrue(bytes.length>32_400_000&&bytes.length<32_401_000);String expectedHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));assertEquals("6b1abc529fbcb3560a2285064c9a49c5e56b26d5f31e83190f75e00b38faebf3",expectedHash);
  var calls=new AtomicInteger();var received=new AtomicLong();var hash=new AtomicReference<String>();var authorization=new AtomicReference<String>();var tenant=new AtomicReference<String>();
  var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);
  upstream.createContext("/api/v1/documents",exchange->{try{
   calls.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));tenant.set(exchange.getRequestHeaders().getFirst("X-DKP-Tenant"));
   var digest=MessageDigest.getInstance("SHA-256");long count=0;byte[] buffer=new byte[65536];try(var in=exchange.getRequestBody()){int n;while((n=in.read(buffer))!=-1){count+=n;digest.update(buffer,0,n);}}
   received.set(count);hash.set(HexFormat.of().formatHex(digest.digest()));byte[] response=("{\"uploadId\":\""+UploadHttpTest.ID+"\"}").getBytes(StandardCharsets.US_ASCII);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}
  }catch(Exception failure){exchange.close();throw new RuntimeException(failure);}});upstream.start();
  try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.bff.portal-origin",UploadHttpTest.ORIGIN,"dkp.bff.upstreams.dm","http://127.0.0.1:"+upstream.getAddress().getPort()))){
   var fixture=new UploadHttpTest();var session=fixture.seed(server);String cookie="__Host-DKP-SESSION="+session.getId();String route=UploadHttpTest.BASE+"/uploads/"+UploadHttpTest.ID+"/content";
   assertEquals(401,fixture.call(client,server,"PUT",route,null,"application/octet-stream",HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}),true).statusCode());
   assertEquals(403,fixture.call(client,server,"PUT",route,cookie,"application/octet-stream",HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}),false).statusCode());assertEquals(0,calls.get());
   for(boolean chunked:List.of(false,true)){
    var publisher=HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(bytes));
    if(!chunked)publisher=HttpRequest.BodyPublishers.fromPublisher(publisher,bytes.length);
    var request=HttpRequest.newBuilder(server.getURI().resolve(route)).timeout(Duration.ofSeconds(310))
      .header("Cookie",cookie).header("Origin",UploadHttpTest.ORIGIN).header("X-CSRF-TOKEN",UploadHttpTest.CSRF)
      .header("Idempotency-Key","synthetic-transfer").header("Content-Type","application/octet-stream").PUT(publisher).build();
    var result=client.send(request,HttpResponse.BodyHandlers.ofString());
    assertEquals(200,result.statusCode(),"chunked="+chunked+" calls="+calls.get()+" received="+received.get());
    assertEquals(bytes.length,received.get());assertEquals(expectedHash,hash.get());
    assertEquals("Bearer synthetic-access",authorization.get());assertEquals("tenant-a",tenant.get());

   }
   assertEquals(2,calls.get());
   assertEquals(401,fixture.call(client,server,"PUT",route,null,"application/octet-stream",HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}),true).statusCode());
   assertEquals(403,fixture.call(client,server,"PUT",route,cookie,"application/octet-stream",HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}),false).statusCode());assertEquals(2,calls.get());
  }finally{upstream.stop(0);}
 }
}
