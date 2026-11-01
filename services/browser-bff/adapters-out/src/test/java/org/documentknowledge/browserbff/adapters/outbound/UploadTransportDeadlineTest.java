package org.documentknowledge.browserbff.adapters.outbound;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.net.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadTransportDeadlineTest {
 static final String ID="11111111-1111-4111-8111-111111111111",CONTENT="/api/v1/documents/"+ID+"/versions/"+ID+"/content";
 @Test void actualHttpHeaderTimeoutAndBodyIdleCannotRetainAnUnboundedRead()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);var headers=new java.util.concurrent.atomic.AtomicBoolean();
  server.createContext("/api/v1/documents",exchange->{try{if(headers.get()){exchange.getResponseHeaders().set("Content-Type","application/pdf");exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+ID+".pdf\"");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Accept-Ranges","bytes");exchange.sendResponseHeaders(200,4);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();}Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
  try(var context=ApplicationContext.run()){
   var json=context.getBean(JsonMapper.class);var gateway=new FixedUploadGateway("http://127.0.0.1:"+server.getAddress().getPort(),json,400,80);var identity=new BrowserIdentity("https://issuer.example","user","Synthetic",List.of("tenant-a"),true,"synthetic-token",Instant.now().plusSeconds(300));
   assertEquals("UNAVAILABLE",assertThrows(BffFailure.class,()->gateway.download(CONTENT,null,identity,"tenant-a")).code());headers.set(true);try(var result=gateway.download(CONTENT,null,identity,"tenant-a")){assertThrows(IOException.class,()->result.body().readAllBytes());}
   assertThrows(IllegalArgumentException.class,()->new FixedUploadGateway("",json,300001,30));assertThrows(IllegalArgumentException.class,()->new FixedUploadGateway("",json,1,30001));
  }finally{server.stop(0);executor.shutdownNow();}
 }
}
