package org.documentknowledge.browserbff.bootstrap;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.browserbff.adapters.outbound.FixedKnowledgeGateway;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeGatewayTest {
 private static final String ID="11111111-1111-4111-8111-111111111111",PATH="/api/v1/knowledge/collections/"+ID;
 private static final BrowserIdentity ACTOR=new BrowserIdentity("https://issuer.example","staff","Synthetic",List.of("tenant-a"),true,"synthetic-access",Instant.now().plusSeconds(120));
 @Test void rejectsDiagnosticsRedirectsMalformedUnknownOverlargeAndMisboundUpstreamResponses()throws Exception{
  var code=new AtomicInteger(200);var body=new AtomicReference<>("{\"collectionId\":\""+ID+"\",\"revision\":1}");var type=new AtomicReference<>("application/json");var cache=new AtomicReference<>("no-store");var encoding=new AtomicReference<String>();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type.get());exchange.getResponseHeaders().set("Cache-Control",cache.get());if(encoding.get()!=null)exchange.getResponseHeaders().set("Content-Encoding",encoding.get());exchange.sendResponseHeaders(code.get(),bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}});server.start();
  try(var context=ApplicationContext.run()){
   var gateway=new FixedKnowledgeGateway("http://127.0.0.1:"+server.getAddress().getPort(),context.getBean(JsonMapper.class),new KnowledgeWireFactory().wire());var command=Map.<String,Object>of("expectedRevision",0,"metadata",Map.of("title","Synthetic","description",""));
   assertEquals(200,gateway.exchange("PATCH",PATH,Map.of(),command,"key",ACTOR,"tenant-a",new KnowledgeDeadline()).status());
   for(String invalid:List.of("{}","null","{\"code\":\"UNAVAILABLE\"}","{\"collectionId\":\""+ID+"\",\"revision\":1,\"provider\":\"diagnostic\"}","{\"collectionId\":\""+ID+"\",\"revision\":1,\"revision\":2}","{\"collectionId\":\"22222222-2222-4222-8222-222222222222\",\"revision\":1}","{\"collectionId\":\""+ID+"\",\"revision\":1} {}","x".repeat(2097153))){body.set(invalid);assertEquals(503,assertThrows(BffFailure.class,()->gateway.exchange("PATCH",PATH,Map.of(),command,"key",ACTOR,"tenant-a",new KnowledgeDeadline())).status());}
   body.set("{\"code\":\"UNAVAILABLE\"}");code.set(503);assertEquals(503,gateway.exchange("GET",PATH,Map.of(),null,null,ACTOR,"tenant-a",new KnowledgeDeadline()).status());
   for(int status:List.of(302,500,202)){code.set(status);assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,ACTOR,"tenant-a",new KnowledgeDeadline()));}
   code.set(503);body.set("{\"code\":\"PRIVATE_DIAGNOSTIC\"}");assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,ACTOR,"tenant-a",new KnowledgeDeadline()));body.set("{\"code\":\"UNAVAILABLE\"}");
   type.set("text/html");assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,ACTOR,"tenant-a",new KnowledgeDeadline()));type.set("application/json");cache.set("public");assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,ACTOR,"tenant-a",new KnowledgeDeadline()));cache.set("no-store");encoding.set("gzip");assertThrows(BffFailure.class,()->gateway.exchange("GET",PATH,Map.of(),null,null,ACTOR,"tenant-a",new KnowledgeDeadline()));
   for(String invalid:List.of("","https://user:password@example.test","https://example.test/path","https://example.test?override=1","https://example.test#fragment","file:///tmp/no")){var bad=new FixedKnowledgeGateway(invalid,context.getBean(JsonMapper.class),new KnowledgeWireFactory().wire());assertThrows(BffFailure.class,()->bad.exchange("GET",PATH,Map.of(),null,null,ACTOR,"tenant-a",new KnowledgeDeadline()));}
  }finally{server.stop(0);}
 }
 @Test void cancellationOwnsThePendingTransportAndRejectsLateCompletion()throws Exception{
  var accepted=new CountDownLatch(1);var release=new CountDownLatch(1);var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{accepted.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
  try(var context=ApplicationContext.run();var executor=Executors.newSingleThreadExecutor()){
   var gateway=new FixedKnowledgeGateway("http://127.0.0.1:"+server.getAddress().getPort(),context.getBean(JsonMapper.class),new KnowledgeWireFactory().wire());var owner=new KnowledgeDeadline();var result=executor.submit(()->gateway.exchange("GET",PATH,Map.of(),null,null,ACTOR,"tenant-a",owner));assertTrue(accepted.await(2,TimeUnit.SECONDS));owner.cancel();ExecutionException error=assertThrows(ExecutionException.class,()->result.get(1,TimeUnit.SECONDS));assertInstanceOf(BffFailure.class,error.getCause());
  }finally{release.countDown();server.stop(0);}
 }
}
