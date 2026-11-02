package org.documentknowledge.browserbff.adapters.inbound;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.session.Session;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Session and CSRF checks precede any binary body access or fixed upstream dispatch. */
@Controller(value="/api/v1/dm/documents",produces=MediaType.APPLICATION_JSON)
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class UploadController {
 private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 private final BrowserSessions sessions;private final DocumentGateway documents;private final UploadGateway uploads;private final DownloadDelivery delivery;
 public UploadController(BrowserSessions sessions,DocumentGateway documents,UploadGateway uploads,DownloadDelivery delivery){this.sessions=sessions;this.documents=documents;this.uploads=uploads;this.delivery=delivery;}
 @Post(value="/uploads",consumes=MediaType.ALL)public HttpResponse<?> initialize(HttpRequest<?> request,@Nullable @Body InputStream body){return metadata(request,body);}
 @Post(value="/uploads/{uploadId}/complete",consumes=MediaType.ALL)public HttpResponse<?> complete(HttpRequest<?> request,String uploadId,@Nullable @Body InputStream body){return metadata(request,body);}
 @Get("/uploads/{uploadId}/status")public HttpResponse<?> status(HttpRequest<?> request,String uploadId){return metadata(request,null);}
 @Get("/{documentId}/upload-receipts/{receiptId}/version")public HttpResponse<?> discover(HttpRequest<?> request,String documentId,String receiptId){return metadata(request,null);}
 @Put(value="/uploads/{uploadId}/content",consumes=MediaType.ALL)public HttpResponse<?> transfer(HttpRequest<?> request,String uploadId,@Nullable @Body InputStream body){try{return safely(()->{
  var context=context(request,true);String path=path(request);if(!UploadWire.transferRoute(path))throw new BffFailure(404,"NOT_FOUND");
  String type=request.getHeaders().get("Content-Type");if(!"application/octet-stream".equalsIgnoreCase(type))throw new BffFailure(415,"UNSUPPORTED_MEDIA_TYPE");
  long length=request.getHeaders().contentLength().orElse(-1);if(length>100_000_000)throw new BffFailure(413,"PAYLOAD_TOO_LARGE");if(length==0||body==null||request.getHeaders().contains("Content-Encoding")||request.getHeaders().contains("Content-Length")&&request.getHeaders().contains("Transfer-Encoding"))throw new BffFailure(400,"INVALID_REQUEST");
  try(body){var result=uploads.transfer(path,key(request),body,length,context.identity(),context.tenant(),()->current(context));current(context);return result(result);}catch(IOException failure){throw new BffFailure(400,"INVALID_REQUEST");}
 });}finally{close(body);}}
 @Get(value="/{documentId}/versions/{versionId}/content",produces={"application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})public HttpResponse<?> download(HttpRequest<?> request,String documentId,String versionId){return safely(()->{
  var context=context(request,false);if(request.getHeaders().getAll("Range").size()>1)throw new BffFailure(400,"INVALID_REQUEST");
  var reservation=delivery.reserve(request);
  try{var result=uploads.download(path(request),request.getHeaders().get("Range"),context.identity(),context.tenant());
   InputStream body=new FilterInputStream(result.body()){boolean started;@Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:Byte.toUnsignedInt(b[0]);}@Override public int read(byte[] b,int offset,int length)throws IOException{if(!started){started=true;try{current(context);}catch(RuntimeException denied){close();throw denied;}}return in.read(b,offset,length);}};
   var file=reservation.attach(result,body);current(context);var response=HttpResponse.status(HttpStatus.valueOf(result.status())).body(file).contentType(result.mimeType()).header("Content-Length",Long.toString(result.length())).header("Content-Disposition","attachment; filename=\""+result.filename()+"\"").header("Accept-Ranges","bytes").header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store");if(result.contentRange()!=null)response.header("Content-Range",result.contentRange());return response;
  }catch(RuntimeException failure){reservation.close();throw failure;}
 });}
 private HttpResponse<?> metadata(HttpRequest<?> request,InputStream body){try{return safely(()->{var context=context(request,request.getMethod()!=HttpMethod.GET);String path=path(request);Map<String,Object> value=body==null?null:decode(request,body);String key=request.getMethod()==HttpMethod.GET?null:key(request);DocumentWire.request(request.getMethodName(),path,Map.of(),value,key);var result=documents.exchange(request.getMethodName(),path,Map.of(),value,key,context.identity(),context.tenant());current(context);return result(result);});}finally{close(body);}}
 private record Context(Session session,BrowserIdentity identity,String tenant){}
 private Context context(HttpRequest<?> request,boolean mutation){path(request);if(!request.getParameters().isEmpty())throw new BffFailure(400,"INVALID_REQUEST");for(String header:List.of("Idempotency-Key","Origin","X-CSRF-TOKEN","Content-Type","Content-Length","Transfer-Encoding"))if(request.getHeaders().getAll(header).size()>1)throw new BffFailure(400,"INVALID_REQUEST");var session=sessions.requireSession(request);if(mutation)sessions.requireMutation(request,session);return new Context(session,sessions.selectedIdentity(session),sessions.selected(session));}
 private void current(Context context){if(!context.identity().equals(sessions.selectedIdentity(context.session()))||!context.tenant().equals(sessions.selected(context.session())))throw new BffFailure(401,"AUTHENTICATION_REQUIRED");}
 private static String path(HttpRequest<?> request){String raw=request.getUri().getRawPath();if(raw.contains("%")||raw.contains("//")||!raw.startsWith("/api/v1/dm/documents/"))throw new BffFailure(404,"NOT_FOUND");return raw.replaceFirst("/api/v1/dm/","/api/v1/");}
 private static String key(HttpRequest<?> request){String key=request.getHeaders().get("Idempotency-Key");if(key==null||!key.matches("[!-~]{1,128}"))throw new BffFailure(400,"INVALID_REQUEST");return key;}
 @SuppressWarnings("unchecked")private static Map<String,Object> decode(HttpRequest<?> request,InputStream body){if(request.getHeaders().contentLength().orElse(-1)>16384)throw new BffFailure(413,"PAYLOAD_TOO_LARGE");String type=request.getHeaders().get("Content-Type");if(type==null||!type.split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new BffFailure(415,"UNSUPPORTED_MEDIA_TYPE");try{byte[] bytes=body.readNBytes(16385);if(bytes.length>16384)throw new BffFailure(413,"PAYLOAD_TOO_LARGE");Object value=JSON.readValue(bytes,Object.class);if(!(value instanceof Map<?,?>))throw new BffFailure(400,"INVALID_REQUEST");return(Map<String,Object>)value;}catch(BffFailure failure){throw failure;}catch(Exception invalid){throw new BffFailure(400,"INVALID_REQUEST");}}
 private static HttpResponse<?> result(DocumentGateway.Result result){return HttpResponse.status(HttpStatus.valueOf(result.status())).body(result.body()).header("Cache-Control","no-store");}
 private static HttpResponse<?> safely(Supplier<HttpResponse<?>> work){try{return work.get();}catch(BffFailure failure){return HttpResponse.status(HttpStatus.valueOf(failure.status())).body(Map.of("code",failure.code())).header("Cache-Control","no-store");}catch(RuntimeException failure){return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code","UNAVAILABLE")).header("Cache-Control","no-store");}}
 private static void close(InputStream body){if(body!=null)try{body.close();}catch(IOException ignored){}}
}
