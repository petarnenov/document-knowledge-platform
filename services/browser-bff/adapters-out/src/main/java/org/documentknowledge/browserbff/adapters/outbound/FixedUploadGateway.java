package org.documentknowledge.browserbff.adapters.outbound;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

/** Fixed backend binary routes. No cookies, browser bearer tokens, selectors, destinations or redirects. */
@Singleton
public final class FixedUploadGateway implements UploadGateway, AutoCloseable {
 private final String upstream;private final JsonMapper json;private final long totalMillis,idleMillis;private final HttpClient client;private final UploadReadAdmission sources;
 @jakarta.inject.Inject public FixedUploadGateway(@Value("${dkp.bff.upstreams.dm:}")String upstream,JsonMapper json,UploadReadAdmission sources){this(upstream,json,300_000,30_000,sources);}
 public FixedUploadGateway(String upstream,JsonMapper json){this(upstream,json,300_000,30_000,new UploadReadAdmission());}
 // Java-only shorter protocol-test deadlines; never configurable at runtime.
 FixedUploadGateway(String upstream,JsonMapper json,long totalMillis,long idleMillis){this(upstream,json,totalMillis,idleMillis,new UploadReadAdmission());}
 FixedUploadGateway(String upstream,JsonMapper json,long totalMillis,long idleMillis,UploadReadAdmission sources){if(totalMillis<1||totalMillis>300_000||idleMillis<1||idleMillis>30_000)throw new IllegalArgumentException("Invalid stream deadline");this.upstream=upstream;this.json=json;this.totalMillis=totalMillis;this.idleMillis=idleMillis;this.sources=Objects.requireNonNull(sources);this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();}
 private HttpRequest.Builder request(String path,BrowserIdentity identity,String tenant){URI base=URI.create(upstream);if(!Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null||!Set.of("","/").contains(base.getRawPath()))throw unavailable();return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofMillis(totalMillis)).header("Authorization","Bearer "+identity.accessToken()).header("X-DKP-Tenant",tenant);}
 @Override public DocumentGateway.Result transfer(String path,String key,InputStream body,long length,BrowserIdentity identity,String tenant,Runnable authorize){
  if(!UploadWire.transferRoute(path)||key==null||!key.matches("[!-~]{1,128}"))throw new BffFailure(400,"INVALID_REQUEST");if(length>100_000_000)throw new BffFailure(413,"PAYLOAD_TOO_LARGE");if(length==0||length< -1||body==null)throw new BffFailure(400,"INVALID_REQUEST");
  var lease=sources.tryRetain(body);if(lease==null)throw unavailable();
  var once=new AtomicBoolean();var bounded=new BoundedStream(body,length,authorize,totalMillis,idleMillis,lease::release);try(bounded){
   var publisher=HttpRequest.BodyPublishers.ofInputStream(()->{if(!once.compareAndSet(false,true))throw unavailable();return bounded;});
   HttpRequest.BodyPublisher wire=length>=0?HttpRequest.BodyPublishers.fromPublisher(publisher,length):publisher;
   var request=request(path,identity,tenant).header("Content-Type","application/octet-stream").header("Accept","application/json").header("Idempotency-Key",key).PUT(wire).build();
   var pending=client.sendAsync(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),16384));
   try{var response=pending.get(totalMillis,TimeUnit.MILLISECONDS);var result=json(response.statusCode(),response.headers(),response.body());if(result.status()!=200||!UploadWire.response(path,result.body())){if(result.status()>=400)return result;throw unavailable();}return result;}finally{if(!pending.isDone())pending.cancel(true);}
  }catch(BffFailure failure){throw failure;}catch(Exception failure){if(bounded.rejection()!=null)throw bounded.rejection();if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw unavailable();}
 }
 @Override public StreamResult download(String path,String range,BrowserIdentity identity,String tenant){
  if(!UploadWire.contentRoute(path))throw new BffFailure(404,"NOT_FOUND");InputStream body=null;
  long started=System.nanoTime();try{
   var request=request(path,identity,tenant).header("Accept","application/pdf, application/vnd.openxmlformats-officedocument.wordprocessingml.document, application/vnd.openxmlformats-officedocument.presentationml.presentation, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");if(range!=null)request.header("Range",range);
   var pending=client.sendAsync(request.GET().build(),HttpResponse.BodyHandlers.ofInputStream());HttpResponse<InputStream> response;
   try{response=pending.get(totalMillis,TimeUnit.MILLISECONDS);}finally{if(!pending.isDone())pending.cancel(true);}body=response.body();
   if(response.statusCode()!=200&&response.statusCode()!=206){try(var bounded=new BoundedStream(body,-1,()->{},remaining(started),idleMillis)){var error=json(response.statusCode(),response.headers(),bounded.readNBytes(16385));throw new BffFailure(error.status(),String.valueOf(error.body().get("code")));}}
   if((response.statusCode()==206)!=(range!=null))throw unavailable();
   var headers=response.headers();String mime=one(headers,"Content-Type"),disposition=one(headers,"Content-Disposition"),length=one(headers,"Content-Length");String version=UUID.fromString(path.split("/")[6]).toString();
   String extension=switch(mime){case "application/pdf"->"pdf";case "application/vnd.openxmlformats-officedocument.wordprocessingml.document"->"docx";case "application/vnd.openxmlformats-officedocument.presentationml.presentation"->"pptx";case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"->"xlsx";default->throw unavailable();};
   String filename=version+"."+extension;if(!disposition.equals("attachment; filename=\""+filename+"\"")||!length.matches("[1-9][0-9]{0,8}"))throw unavailable();long size=Long.parseLong(length);if(size>100_000_000||headers.firstValue("Content-Encoding").isPresent()||!one(headers,"Cache-Control").equals("no-store")||!one(headers,"X-Content-Type-Options").equals("nosniff")||!one(headers,"Accept-Ranges").equals("bytes"))throw unavailable();
   String contentRange=null;if(response.statusCode()==206){contentRange=one(headers,"Content-Range");validateRange(contentRange,size,range);}else if(headers.firstValue("Content-Range").isPresent())throw unavailable();
   var settled=new CompletableFuture<Void>();
   var bounded=new BoundedStream(body,size,()->{},remaining(started),idleMillis,()->settled.complete(null));
   return new StreamResult(response.statusCode(),mime,filename,size,contentRange,bounded,settled);
  }catch(BffFailure failure){close(body);throw failure;}catch(Exception failure){close(body);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw unavailable();}
 }
 @SuppressWarnings("unchecked")private DocumentGateway.Result json(int status,HttpHeaders headers,byte[] bytes)throws IOException{
  if(status!=200&&!Set.of(400,401,403,404,409,413,415,416,422,429,503).contains(status))throw unavailable();
  if(bytes.length>16384||!one(headers,"Content-Type").split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw unavailable();Map<String,Object> fields=json.readValue(bytes,Map.class);
  if(status>=400){if(!Set.of(400,401,403,404,409,413,415,416,422,429,503).contains(status)||fields==null||!fields.keySet().equals(Set.of("code"))||!(fields.get("code")instanceof String code)||!UploadWire.ERRORS.contains(code))throw unavailable();}
  return new DocumentGateway.Result(status,fields);
 }
 private long remaining(long started){long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);if(elapsed>=totalMillis)throw unavailable();return totalMillis-elapsed;}
 private static String one(HttpHeaders headers,String name){var values=headers.allValues(name);if(values.size()!=1)throw unavailable();return values.getFirst();}
 private static void validateRange(String header,long length,String requested){if(!header.matches("bytes [0-9]+-[0-9]+/[0-9]+"))throw unavailable();try{String[] parts=header.substring(6).split("[-/]");long start=Long.parseLong(parts[0]),end=Long.parseLong(parts[1]),total=Long.parseLong(parts[2]);if(start>end||end>=total||total>100_000_000||end-start+1!=length)throw unavailable();if(requested==null||!requested.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))throw unavailable();String[] wanted=requested.substring(6).split("-",-1);long expectedStart,expectedEnd;if(wanted[0].isEmpty()){long suffix=Long.parseLong(wanted[1]);if(suffix<1)throw unavailable();expectedStart=Math.max(0,total-suffix);expectedEnd=total-1;}else{expectedStart=Long.parseLong(wanted[0]);expectedEnd=wanted[1].isEmpty()?total-1:Math.min(total-1,Long.parseLong(wanted[1]));}if(start!=expectedStart||end!=expectedEnd)throw unavailable();}catch(NumberFormatException invalid){throw unavailable();}}
 private static void close(InputStream input){if(input!=null)try{input.close();}catch(IOException ignored){}}
 private static BffFailure unavailable(){return new BffFailure(503,"UNAVAILABLE");}
    /** Requests shutdown of this bean's client; active-exchange settlement is not implied. */
    @jakarta.annotation.PreDestroy
    @Override public void close() { client.shutdownNow(); }
}
