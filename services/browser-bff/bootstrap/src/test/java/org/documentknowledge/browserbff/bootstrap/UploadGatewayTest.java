package org.documentknowledge.browserbff.bootstrap;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.net.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.browserbff.adapters.outbound.*;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadGatewayTest {
 static final String ID=DocumentGatewayTest.ID,BASE="/api/v1/documents",TRANSFER=BASE+"/uploads/"+ID+"/content",CONTENT=BASE+"/"+ID+"/versions/"+ID+"/content";
 final BrowserIdentity identity=new BrowserIdentity("https://issuer.example","user","Synthetic",List.of("tenant-a"),true,"synthetic-token",Instant.now().plusSeconds(300));
 @Test void boundedTransferAndExactContentReconstructOnlyTrustedHeaders()throws Exception{
  var status=new AtomicInteger(200);var format=new AtomicReference<>("application/pdf");var bytes=new AtomicReference<>(new byte[]{1,2,3,4});var range=new AtomicReference<String>();var error=new AtomicReference<>("NOT_FOUND");var received=new AtomicInteger();var override=new AtomicReference<Map<String,String>>(Map.of());
  var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);
  upstream.createContext(BASE,exchange->{assertEquals("Bearer synthetic-token",exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("tenant-a",exchange.getRequestHeaders().getFirst("X-DKP-Tenant"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Origin"));assertNull(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));byte[] body;
   if(exchange.getRequestMethod().equals("PUT")){assertEquals("application/octet-stream",exchange.getRequestHeaders().getFirst("Content-Type"));assertEquals("key",exchange.getRequestHeaders().getFirst("Idempotency-Key"));received.addAndGet(exchange.getRequestBody().readAllBytes().length);exchange.getResponseHeaders().set("Content-Type","application/json");body=(status.get()>=400?"{\"code\":\""+error.get()+"\"}":"{\"uploadId\":\""+ID+"\"}").getBytes();}
   else if(status.get()>=400){exchange.getResponseHeaders().set("Content-Type","application/json");body=("{\"code\":\""+error.get()+"\"}").getBytes();}
   else{assertEquals(Set.of("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),new HashSet<>(Arrays.asList(exchange.getRequestHeaders().getFirst("Accept").split(", "))));exchange.getResponseHeaders().set("Content-Type",format.get());String extension=switch(format.get()){case"application/pdf"->"pdf";case"application/vnd.openxmlformats-officedocument.wordprocessingml.document"->"docx";case"application/vnd.openxmlformats-officedocument.presentationml.presentation"->"pptx";case"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"->"xlsx";default->"bin";};exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+ID+"."+extension+"\"");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Accept-Ranges","bytes");if(range.get()!=null)exchange.getResponseHeaders().set("Content-Range",range.get());body=bytes.get();}
   override.get().forEach((k,v)->exchange.getResponseHeaders().set(k,v));exchange.sendResponseHeaders(status.get(),body.length);try(var output=exchange.getResponseBody()){output.write(body);}});upstream.start();
  try(var context=ApplicationContext.run()){
   var gateway=new FixedUploadGateway("http://127.0.0.1:"+upstream.getAddress().getPort(),context.getBean(JsonMapper.class));var first=new AtomicInteger();
   assertEquals(Map.of("uploadId",ID),gateway.transfer(TRANSFER,"key",new ByteArrayInputStream(new byte[]{1,2,3,4}),4,identity,"tenant-a",first::incrementAndGet).body());assertEquals(1,first.get());assertEquals(4,received.get());assertEquals(200,gateway.transfer(TRANSFER,"key",new ByteArrayInputStream(new byte[]{1,2,3,4}),-1,identity,"tenant-a",()->{}).status());
   status.set(429);error.set("QUOTA_EXCEEDED");assertEquals(429,gateway.transfer(TRANSFER,"key",new ByteArrayInputStream(new byte[]{1,2,3,4}),4,identity,"tenant-a",()->{}).status());status.set(201);assertEquals("UNAVAILABLE",assertThrows(BffFailure.class,()->gateway.transfer(TRANSFER,"key",new ByteArrayInputStream(new byte[]{1,2,3,4}),4,identity,"tenant-a",()->{})).code());status.set(200);
   assertEquals(401,assertThrows(BffFailure.class,()->gateway.transfer(TRANSFER,"key",new ByteArrayInputStream(new byte[]{1,2,3,4}),4,identity,"tenant-a",()->{throw new BffFailure(401,"UNAUTHENTICATED");})).status());
   assertEquals(422,assertThrows(BffFailure.class,()->gateway.transfer(TRANSFER,"key",new ByteArrayInputStream(new byte[]{1,2}),4,identity,"tenant-a",()->{})).status());
   for(String mime:List.of("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")){format.set(mime);try(var result=gateway.download(CONTENT,null,identity,"tenant-a")){assertEquals(200,result.status());assertEquals(4,result.length());assertArrayEquals(bytes.get(),result.body().readAllBytes());}}
   format.set("application/pdf");status.set(206);range.set("bytes 1-2/4");bytes.set(new byte[]{2,3});try(var result=gateway.download(CONTENT,"bytes=1-2",identity,"tenant-a")){assertEquals(206,result.status());assertEquals("bytes 1-2/4",result.contentRange());assertArrayEquals(new byte[]{2,3},result.body().readAllBytes());}
   assertEquals(503,assertThrows(BffFailure.class,()->gateway.download(CONTENT,null,identity,"tenant-a")).status());range.set("bytes 0-3/4");assertEquals(503,assertThrows(BffFailure.class,()->gateway.download(CONTENT,"bytes=1-2",identity,"tenant-a")).status());
   // Every accepted range form is correlated with the exact returned object bounds.
   for(String wanted:List.of("bytes=1-", "bytes=-3")){range.set("bytes 1-3/4");bytes.set(new byte[]{2,3,4});try(var result=gateway.download(CONTENT,wanted,identity,"tenant-a")){assertArrayEquals(new byte[]{2,3,4},result.body().readAllBytes());}}
   for(String malformed:List.of("wrong", "bytes 3-1/4", "bytes 0-4/4", "bytes 1-2/100000001", "bytes 1-1/4", "bytes 999999999999999999999-2/4")){range.set(malformed);assertThrows(BffFailure.class,()->gateway.download(CONTENT,"bytes=1-2",identity,"tenant-a"));}
   range.set("bytes 1-3/4");for(String malformed:List.of("bytes=-0","bytes=0-","bytes=1-999999999999999999999","invalid")){assertThrows(BffFailure.class,()->gateway.download(CONTENT,malformed,identity,"tenant-a"));}
   status.set(200);range.set(null);bytes.set(new byte[]{1,2,3,4});
   for(var invalid:List.of(Map.of("Content-Disposition","inline; filename=evil.pdf"),Map.of("Content-Encoding","gzip"),Map.of("Cache-Control","public"),Map.of("X-Content-Type-Options","unsafe"),Map.of("Accept-Ranges","none"),Map.of("Content-Range","bytes 0-3/4"))){override.set(invalid);assertThrows(BffFailure.class,()->gateway.download(CONTENT,null,identity,"tenant-a"));}override.set(Map.of());
   assertThrows(BffFailure.class,()->gateway.transfer(TRANSFER,null,InputStream.nullInputStream(),1,identity,"tenant-a",()->{}));
   assertThrows(BffFailure.class,()->gateway.transfer(TRANSFER,"bad key",InputStream.nullInputStream(),1,identity,"tenant-a",()->{}));
   for(long length:List.of(0L,-2L)){assertThrows(BffFailure.class,()->gateway.transfer(TRANSFER,"key",InputStream.nullInputStream(),length,identity,"tenant-a",()->{}));}
   assertThrows(BffFailure.class,()->gateway.transfer(TRANSFER,"key",null,1,identity,"tenant-a",()->{}));
   status.set(404);error.set("NOT_FOUND");assertEquals("NOT_FOUND",assertThrows(BffFailure.class,()->gateway.download(CONTENT,null,identity,"tenant-a")).code());error.set("diagnostics-secret");assertEquals("UNAVAILABLE",assertThrows(BffFailure.class,()->gateway.download(CONTENT,null,identity,"tenant-a")).code());
   status.set(200);range.set(null);format.set("text/html");assertEquals(503,assertThrows(BffFailure.class,()->gateway.download(CONTENT,null,identity,"tenant-a")).status());
   assertThrows(BffFailure.class,()->gateway.transfer(TRANSFER,"key",InputStream.nullInputStream(),100000001,identity,"tenant-a",()->{}));assertThrows(BffFailure.class,()->gateway.transfer(BASE+"/evil","key",InputStream.nullInputStream(),1,identity,"tenant-a",()->{}));assertThrows(BffFailure.class,()->gateway.download(BASE+"/evil",null,identity,"tenant-a"));
   for(String invalid:List.of("", "relative", "ftp://host", "http://user:secret@host", "http://host/path", "http://host?query", "http://host#fragment")){var broken=new FixedUploadGateway(invalid,context.getBean(JsonMapper.class));assertEquals(503,assertThrows(BffFailure.class,()->broken.download(CONTENT,null,identity,"tenant-a")).status());}
  }finally{upstream.stop(0);}
 }
}
