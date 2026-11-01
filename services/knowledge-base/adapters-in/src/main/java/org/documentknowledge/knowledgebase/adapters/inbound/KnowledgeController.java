package org.documentknowledge.knowledgebase.adapters.inbound;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.io.*;
import java.util.*;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.security.TrustedTenantResolver;

@Controller(value="/api/v1/knowledge",produces=MediaType.ALL)
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class KnowledgeController {
    private final KnowledgeRequestFactory requests;private final TrustedTenantResolver tenants;private final KnowledgeBodyReader bodies;private final KnowledgeRequestOwner owners;private final KnowledgeFinalResponse responses;
    public KnowledgeController(KnowledgeRequestFactory requests,TrustedTenantResolver tenants,KnowledgeBodyReader bodies,KnowledgeRequestOwner owners,KnowledgeFinalResponse responses){this.requests=requests;this.tenants=tenants;this.bodies=bodies;this.owners=owners;this.responses=responses;}
    @Get("/collections") public HttpResponse<?> list(HttpRequest<?> request,Authentication actor){return invoke(request,actor,null,true,(service,c,deadline,body)->{var page=service.list(c,cursor(request),limit(request),deadline);return new Result(200,()->KnowledgeWire.page(page.items().stream().map(KnowledgeWire::collection).toList(),page.nextCursor()));});}
    @Post(value="/collections",consumes=MediaType.ALL) public HttpResponse<?> create(HttpRequest<?> request,Authentication actor,@Nullable @Body InputStream body){return invoke(request,actor,body,false,(service,c,deadline,input)->outcome(service.create(c,KnowledgeWire.metadata(input),key(request),KnowledgeWire.hash(input),deadline)));}
    @Get("/collections/{collectionId}") public HttpResponse<?> inspect(HttpRequest<?> request,Authentication actor,String collectionId){return invoke(request,actor,null,false,(service,c,deadline,body)->inspectResult(service,c,KnowledgeWire.id(collectionId),deadline,KnowledgeWire::collection));}
    @Patch(value="/collections/{collectionId}",consumes=MediaType.ALL) public HttpResponse<?> update(HttpRequest<?> request,Authentication actor,String collectionId,@Nullable @Body InputStream body){return mutate(request,actor,collectionId,body,KnowledgeWire::update);}
    @Patch(value="/collections/{collectionId}/status",consumes=MediaType.ALL) public HttpResponse<?> status(HttpRequest<?> request,Authentication actor,String collectionId,@Nullable @Body InputStream body){return mutate(request,actor,collectionId,body,KnowledgeWire::status);}
    @Get("/collections/{collectionId}/acl") public HttpResponse<?> acl(HttpRequest<?> request,Authentication actor,String collectionId){return invoke(request,actor,null,false,(service,c,deadline,body)->inspectResult(service,c,KnowledgeWire.id(collectionId),deadline,KnowledgeWire::acl));}
    @Patch(value="/collections/{collectionId}/acl",consumes=MediaType.ALL) public HttpResponse<?> acl(HttpRequest<?> request,Authentication actor,String collectionId,@Nullable @Body InputStream body){return mutate(request,actor,collectionId,body,KnowledgeWire::aclChange);}
    @Get("/collections/{collectionId}/audience") public HttpResponse<?> audience(HttpRequest<?> request,Authentication actor,String collectionId){return invoke(request,actor,null,false,(service,c,deadline,body)->inspectResult(service,c,KnowledgeWire.id(collectionId),deadline,value->KnowledgeWire.audience(value.audience())));}
    @Put(value="/collections/{collectionId}/audience",consumes=MediaType.ALL) public HttpResponse<?> audience(HttpRequest<?> request,Authentication actor,String collectionId,@Nullable @Body InputStream body){return mutate(request,actor,collectionId,body,KnowledgeWire::audienceChange);}
    @Post(value="/collections/{collectionId}/audience-evaluations",consumes=MediaType.ALL) public HttpResponse<?> evaluate(HttpRequest<?> request,Authentication actor,String collectionId,@Nullable @Body InputStream body){return invoke(request,actor,body,false,(service,c,deadline,input)->{KnowledgeWire.keys(input);var result=service.evaluate(c,KnowledgeWire.id(collectionId),deadline);return new Result(200,()->Map.of("collectionId",result.collectionId().toString(),"audiencePolicyRevision",result.audiencePolicyRevision(),"decision",result.decision().name()));});}
    @Get("/collections/{collectionId}/memberships") public HttpResponse<?> members(HttpRequest<?> request,Authentication actor,String collectionId){return invoke(request,actor,null,true,(service,c,deadline,body)->{var page=service.members(c,KnowledgeWire.id(collectionId),cursor(request),limit(request),deadline);return new Result(200,()->KnowledgeWire.page(page.items().stream().map(KnowledgeWire::membership).toList(),page.nextCursor()));});}
    @Post(value="/collections/{collectionId}/memberships",consumes=MediaType.ALL) public HttpResponse<?> member(HttpRequest<?> request,Authentication actor,String collectionId,@Nullable @Body InputStream body){return mutate(request,actor,collectionId,body,KnowledgeWire::membership);}
    @Patch(value="/collections/{collectionId}/memberships/{membershipId}",consumes=MediaType.ALL) public HttpResponse<?> member(HttpRequest<?> request,Authentication actor,String collectionId,String membershipId,@Nullable @Body InputStream body){return mutate(request,actor,collectionId,body,input->KnowledgeWire.membership(KnowledgeWire.id(membershipId),input));}
    @Post(value="/documents/{documentId}/policy-snapshots",consumes=MediaType.ALL) public HttpResponse<?> snapshot(HttpRequest<?> request,Authentication actor,String documentId,@Nullable @Body InputStream body){return invoke(request,actor,body,false,(service,c,deadline,input)->{KnowledgeWire.keys(input,"expectedDocumentRevision");var result=service.snapshot(c,KnowledgeWire.id(documentId),KnowledgeWire.revision(input.get("expectedDocumentRevision")),deadline);return new Result(201,()->Map.of("snapshotToken",result.snapshotToken(),"documentId",result.documentId().toString(),"documentRevision",result.documentRevision(),"classificationPolicyRevision",1,"decision",result.decision().name()));});}
    @Post(value="/documents/{documentId}/policy-validations",consumes=MediaType.ALL) public HttpResponse<?> validate(HttpRequest<?> request,Authentication actor,String documentId,@Nullable @Body InputStream body){return invoke(request,actor,body,false,(service,c,deadline,input)->{KnowledgeWire.keys(input,"snapshotToken","expectedDocumentRevision");var result=service.validate(c,KnowledgeWire.id(documentId),KnowledgeWire.revision(input.get("expectedDocumentRevision")),KnowledgeWire.token(input.get("snapshotToken")),deadline);return new Result(200,()->Map.of("documentId",result.documentId().toString(),"decision",result.decision().name()));});}
    private HttpResponse<?> mutate(HttpRequest<?> request,Authentication actor,String id,InputStream body,java.util.function.Function<Map<?,?>,KnowledgeService.Change> decode){return invoke(request,actor,body,false,(service,c,deadline,input)->outcome(service.change(c,KnowledgeWire.id(id),decode.apply(input),key(request),KnowledgeWire.hash(input),deadline)));}
    private static Result inspectResult(KnowledgeService service,Context context,UUID id,Deadline deadline,java.util.function.Function<org.documentknowledge.knowledgebase.domain.KbModels.Collection,Object> encode){var value=service.inspect(context,id,deadline);return new Result(200,()->encode.apply(value));}
    private static Result outcome(CommandOutcome value){return new Result(value.status(),()->KnowledgeWire.receipt(value.receipt()));}
    private record Result(int status,java.util.function.Supplier<Object> body) { }
    private interface Work { Result run(KnowledgeService service,Context context,Deadline deadline,Map<?,?> input); }
    private HttpResponse<?> invoke(HttpRequest<?> request,Authentication authentication,InputStream body,boolean list,Work work){
        try{
            Deadline deadline=request.getAttribute(KnowledgeBoundaryFilter.DEADLINE,Deadline.class).orElseThrow(KbFailure::unavailable);try(var ownership=owners.enter(request,deadline)){deadline.check();boundary(request,list);
            String selected=request.getHeaders().get("X-DKP-Tenant");if(selected==null||selected.isBlank()||selected.length()>256)throw new KbFailure(403,"FORBIDDEN");var claims=authentication.getAttributes();AuthorityDecision membership=tenants.resolve(claims).select(selected);
            if(membership!=AuthorityDecision.ALLOW)throw new KbFailure(membership==AuthorityDecision.UNAVAILABLE?503:403,membership==AuthorityDecision.UNAVAILABLE?"UNAVAILABLE":"FORBIDDEN");
            Context context=new Context(new Principal(KnowledgeWire.text(claims.get("iss"),1,2048),KnowledgeWire.text(claims.get("sub"),1,256)),selected);
            KnowledgeSetupBudget setup=request.getAttribute(KnowledgeSetupBudget.ATTRIBUTE,KnowledgeSetupBudget.class).orElseThrow(KbFailure::unavailable);setup.identityComplete();
            Map<?,?> input=body==null?null:KnowledgeWire.decode(bodies.read(body,deadline),request.getHeaders().get("Content-Type"));
            if(!request.getMethod().equals(HttpMethod.GET)&&body==null)throw KnowledgeWire.invalid();
            try(var handle=open(setup,context,claims,request.getHeaders().get("Authorization"))){
                var result=work.run(handle.service(),context,deadline,input);
                Deadline phase=deadline.child(java.time.Duration.ofMillis(500));
                return responses.finish(phase,()->{setup.run(handle::revalidate);AuthorityDecision current=setup.call(()->tenants.resolve(claims).select(selected));if(current!=AuthorityDecision.ALLOW)throw new KbFailure(current==AuthorityDecision.UNAVAILABLE?503:403,current==AuthorityDecision.UNAVAILABLE?"UNAVAILABLE":"FORBIDDEN");},result.body(),result.status());
            }
            }

        }catch(KbFailure failure){return HttpResponse.status(HttpStatus.valueOf(failure.status())).body(Map.of("code",failure.code())).header("Cache-Control","no-store");}
        catch(RuntimeException failure){return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code","UNAVAILABLE")).header("Cache-Control","no-store");}

    }
    private KnowledgeRequestFactory.RequestHandle open(KnowledgeSetupBudget setup,Context context,Map<String,Object> claims,String authorization){
        var acquired=new java.util.concurrent.atomic.AtomicReference<KnowledgeRequestFactory.RequestHandle>();
        try{setup.run(()->acquired.set(requests.open(context,Map.copyOf(claims),authorization)));return acquired.get();}
        catch(RuntimeException failure){var handle=acquired.get();if(handle!=null)handle.close();throw failure;}
    }
    private static String key(HttpRequest<?> request){String key=request.getHeaders().get("Idempotency-Key");if(key==null||!key.matches("[!-~]{1,128}"))throw KnowledgeWire.invalid();return key;}
    private static String cursor(HttpRequest<?> request){String value=request.getParameters().get("cursor");return value==null?null:KnowledgeWire.text(value,1,2048);}
    private static int limit(HttpRequest<?> request){String value=request.getParameters().get("limit");if(value==null)return 20;if(!value.matches("[1-9]|[1-4][0-9]|50"))throw KnowledgeWire.invalid();return Integer.parseInt(value);}
    private static boolean acceptsJson(HttpRequest<?> request){
        if(!request.getHeaders().contains("Accept"))return true;
        double[] quality={-1,-1,-1};
        try{for(MediaType media:request.getHeaders().accept()){
            int specificity=media.getName().equalsIgnoreCase("application/json")?2:media.getName().equalsIgnoreCase("application/*")?1:media.getName().equals("*/*")?0:-1;
            if(specificity>=0)quality[specificity]=Math.max(quality[specificity],media.getQualityAsNumber().doubleValue());
        }}catch(IllegalArgumentException malformed){throw KnowledgeWire.invalid();}
        for(int i=2;i>=0;i--)if(quality[i]>=0)return quality[i]>0;
        return false;
    }
    private static void boundary(HttpRequest<?> request,boolean list){
        if(!acceptsJson(request))throw new KbFailure(406,"NOT_ACCEPTABLE");
        String raw=request.getUri().getRawPath();if(raw.contains("%")||raw.contains("//"))throw KnowledgeWire.invalid();
        for(String name:List.of("Authorization","X-DKP-Tenant","Idempotency-Key","Content-Type","Content-Length","Transfer-Encoding"))if(request.getHeaders().getAll(name).size()>1)throw KnowledgeWire.invalid();
        request.getParameters().asMap().forEach((name,values)->{if(!list||!Set.of("limit","cursor").contains(name)||values.size()!=1)throw KnowledgeWire.invalid();});
        if(request.getHeaders().contains("Content-Encoding")||request.getHeaders().contains("Content-Length")&&request.getHeaders().contains("Transfer-Encoding"))throw KnowledgeWire.invalid();
        long length=request.getHeaders().contentLength().orElse(-1);if(length>KnowledgeWire.REQUEST_LIMIT)throw new KbFailure(413,"PAYLOAD_TOO_LARGE");
        if(request.getMethod()==HttpMethod.GET&&(length>0||request.getHeaders().contains("Transfer-Encoding")))throw KnowledgeWire.invalid();
    }
}
