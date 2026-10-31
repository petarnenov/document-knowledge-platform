package org.documentknowledge.documentmanagement.adapters.inbound;
import io.micronaut.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadErrorResponsesTest {
 static final String ID="11111111-1111-4111-8111-111111111111",BASE="/api/v1/documents";
 @Test void exactUploadRoutesNormalizeFrameworkAndSharedBoundaryCodesOnly(){
  var filter=new UploadErrorResponses(new io.micronaut.http.server.HttpServerConfiguration());var routes=List.of(new String[]{"POST",BASE+"/uploads"},new String[]{"PUT",BASE+"/uploads/"+ID+"/content"},new String[]{"POST",BASE+"/uploads/"+ID+"/complete"},new String[]{"GET",BASE+"/uploads/"+ID+"/status"},new String[]{"GET",BASE+"/"+ID+"/upload-receipts/"+ID+"/version"},new String[]{"GET",BASE+"/"+ID+"/versions/"+ID+"/content"});
  for(var route:routes)for(var entry:Map.of(400,"INVALID_REQUEST",401,"UNAUTHENTICATED",403,"OPERATION_DENIED",404,"NOT_FOUND",409,"UPLOAD_STATE_CONFLICT",413,"PAYLOAD_TOO_LARGE",415,"UNSUPPORTED_MEDIA_TYPE",416,"RANGE_NOT_SATISFIABLE",429,"QUOTA_EXCEEDED",503,"UNAVAILABLE").entrySet()){
   var request=HttpRequest.create(HttpMethod.valueOf(route[0]),route[1]);var response=HttpResponse.status(HttpStatus.valueOf(entry.getKey())).body(Map.of("code","OLD_BOUNDARY_CODE"));filter.normalize(request,response);assertEquals(Map.of("code",entry.getValue()),response.body());assertEquals("no-store",response.header("Cache-Control"));
  }
 }
 @Test void typedErrorsAndAllOldMetadataWireRemainUnchanged(){var filter=new UploadErrorResponses(new io.micronaut.http.server.HttpServerConfiguration());
  for(String path:List.of(BASE,BASE+"/"+ID,BASE+"/"+ID+"/versions",BASE+"/"+ID+"/acl")){var response=HttpResponse.status(HttpStatus.REQUEST_ENTITY_TOO_LARGE).body(Map.of("code","REQUEST_TOO_LARGE"));filter.normalize(HttpRequest.POST(path,""),response);assertEquals(Map.of("code","REQUEST_TOO_LARGE"),response.body());}
  var request=HttpRequest.GET(BASE+"/uploads/"+ID+"/status");var response=HttpResponse.notFound(Map.of("code","NOT_FOUND"));filter.normalize(request,response);assertEquals(Map.of("code","NOT_FOUND"),response.body());var success=HttpResponse.ok(Map.of("uploadId",ID));filter.normalize(request,success);assertEquals(Map.of("uploadId",ID),success.body());
  for(Object malformed:List.of("unsafe diagnostic",Map.of("code",12),Map.of("code","NOT_FOUND","secret","diagnostic"))){var rejected=HttpResponse.badRequest(malformed);filter.normalize(request,rejected);assertEquals(Map.of("code","INVALID_REQUEST"),rejected.body());}
 }
 @Test void routeMatchingCannotExpandToOtherCommands(){
  for(String path:List.of(BASE+"/uploads/not-uuid/status",BASE+"/uploads/"+ID+"/other",BASE+"/"+ID+"/versions/not-uuid/content",BASE+"/"+ID+"/acl/"+ID+"/content",BASE+"/"+ID+"/versions/"+ID+"/other"))assertFalse(UploadErrorResponses.route("GET",path));
  assertFalse(UploadErrorResponses.route("GET",BASE+"/uploads"));assertFalse(UploadErrorResponses.route("DELETE",BASE+"/uploads/"+ID+"/content"));assertFalse(UploadErrorResponses.route("PATCH",BASE+"/"+ID+"/versions/"+ID+"/content"));
 }
 @Test void declaredFrameworkOversizeIsRejectedWithoutPayloadOrAuthorityAccess(){var configuration=new io.micronaut.http.server.HttpServerConfiguration();configuration.setMaxRequestSize(64);var filter=new UploadErrorResponses(configuration);var request=HttpRequest.PUT(BASE+"/uploads/"+ID+"/content","").header("Content-Length","65");var rejected=filter.rejectDeclaredOversize(request);assertNotNull(rejected);assertEquals(413,rejected.code());assertEquals(Map.of("code","PAYLOAD_TOO_LARGE"),rejected.body());assertNull(filter.rejectDeclaredOversize(HttpRequest.PUT(BASE+"/uploads/"+ID+"/content","").header("Content-Length","64")));assertNull(filter.rejectDeclaredOversize(HttpRequest.POST(BASE,"").header("Content-Length","65")));}
}
