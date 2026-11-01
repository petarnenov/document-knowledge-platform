package org.documentknowledge.documentmanagement.bootstrap;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Local wire fixture for bounded health parser only. Runtime inspector constructor enforces HTTPS. */
class ProviderHealthTest {
 @Test void onlyExactSuccessfulHealthCanPassAndStalledBodiesAreBounded()throws Exception{
  var status=new AtomicInteger(200);var type=new AtomicReference<>("application/json");var body=new AtomicReference<>("{\"status\":\"UP\"}");var stall=new AtomicBoolean();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
  server.createContext("/health/readiness",exchange->{exchange.getResponseHeaders().set("Content-Type",type.get());byte[] bytes=body.get().getBytes(StandardCharsets.US_ASCII);exchange.sendResponseHeaders(status.get(),bytes.length);try{if(stall.get())Thread.sleep(4000);exchange.getResponseBody().write(bytes);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
  try{
   var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/health/readiness");var connections=new UploadRuntime.RealConnections();var tls=SSLContext.getDefault();
   assertTrue(connections.health(endpoint,tls));status.set(503);assertFalse(connections.health(endpoint,tls));status.set(200);type.set("text/html");assertFalse(connections.health(endpoint,tls));type.set("application/json");body.set("{\"status\":\"DOWN\"}");assertFalse(connections.health(endpoint,tls));body.set("x".repeat(65));assertThrows(Exception.class,()->connections.health(endpoint,tls));body.set("{\"status\":\"UP\"}");stall.set(true);assertThrows(Exception.class,()->connections.health(endpoint,tls));
  }finally{server.stop(0);executor.shutdownNow();}
 }
}
