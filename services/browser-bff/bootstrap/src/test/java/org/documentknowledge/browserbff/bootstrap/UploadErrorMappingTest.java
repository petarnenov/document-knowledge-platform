package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.http.*;
import java.util.*;
import org.documentknowledge.browserbff.adapters.inbound.SafeErrorResponses;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadErrorMappingTest {
 @Test void frameworkAndLegacyCodesMapOnlyOnApprovedUploadRoutes(){
  var filter=new SafeErrorResponses();String upload="/api/v1/dm/documents/uploads";
  for(var entry:Map.of(400,"INVALID_REQUEST",401,"UNAUTHENTICATED",403,"OPERATION_DENIED",404,"NOT_FOUND",405,"INVALID_REQUEST",413,"PAYLOAD_TOO_LARGE",415,"UNSUPPORTED_MEDIA_TYPE",503,"UNAVAILABLE",500,"UNAVAILABLE").entrySet()){
   var response=HttpResponse.status(HttpStatus.valueOf(entry.getKey())).body("framework diagnostics");filter.sanitize(HttpRequest.POST(upload,Map.of()),response);assertEquals(Map.of("code",entry.getValue()),response.body());assertEquals(entry.getKey()>=500?503:entry.getKey(),response.code());assertEquals(MediaType.APPLICATION_JSON_TYPE,response.getContentType().orElseThrow());
  }
  for(Object unsafe:List.of(Map.of("code","lowercase"),Map.of("code",42),Map.of("code","A".repeat(65)),Map.of("code","SAFE","detail","private"))){var response=HttpResponse.badRequest(unsafe);filter.sanitize(HttpRequest.POST(upload,Map.of()),response);assertEquals(Map.of("code","INVALID_REQUEST"),response.body());}
  var typed=HttpResponse.status(HttpStatus.CONFLICT).body(Map.of("code","UPLOAD_STATE_CONFLICT"));filter.sanitize(HttpRequest.POST(upload,Map.of()),typed);assertEquals(Map.of("code","UPLOAD_STATE_CONFLICT"),typed.body());
  var old=HttpResponse.unauthorized().body(Map.of("code","AUTHENTICATION_REQUIRED"));filter.sanitize(HttpRequest.GET("/api/v1/session"),old);assertEquals(Map.of("code","AUTHENTICATION_REQUIRED"),old.body());
  var oauth=HttpResponse.serverError("secret");filter.sanitize(HttpRequest.GET("/oauth/callback"),oauth);assertEquals(503,oauth.code());assertEquals(Map.of("code","DEPENDENCY_UNAVAILABLE"),oauth.body());
  var okay=HttpResponse.ok(Map.of("value","kept"));filter.sanitize(HttpRequest.GET(upload),okay);assertEquals(Map.of("value","kept"),okay.body());
 }
 @Test void actualFrameworkServerErrorNormalizesOnlyApprovedUploadRoutes()throws Exception{
  try(var server=io.micronaut.context.ApplicationContext.run(io.micronaut.runtime.server.EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.test.framework-error",true));var client=java.net.http.HttpClient.newHttpClient()){
   for(String path:List.of("/api/v1/dm/documents/uploads/11111111-1111-4111-8111-111111111111/status","/api/v1/session")){
    var response=client.send(java.net.http.HttpRequest.newBuilder(server.getURI().resolve(path)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(path.endsWith("/status")?503:500,response.statusCode());assertEquals(path.endsWith("/status")?"{\"code\":\"UNAVAILABLE\"}":"{\"code\":\"REQUEST_REJECTED\"}",response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
   }
  }
 }
 @io.micronaut.context.annotation.Requires(property="dkp.test.framework-error",value="true")
 @io.micronaut.http.annotation.ServerFilter("/api/v1/**")
 public static class FrameworkErrorFixture {
  @io.micronaut.http.annotation.RequestFilter public HttpResponse<?> fail(){return HttpResponse.serverError("private synthetic diagnostics");}
 }
}
