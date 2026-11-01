package org.documentknowledge.documentmanagement.adapters.inbound;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.server.HttpServerConfiguration;
import jakarta.inject.Inject;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import java.util.*;
/** The shared metadata/security boundary keeps its own wire; only approved BP03 routes use UploadError. */
@ServerFilter("/api/v1/documents/**")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class UploadErrorResponses {
 private static final Set<String> CODES=Set.of("INVALID_REQUEST","UNAUTHENTICATED","TENANT_DENIED","OPERATION_DENIED","NOT_FOUND","REVISION_CONFLICT","IDEMPOTENCY_CONFLICT","UPLOAD_STATE_CONFLICT","RECEIPT_CONSUMED","UPLOAD_EXPIRED","PAYLOAD_TOO_LARGE","UNSUPPORTED_MEDIA_TYPE","SIZE_MISMATCH","INVALID_FILE","MALWARE_DETECTED","STRUCTURE_LIMIT","UNSUPPORTED_CONTENT","RANGE_NOT_SATISFIABLE","QUOTA_EXCEEDED","UNAVAILABLE");
 private final HttpServerConfiguration configuration;
 @Inject public UploadErrorResponses(HttpServerConfiguration configuration){this.configuration=configuration;}
 @RequestFilter @Nullable public HttpResponse<?> rejectDeclaredOversize(HttpRequest<?> request){
  if(route(request.getMethodName(),request.getUri().getRawPath())&&request.getHeaders().contentLength().orElse(-1)>configuration.getMaxRequestSize())return HttpResponse.status(HttpStatus.REQUEST_ENTITY_TOO_LARGE).body(Map.of("code","PAYLOAD_TOO_LARGE")).header("Cache-Control","no-store");return null;
 }
 @ResponseFilter public void normalize(HttpRequest<?> request,MutableHttpResponse<?> response){
  if(response.code()<400||!route(request.getMethodName(),request.getUri().getRawPath()))return;
  Object body=response.getBody().orElse(null);if(!(body instanceof Map<?,?> map&&map.size()==1&&map.get("code") instanceof String code&&CODES.contains(code))){String code=switch(response.code()){case 401->"UNAUTHENTICATED";case 403->"OPERATION_DENIED";case 404->"NOT_FOUND";case 409->"UPLOAD_STATE_CONFLICT";case 413->"PAYLOAD_TOO_LARGE";case 415->"UNSUPPORTED_MEDIA_TYPE";case 416->"RANGE_NOT_SATISFIABLE";case 429->"QUOTA_EXCEEDED";case 503->"UNAVAILABLE";default->"INVALID_REQUEST";};response.body(Map.of("code",code));}
  response.header("Cache-Control","no-store").contentType(MediaType.APPLICATION_JSON_TYPE);
 }
 static boolean route(String method,String path){String[] p=path.split("/",-1);if(path.equals("/api/v1/documents/uploads"))return method.equals("POST");if(p.length==7&&p[4].equals("uploads")&&uuid(p[5]))return p[6].equals("content")&&method.equals("PUT")||p[6].equals("complete")&&method.equals("POST")||p[6].equals("status")&&method.equals("GET");return p.length==8&&uuid(p[4])&&uuid(p[6])&&(p[5].equals("upload-receipts")&&p[7].equals("version")||p[5].equals("versions")&&p[7].equals("content"))&&method.equals("GET");}
 private static boolean uuid(String value){return value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}
}
