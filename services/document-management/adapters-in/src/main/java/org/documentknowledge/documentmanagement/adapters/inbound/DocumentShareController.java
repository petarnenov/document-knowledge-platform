package org.documentknowledge.documentmanagement.adapters.inbound;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.io.InputStream;
import java.util.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.sharing.*;
import org.documentknowledge.documentmanagement.application.sharing.SharingModels.*;
import tools.jackson.databind.json.JsonMapper;

/** Independent direct-API boundary. No BFF route or caller-selected action/authority exists. */
@Controller(value="/api/v1/documents",produces=MediaType.APPLICATION_JSON)
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class DocumentShareController {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final DocumentShareService sharing;
    private final DocumentController documents;
    public DocumentShareController(DocumentShareService sharing,DocumentController documents){this.sharing=sharing;this.documents=documents;}
    @Post(value="/knowledge-share-assessments",consumes=MediaType.ALL)
    public HttpResponse<?> assess(HttpRequest<?> request,Authentication authentication,@Nullable @Body InputStream body){return respond(request,body,()->{
        var decoded=decode(request,body);DocumentWire.keys(decoded,"documents");
        List<Expectation> expectations=new ArrayList<>();
        for(Object value:items(decoded.get("documents"))){var entry=DocumentWire.map(value);if(!entry.keySet().equals(Set.of("documentId"))&&!entry.keySet().equals(Set.of("documentId","expectedDocumentRevision")))throw DocumentWire.invalid();expectations.add(new Expectation(id(entry.get("documentId")),entry.containsKey("expectedDocumentRevision")?DocumentWire.revision(entry.get("expectedDocumentRevision")):null));}
        var result=sharing.assess(documents.caller(request,authentication),expectations,deadline(request));
        return Map.of("actor",actor(result.actor()),"tenant",result.tenant(),"action",result.action(),"assessments",result.assessments().stream().map(DocumentShareController::assessment).toList());
    });}
    @Post(value="/knowledge-share-eligibility",consumes=MediaType.ALL)
    public HttpResponse<?> discover(HttpRequest<?> request,Authentication authentication,@Nullable @Body InputStream body){return respond(request,body,()->{
        var decoded=decode(request,body);DocumentWire.keys(decoded,"documentIds");
        var ids=items(decoded.get("documentIds")).stream().map(DocumentShareController::id).toList();
        var result=sharing.discover(documents.caller(request,authentication),ids,deadline(request));
        return Map.of("actor",actor(result.actor()),"tenant",result.tenant(),"action",result.action(),"requestedSetDigest",result.requestedSetDigest(),"eligible",result.eligible().stream().map(DocumentShareController::assessment).toList());
    });}
    @Get(uris={"/knowledge-share-assessments","/knowledge-share-eligibility"},consumes=MediaType.ALL)
    public HttpResponse<?> get(){return error(405,"METHOD_NOT_ALLOWED");}
    @Patch(uris={"/knowledge-share-assessments","/knowledge-share-eligibility"},consumes=MediaType.ALL)
    public HttpResponse<?> patch(){return error(405,"METHOD_NOT_ALLOWED");}
    @Put(uris={"/knowledge-share-assessments","/knowledge-share-eligibility"},consumes=MediaType.ALL)
    public HttpResponse<?> put(){return error(405,"METHOD_NOT_ALLOWED");}
    @Delete(uris={"/knowledge-share-assessments","/knowledge-share-eligibility"},consumes=MediaType.ALL)
    public HttpResponse<?> delete(){return error(405,"METHOD_NOT_ALLOWED");}
    @Options(uris={"/knowledge-share-assessments","/knowledge-share-eligibility"},consumes=MediaType.ALL)
    public HttpResponse<?> options(){return error(405,"METHOD_NOT_ALLOWED");}
    private Map<?,?> decode(HttpRequest<?> request,InputStream body){
        deadline(request).check();documents.boundary(request,false);
        for(String name:List.of("Accept","Content-Encoding"))if(request.getHeaders().getAll(name).size()>1)throw DocumentWire.invalid();
        if(request.getHeaders().contains("Content-Encoding"))throw new DmFailure(415,"UNSUPPORTED_MEDIA_TYPE");
        String accept=request.getHeaders().get("Accept");if(accept!=null&&!accept.equals("application/json")&&!accept.equals("*/*"))throw new DmFailure(406,"NOT_ACCEPTABLE");
        return documents.decode(request,body);
    }
    private static List<?> items(Object value){if(!(value instanceof List<?> list)||list.isEmpty()||list.size()>100)throw DocumentWire.invalid();return list;}
    private static UUID id(Object value){if(!(value instanceof String string)||!string.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw DocumentWire.invalid();return UUID.fromString(string);}
    private static Map<String,Object> actor(org.documentknowledge.identity.Actor actor){return Map.of("issuer",actor.issuer(),"subject",actor.subject());}
    private static Map<String,Object> assessment(Assessment value){return Map.of("documentId",value.documentId().toString(),"documentRevision",value.documentRevision(),"classification",value.classification(),"classificationPolicyRevision",value.classificationPolicyRevision());}
    private static SharingDeadline deadline(HttpRequest<?> request){return request.getAttribute(SharingRequestDeadlineFilter.ATTRIBUTE,SharingDeadline.class).orElseThrow(()->new DmFailure(503,"UNAVAILABLE"));}
    private HttpResponse<?> respond(HttpRequest<?> request,InputStream body,java.util.function.Supplier<Object> work){
        try{
            byte[] bytes=JSON.writeValueAsBytes(work.get());if(bytes.length>32768)throw new DmFailure(503,"UNAVAILABLE");deadline(request).check();
            return HttpResponse.ok(bytes).contentType(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store");
        }catch(DmFailure failure){return error(failure.status(),failure.code());}catch(RuntimeException failure){return error(503,"UNAVAILABLE");}
        finally{if(body!=null)try{body.close();}catch(java.io.IOException|RuntimeException ignored){/* Cleanup cannot replace the finite response. */}}
    }
    private static HttpResponse<?> error(int status,String code){return HttpResponse.status(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store").body(Map.of("code",code));}
}
