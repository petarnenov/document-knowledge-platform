package org.documentknowledge.browserbff.bootstrap;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
class IngressMetadataLimitTest {
 @Test void semanticMetadataLimitSurvivesLargerFrameworkWindowForFixedAndChunkedBodies()throws Exception {
  var calls=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);
  upstream.createContext("/api/v1/documents",e->{calls.incrementAndGet();e.getRequestBody().readAllBytes();byte[] result=("{\"documentId\":\""+UploadHttpTest.ID+"\",\"revision\":1}").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(201,result.length);try(var out=e.getResponseBody()){out.write(result);}});upstream.start();
  try(var client=HttpClient.newHttpClient();var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.bff.portal-origin",UploadHttpTest.ORIGIN,"dkp.bff.upstreams.dm","http://127.0.0.1:"+upstream.getAddress().getPort()))) {
   var fixture=new UploadHttpTest();String cookie="__Host-DKP-SESSION="+fixture.seed(server).getId();
   String create=server.getApplicationContext().getBean(io.micronaut.json.JsonMapper.class).writeValueAsString(DocumentGatewayTest.CREATE);
   for(boolean chunked:new boolean[]{false,true})for(String path:new String[]{"/api/v1/dm/documents","/api/v1/session/tenant","/api/v1/session/logout"}) {
    String json=path.endsWith("tenant")?"{\"tenantId\":\"tenant-a\"}":path.endsWith("logout")?"{}":create;
    byte[] oversized=(json+" ".repeat(16385-json.getBytes(StandardCharsets.UTF_8).length)).getBytes(StandardCharsets.UTF_8);
    int before=calls.get();var body=chunked?HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(oversized)):HttpRequest.BodyPublishers.ofByteArray(oversized);
    var response=fixture.call(client,server,"POST",path,cookie,"application/json",body,true);
    fixture.assertCode(response,413,"PAYLOAD_TOO_LARGE");assertEquals(before,calls.get());
   }
   byte[] atLimit=(create+" ".repeat(16384-create.getBytes(StandardCharsets.UTF_8).length)).getBytes(StandardCharsets.UTF_8);
   for(boolean chunked:new boolean[]{false,true}) {
    var body=chunked?HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(atLimit)):HttpRequest.BodyPublishers.ofByteArray(atLimit);
    assertEquals(201,fixture.call(client,server,"POST","/api/v1/dm/documents",cookie,"application/json",body,true).statusCode());
   }
   assertEquals(2,calls.get());
   for(boolean chunked:new boolean[]{false,true})for(String path:new String[]{"/api/v1/session/tenant","/api/v1/session/logout"}) {
    String json=path.endsWith("tenant")?"{\"tenantId\":\"tenant-a\"}":"{}";
    byte[] atSessionLimit=(json+" ".repeat(16384-json.length())).getBytes(StandardCharsets.UTF_8);
    String freshCookie="__Host-DKP-SESSION="+fixture.seed(server).getId();
    var body=chunked?HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(atSessionLimit)):HttpRequest.BodyPublishers.ofByteArray(atSessionLimit);
    assertEquals(path.endsWith("tenant")?200:204,fixture.call(client,server,"POST",path,freshCookie,"application/json",body,true).statusCode());
   }

   fixture.assertCode(fixture.call(client,server,"POST","/api/v1/session/logout",null,"application/json",HttpRequest.BodyPublishers.ofString(" ".repeat(16385)),true),401,"AUTHENTICATION_REQUIRED");
   fixture.assertCode(fixture.call(client,server,"POST","/api/v1/session/logout",cookie,"application/json",HttpRequest.BodyPublishers.ofString(" ".repeat(16385)),false),403,"REQUEST_DENIED");
  }finally{upstream.stop(0);}
 }
}
