package org.documentknowledge.browserbff.adapters.inbound;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.documentknowledge.browserbff.application.*;

@Controller(value="/api/v1/kb",produces=MediaType.APPLICATION_JSON)
@Secured(SecurityRule.IS_ANONYMOUS)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class KnowledgeController {
    private final KnowledgeWire wire;private final BrowserSessions sessions;private final KnowledgeGateway gateway;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final ExecutorService READERS=new ThreadPoolExecutor(0,16,30,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("kb-bff-body-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public KnowledgeController(BrowserSessions sessions,KnowledgeGateway gateway,KnowledgeWire wire){this.wire=wire;this.sessions=sessions;this.gateway=gateway;}
    @Get(value="/collections") public HttpResponse<?> route0(HttpRequest<?> request){return exchange(request,null);}
    @Post(value="/collections", consumes=MediaType.ALL) public HttpResponse<?> route1(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Get(value="/collections/{collectionId}") public HttpResponse<?> route2(HttpRequest<?> request){return exchange(request,null);}
    @Patch(value="/collections/{collectionId}", consumes=MediaType.ALL) public HttpResponse<?> route3(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Patch(value="/collections/{collectionId}/status", consumes=MediaType.ALL) public HttpResponse<?> route4(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Get(value="/collections/{collectionId}/acl") public HttpResponse<?> route5(HttpRequest<?> request){return exchange(request,null);}
    @Patch(value="/collections/{collectionId}/acl", consumes=MediaType.ALL) public HttpResponse<?> route6(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Get(value="/collections/{collectionId}/audience") public HttpResponse<?> route7(HttpRequest<?> request){return exchange(request,null);}
    @Put(value="/collections/{collectionId}/audience", consumes=MediaType.ALL) public HttpResponse<?> route8(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Post(value="/collections/{collectionId}/audience-evaluations", consumes=MediaType.ALL) public HttpResponse<?> route9(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Get(value="/collections/{collectionId}/memberships") public HttpResponse<?> route10(HttpRequest<?> request){return exchange(request,null);}
    @Post(value="/collections/{collectionId}/memberships", consumes=MediaType.ALL) public HttpResponse<?> route11(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Patch(value="/collections/{collectionId}/memberships/{membershipId}", consumes=MediaType.ALL) public HttpResponse<?> route12(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Post(value="/documents/{documentId}/policy-snapshots", consumes=MediaType.ALL) public HttpResponse<?> route13(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @Post(value="/documents/{documentId}/policy-validations", consumes=MediaType.ALL) public HttpResponse<?> route14(HttpRequest<?> request, @Body @io.micronaut.core.annotation.Nullable InputStream body){return exchange(request,body);}
    @SuppressWarnings("unchecked") private HttpResponse<?> exchange(HttpRequest<?> request,InputStream input){
        var deadline=request.getAttribute(KnowledgeDeadlineFilter.ATTRIBUTE,KnowledgeDeadline.class).orElseThrow();
        try{
            deadline.check();var session=sessions.requireSession(request);var identity=sessions.selectedIdentity(session);String tenant=sessions.selected(session);
            for(String header:List.of("Origin","X-CSRF-TOKEN","Idempotency-Key","Content-Type","Accept","Content-Encoding","X-DKP-Tenant","Authorization"))if(request.getHeaders().getAll(header).size()>1)throw new BffFailure(400,"BAD_REQUEST");
            if(request.getHeaders().contains("X-DKP-Tenant")||request.getHeaders().contains("Authorization"))throw new BffFailure(400,"BAD_REQUEST");
            String method=request.getMethodName();if(!method.equals("GET"))sessions.requireMutation(request,session);
            String raw=request.getUri().getRawPath();if(raw.contains("%")||raw.contains("//"))throw new BffFailure(404,"NOT_FOUND");
            String path=raw.replaceFirst("/api/v1/kb/","/api/v1/knowledge/");var route=KnowledgeWire.route(method,path);
            if(request.getHeaders().contains("Content-Encoding"))throw new BffFailure(415,"UNSUPPORTED_MEDIA_TYPE");
            String accept=request.getHeaders().get("Accept");if(accept!=null&&!Set.of("application/json","*/*").contains(accept))throw new BffFailure(406,"NOT_ACCEPTABLE");
            var query=new HashMap<String,String>();request.getParameters().asMap().forEach((name,values)->{if(values.size()!=1)throw new BffFailure(400,"BAD_REQUEST");query.put(name,values.getFirst());});
            Map<String,Object> body=null;
            if(!method.equals("GET")){
                if(!request.getContentType().map(t->t.getName().equals("application/json")).orElse(false))throw new BffFailure(415,"UNSUPPORTED_MEDIA_TYPE");
                if(input==null)throw new BffFailure(400,"BAD_REQUEST");
                Future<byte[]> pending=READERS.submit(()->input.readNBytes(16385));deadline.own(pending);byte[] bytes;
                try{bytes=pending.get(deadline.remainingNanos(),TimeUnit.NANOSECONDS);}catch(Exception failure){pending.cancel(true);throw failure;}
                deadline.check();if(bytes.length>16384)throw new BffFailure(413,"PAYLOAD_TOO_LARGE");
                body=JSON.readValue(bytes,Map.class);
            }else if(request.getContentLength()>0||request.getHeaders().contains("Transfer-Encoding"))throw new BffFailure(400,"BAD_REQUEST");
            String key=request.getHeaders().get("Idempotency-Key");wire.request(method,path,query,body,key);deadline.check();
            var result=gateway.exchange(method,path,query,body,key,identity,tenant,deadline);
            sessions.selectedIdentity(session);if(!Objects.equals(tenant,sessions.selected(session)))throw new BffFailure(403,"FORBIDDEN");deadline.check();
            if(result.status()>=400)return error(result.status(),(String)result.body().get("code"));
            byte[] encoded=JSON.writeValueAsBytes(result.body());deadline.check();if(encoded.length>2097152)throw new BffFailure(503,"UNAVAILABLE");
            return HttpResponse.status(HttpStatus.valueOf(result.status())).contentType(MediaType.APPLICATION_JSON_TYPE).body(encoded).header("Cache-Control","no-store");
        }catch(BffFailure failure){return error(failure.status(),failure.status()==401?"UNAUTHENTICATED":failure.status()==403?"FORBIDDEN":wire.validate("Error",Map.of("code",failure.code()))?failure.code():"UNAVAILABLE");}
        catch(TimeoutException|RejectedExecutionException|CancellationException failure){deadline.cancel();return error(503,"UNAVAILABLE");}
        catch(InterruptedException failure){deadline.cancel();Thread.currentThread().interrupt();return error(503,"UNAVAILABLE");}
        catch(Exception malformed){return error(400,"BAD_REQUEST");}
        finally{if(input!=null)try{input.close();}catch(IOException|RuntimeException ignored){/* Cleanup must never replace the finite response. */}}
    }
    private static HttpResponse<?> error(int status,String code){return HttpResponse.status(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON_TYPE).body(Map.of("code",code)).header("Cache-Control","no-store");}
}
