package org.documentknowledge.browserbff.bootstrap;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.browserbff.adapters.inbound.VerifiedOpenIdMapper;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadHttpTest {
 static final String ID=DocumentGatewayTest.ID,BASE="/api/v1/dm/documents",ORIGIN="https://localhost:18443",CSRF=".fixture-csrf";
 @Test void realBrowserBoundaryGuardsChunkedTransferDiscoveryAndRangeContent()throws Exception{
  var calls=new AtomicInteger();var received=new AtomicInteger();var contentMime=new AtomicReference<>("application/pdf");var retired=new AtomicBoolean();var current=new AtomicReference<Session>();
  var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);upstream.createContext("/api/v1/documents",exchange->{
   calls.incrementAndGet();assertEquals("Bearer synthetic-access",exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("tenant-a",exchange.getRequestHeaders().getFirst("X-DKP-Tenant"));for(String name:List.of("Cookie","Origin","X-CSRF-TOKEN","X-User-Id"))assertNull(exchange.getRequestHeaders().getFirst(name));
   String path=exchange.getRequestURI().getPath();int status=200;byte[] bytes;
   if(exchange.getRequestMethod().equals("PUT")){received.addAndGet(exchange.getRequestBody().readAllBytes().length);bytes=("{\"uploadId\":\""+ID+"\"}").getBytes(StandardCharsets.US_ASCII);}
   else if(path.endsWith("/status")){bytes=("{\"uploadId\":\""+ID+"\",\"state\":\"VERIFIED\",\"expiresAt\":\"2026-10-31T23:00:00Z\",\"failureCode\":null}").getBytes(StandardCharsets.US_ASCII);}
   else if(path.endsWith("/complete")){bytes=("{\"uploadId\":\""+ID+"\",\"receiptId\":\""+ID+"\"}").getBytes(StandardCharsets.US_ASCII);}
   else if(path.endsWith("/version")){bytes=("{\"documentId\":\""+ID+"\",\"versionId\":\""+ID+"\"}").getBytes(StandardCharsets.US_ASCII);}
   else if(path.endsWith("/content")){bytes=new byte[]{1,2,3,4};if(exchange.getRequestHeaders().getFirst("Range")!=null){status=206;bytes=new byte[]{2,3};exchange.getResponseHeaders().set("Content-Range","bytes 1-2/4");}exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+ID+"."+(contentMime.get().equals("application/pdf")?"pdf":contentMime.get().contains("wordprocessing")?"docx":contentMime.get().contains("presentation")?"pptx":"xlsx")+"\"");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Accept-Ranges","bytes");}
   else{status=201;bytes=("{\"uploadId\":\""+ID+"\"}").getBytes(StandardCharsets.US_ASCII);}
   exchange.getResponseHeaders().set("Content-Type",path.endsWith("/content")&&exchange.getRequestMethod().equals("GET")?contentMime.get():"application/json");exchange.getResponseHeaders().set("Location","https://untrusted.example/permanent-link");if(retired.get())current.get().clear();exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
  });upstream.start();
  try(var client=HttpClient.newHttpClient();var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.bff.portal-origin",ORIGIN,"dkp.bff.upstreams.dm","http://127.0.0.1:"+upstream.getAddress().getPort()))){
   current.set(seed(server));String cookie="__Host-DKP-SESSION="+current.get().getId(),slot=BASE+"/uploads/"+ID;
   assertCode(call(client,server,"GET",slot+"/status",null,null,null,false),401,"UNAUTHENTICATED");
   int before=calls.get();assertCode(call(client,server,"PUT",slot+"/content",cookie,"application/octet-stream",HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}),false),403,"OPERATION_DENIED");assertEquals(before,calls.get());
   String initialize="{\"documentId\":\""+ID+"\",\"expectedRevision\":0,\"format\":\"PDF\",\"sizeBytes\":4}";
   var initialized=call(client,server,"POST",BASE+"/uploads",cookie,"application/json",HttpRequest.BodyPublishers.ofString(initialize),true);assertEquals(201,initialized.statusCode(),initialized.body());assertEquals("{\"uploadId\":\""+ID+"\"}",initialized.body());
   var transfer=call(client,server,"PUT",slot+"/content",cookie,"application/octet-stream",HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(new byte[]{1,2,3,4})),true);assertEquals(200,transfer.statusCode(),transfer.body());assertEquals(4,received.get());
   assertEquals(200,call(client,server,"POST",slot+"/complete",cookie,"application/json",HttpRequest.BodyPublishers.ofString("{}"),true).statusCode());
   assertEquals(200,call(client,server,"GET",slot+"/status",cookie,null,null,false).statusCode());assertEquals(200,call(client,server,"GET",BASE+"/"+ID+"/upload-receipts/"+ID+"/version",cookie,null,null,false).statusCode());
   String content=BASE+"/"+ID+"/versions/"+ID+"/content";var unknownContent=client.send(HttpRequest.newBuilder(server.getURI().resolve(content)).header("Accept","application/pdf").GET().build(),HttpResponse.BodyHandlers.ofString());assertCode(unknownContent,401,"UNAUTHENTICATED");
   var request=HttpRequest.newBuilder(server.getURI().resolve(content)).header("Cookie",cookie).header("Accept","application/pdf").GET().build();var full=client.send(request,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,full.statusCode());assertArrayEquals(new byte[]{1,2,3,4},full.body());assertEquals("4",full.headers().firstValue("Content-Length").orElseThrow());assertEquals("application/pdf",full.headers().firstValue("Content-Type").orElseThrow());assertEquals("no-store",full.headers().firstValue("Cache-Control").orElseThrow());assertTrue(full.headers().firstValue("Location").isEmpty());
   var range=client.send(HttpRequest.newBuilder(server.getURI().resolve(content)).header("Cookie",cookie).header("Range","bytes=1-2").header("Accept","application/pdf").GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(206,range.statusCode());assertArrayEquals(new byte[]{2,3},range.body());assertEquals("bytes 1-2/4",range.headers().firstValue("Content-Range").orElseThrow());
   for(String mime:List.of("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")){contentMime.set(mime);var downloaded=client.send(HttpRequest.newBuilder(server.getURI().resolve(content)).header("Cookie",cookie).header("Accept",mime).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,downloaded.statusCode());assertEquals(mime,downloaded.headers().firstValue("Content-Type").orElseThrow());assertArrayEquals(new byte[]{1,2,3,4},downloaded.body());}contentMime.set("application/pdf");
   before=calls.get();assertCode(call(client,server,"PUT",slot+"/content",cookie,"text/html",HttpRequest.BodyPublishers.ofString("1234"),true),415,"UNSUPPORTED_MEDIA_TYPE");assertCode(call(client,server,"POST",slot+"/complete",cookie,"application/json",HttpRequest.BodyPublishers.ofString("{\"extra\":true}"),true),400,"INVALID_REQUEST");assertCode(call(client,server,"POST",slot+"/complete",cookie,"application/json",HttpRequest.BodyPublishers.ofString("{\"x\":1,\"x\":2}"),true),400,"INVALID_REQUEST");assertCode(call(client,server,"GET",slot+"/status?target=evil",cookie,null,null,false),400,"INVALID_REQUEST");assertEquals(before,calls.get());
   assertCode(call(client,server,"POST",BASE+"/uploads",cookie,"application/json",HttpRequest.BodyPublishers.ofString(" ".repeat(16384)+"{}"),true),413,"PAYLOAD_TOO_LARGE");
   for(String header:List.of("Origin","X-CSRF-TOKEN","Idempotency-Key")){var duplicate=HttpRequest.newBuilder(server.getURI().resolve(slot+"/content")).header("Cookie",cookie).header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).header("Idempotency-Key","key").header(header,"duplicate").header("Content-Type","application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1,2,3,4})).build();before=calls.get();assertTrue(client.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode()>=400);assertEquals(before,calls.get());}
   before=calls.get();
   assertCode(call(client,server,"POST",slot+"/complete",cookie,"text/html",HttpRequest.BodyPublishers.ofString("{}"),true),415,"UNSUPPORTED_MEDIA_TYPE");
   for(String invalid:List.of("[]","null","{} {}")){assertCode(call(client,server,"POST",slot+"/complete",cookie,"application/json",HttpRequest.BodyPublishers.ofString(invalid),true),400,"INVALID_REQUEST");}
   assertCode(call(client,server,"PUT",slot+"/content",cookie,"application/octet-stream",HttpRequest.BodyPublishers.noBody(),true),400,"INVALID_REQUEST");
   var gzipBytes=new ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(gzipBytes)){gzip.write(new byte[]{1,2,3,4});}
   for(int repetition=0;repetition<4;repetition++)for(byte[] encodedBody:List.of("1234".getBytes(StandardCharsets.US_ASCII),gzipBytes.toByteArray())){
    var encoded=HttpRequest.newBuilder(server.getURI().resolve(slot+"/content")).header("Cookie",cookie).header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).header("Idempotency-Key","key").header("Content-Type","application/octet-stream").header("Content-Encoding","gzip").PUT(HttpRequest.BodyPublishers.ofByteArray(encodedBody)).build();assertCode(client.send(encoded,HttpResponse.BodyHandlers.ofString()),400,"INVALID_REQUEST");assertEquals(before,calls.get(),"Encoded bytes never reach the upstream");
   }
   var duplicateRange=HttpRequest.newBuilder(server.getURI().resolve(content)).header("Cookie",cookie).header("Range","bytes=0-1").header("Range","bytes=1-2").header("Accept","application/pdf").GET().build();assertCode(client.send(duplicateRange,HttpResponse.BodyHandlers.ofString()),400,"INVALID_REQUEST");
   assertEquals(before,calls.get());
   retired.set(true);assertCode(call(client,server,"GET",slot+"/status",cookie,null,null,false),401,"UNAUTHENTICATED");
  }finally{upstream.stop(0);}
 }
 @SuppressWarnings("unchecked")Session seed(EmbeddedServer server)throws Exception{SessionStore<Session> store=server.getApplicationContext().getBean(SessionStore.class);Session session=store.newSession();var identity=new BrowserIdentity("https://issuer.example","staff-1","Synthetic",List.of("tenant-a"),true,"synthetic-access",Instant.now().plusSeconds(300));session.put(SecurityFilter.AUTHENTICATION,Authentication.build("staff-1",Map.of(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE,identity)));session.put("csrfToken",CSRF);session.put("dkp.selected.tenant","tenant-a");store.save(session).get();return session;}
 java.net.http.HttpResponse<String> call(HttpClient client,EmbeddedServer server,String method,String path,String cookie,String type,HttpRequest.BodyPublisher body,boolean guarded)throws Exception{var request=HttpRequest.newBuilder(server.getURI().resolve(path)).timeout(java.time.Duration.ofSeconds(10));if(cookie!=null)request.header("Cookie",cookie);if(type!=null)request.header("Content-Type",type);if(guarded)request.header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).header("Idempotency-Key","key");request.header("X-DKP-Tenant","forged").header("X-User-Id","forged");return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():body).build(),java.net.http.HttpResponse.BodyHandlers.ofString());}
 void assertCode(java.net.http.HttpResponse<String> response,int status,String code){assertEquals(status,response.statusCode(),response.body());assertEquals("{\"code\":\""+code+"\"}",response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());}
}
