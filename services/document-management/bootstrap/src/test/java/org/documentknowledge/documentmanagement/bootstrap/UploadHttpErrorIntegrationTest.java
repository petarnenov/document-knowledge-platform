package org.documentknowledge.documentmanagement.bootstrap;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Actual framework/security filters; external membership fails before any repository access. */
class UploadHttpErrorIntegrationTest {
 @Test void currentUnavailableMembershipAndFramework413UseExactFrozenCodes()throws Exception{
  var security=new SecurityIntegrationTest();var settings=security.settings("external");
  try(var client=HttpClient.newHttpClient();var server=ApplicationContext.run(EmbeddedServer.class,settings)){
   String token=security.token(security.claims()),id=UUID.randomUUID().toString();
   var upload=client.send(HttpRequest.newBuilder(server.getURI().resolve("/api/v1/documents/uploads/"+id+"/status")).header("Authorization","Bearer "+token).header("X-DKP-Tenant","tenant-a").GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(503,upload.statusCode(),upload.body());assertEquals("{\"code\":\"UNAVAILABLE\"}",upload.body());
   // A finite accepted representation must reach current authority even when
   // the dependency is unavailable; content negotiation must not hide the route.
   var mimes=List.of("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
   for(String accept:mimes){
    var content=client.send(HttpRequest.newBuilder(server.getURI().resolve("/api/v1/documents/"+id+"/versions/"+id+"/content")).header("Authorization","Bearer "+token).header("X-DKP-Tenant","tenant-a").header("Accept",accept).GET().build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(503,content.statusCode(),"Accepted representation must reach current authority: "+accept+" "+content.body());assertEquals("{\"code\":\"UNAVAILABLE\"}",content.body());
   }
   var metadata=client.send(HttpRequest.newBuilder(server.getURI().resolve("/api/v1/documents/"+id)).header("Authorization","Bearer "+token).header("X-DKP-Tenant","tenant-a").GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(503,metadata.statusCode());assertEquals("{\"code\":\"AUTHORITY_UNAVAILABLE\"}",metadata.body());
   var absent=client.send(HttpRequest.newBuilder(server.getURI().resolve("/api/v1/documents/uploads/"+id+"/status")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,absent.statusCode());assertEquals("{\"code\":\"UNAUTHENTICATED\"}",absent.body());
  }
  settings.put("micronaut.server.max-request-size",64);
  try(var client=HttpClient.newHttpClient();var server=ApplicationContext.run(EmbeddedServer.class,settings)){
   var response=client.send(HttpRequest.newBuilder(server.getURI().resolve("/api/v1/documents/uploads/"+UUID.randomUUID()+"/content")).header("Authorization","Bearer "+security.token(security.claims())).header("X-DKP-Tenant","tenant-a").header("Idempotency-Key","framework-limit").header("Content-Type","application/octet-stream").PUT(HttpRequest.BodyPublishers.ofString("x".repeat(65))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(413,response.statusCode(),response.body());assertEquals("{\"code\":\"PAYLOAD_TOO_LARGE\"}",response.body());
  }
 }
}
