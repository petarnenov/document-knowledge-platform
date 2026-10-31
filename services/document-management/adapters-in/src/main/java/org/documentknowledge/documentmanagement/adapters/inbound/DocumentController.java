package org.documentknowledge.documentmanagement.adapters.inbound;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.security.TrustedTenantResolver;

@Controller(value="/api/v1/documents",produces=MediaType.APPLICATION_JSON)
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class DocumentController {
    private final DocumentService documents;
    private final TrustedTenantResolver tenants;
    public DocumentController(DocumentService documents,TrustedTenantResolver tenants){this.documents=documents;this.tenants=tenants;}
    @Get public HttpResponse<?> list(HttpRequest<?> request,Authentication authentication){return respond(200,()->{
        boundary(request,true);var caller=caller(request,authentication);String raw=request.getParameters().get("limit");int limit=20;if(raw!=null){if(!raw.matches("[1-9]|[1-4][0-9]|50"))throw DocumentWire.invalid();limit=Integer.parseInt(raw);}
        var page=documents.list(caller,limit,request.getParameters().get("cursor"));Map<String,Object> result=new LinkedHashMap<>();result.put("items",page.items().stream().map(DocumentWire::document).toList());result.put("nextCursor",page.nextCursor());return result;
    });}
    @Post(consumes=MediaType.ALL) public HttpResponse<?> create(HttpRequest<?> request,Authentication authentication,@Nullable @Body InputStream body){return respond(201,body,()->{boundary(request,false);return DocumentWire.receipt(documents.create(caller(request,authentication),key(request),DocumentWire.draft(decode(request,body))));});}
    @Get("/{documentId}") public HttpResponse<?> detail(HttpRequest<?> request,Authentication authentication,String documentId){return respond(200,()->{boundary(request,false);return DocumentWire.document(documents.inspect(caller(request,authentication),DocumentWire.id(documentId)));});}
    @Patch(value="/{documentId}",consumes=MediaType.ALL) public HttpResponse<?> update(HttpRequest<?> request,Authentication authentication,String documentId,@Nullable @Body InputStream body){return respond(200,body,()->{boundary(request,false);var actor=caller(request,authentication);var decoded=decode(request,body);DocumentWire.keys(decoded,"expectedRevision","metadata");return DocumentWire.receipt(documents.updateMetadata(actor,DocumentWire.id(documentId),DocumentWire.revision(decoded.get("expectedRevision")),key(request),DocumentWire.metadata(decoded.get("metadata"))));});}
    @Get("/{documentId}/acl") public HttpResponse<?> acl(HttpRequest<?> request,Authentication authentication,String documentId){return respond(200,()->{boundary(request,false);return DocumentWire.acl(documents.inspectAcl(caller(request,authentication),DocumentWire.id(documentId)));});}
    @Patch(value="/{documentId}/acl",consumes=MediaType.ALL) public HttpResponse<?> changeAcl(HttpRequest<?> request,Authentication authentication,String documentId,@Nullable @Body InputStream body){return respond(200,body,()->{boundary(request,false);var actor=caller(request,authentication);var decoded=decode(request,body);var change=DocumentWire.acl(decoded);return DocumentWire.receipt(documents.changeAcl(actor,DocumentWire.id(documentId),DocumentWire.revision(decoded.get("expectedRevision")),key(request),change));});}
    @Get("/{documentId}/legal-holds") public HttpResponse<?> holds(HttpRequest<?> request,Authentication authentication,String documentId){return respond(200,()->{boundary(request,false);return DocumentWire.holds(documents.inspectHolds(caller(request,authentication),DocumentWire.id(documentId)));});}
    @Post(value="/{documentId}/legal-holds",consumes=MediaType.ALL) public HttpResponse<?> hold(HttpRequest<?> request,Authentication authentication,String documentId,@Nullable @Body InputStream body){return respond(201,body,()->{boundary(request,false);var actor=caller(request,authentication);var decoded=decode(request,body);DocumentWire.keys(decoded,"expectedRevision","reason");return DocumentWire.receipt(documents.placeHold(actor,DocumentWire.id(documentId),DocumentWire.revision(decoded.get("expectedRevision")),key(request),DocumentWire.text(decoded.get("reason"),1,1024)));});}
    @Post(value="/{documentId}/versions",consumes=MediaType.ALL) public HttpResponse<?> version(HttpRequest<?> request,Authentication authentication,String documentId,@Nullable @Body InputStream body){return respond(201,body,()->{boundary(request,false);var actor=caller(request,authentication);var decoded=decode(request,body);DocumentWire.keys(decoded,"expectedRevision","receiptId");return DocumentWire.receipt(documents.appendVersion(actor,DocumentWire.id(documentId),DocumentWire.revision(decoded.get("expectedRevision")),key(request),DocumentWire.text(decoded.get("receiptId"),1,256)));});}
    @Error(global=true,status=HttpStatus.NOT_FOUND) public HttpResponse<?> notFound(){return safeError(404,"NOT_FOUND");}
    @Error(global=true,status=HttpStatus.METHOD_NOT_ALLOWED) public HttpResponse<?> methodNotAllowed(){return safeError(405,"METHOD_NOT_ALLOWED");}
    @Error(global=true,status=HttpStatus.BAD_REQUEST) public HttpResponse<?> badRequest(){return safeError(400,"INVALID_REQUEST");}
    @Error(global=true,status=HttpStatus.UNSUPPORTED_MEDIA_TYPE) public HttpResponse<?> unsupportedType(){return safeError(415,"UNSUPPORTED_MEDIA_TYPE");}
    @Error(global=true,status=HttpStatus.REQUEST_ENTITY_TOO_LARGE) public HttpResponse<?> tooLarge(){return safeError(413,"REQUEST_TOO_LARGE");}
    private HttpResponse<?> safeError(int status,String code){return HttpResponse.status(HttpStatus.valueOf(status)).body(Map.of("code",code)).header("Cache-Control","no-store");}
    private Map<?,?> decode(HttpRequest<?> request,InputStream body){if(request.getHeaders().contentLength().orElse(-1)>16384)throw new DmFailure(413,"REQUEST_TOO_LARGE");return DocumentWire.decode(read(body),request.getHeaders().get("Content-Type"));}
    private static byte[] read(InputStream body){if(body==null)return null;try(body){return body.readNBytes(16385);}catch(IOException failure){Throwable cause=failure;for(int depth=0;cause!=null && depth<8;depth++,cause=cause.getCause())if(cause instanceof ContentLengthExceededException)throw new DmFailure(413,"REQUEST_TOO_LARGE");throw DocumentWire.invalid();}}
    private DocumentCaller caller(HttpRequest<?> request,Authentication authentication){String tenant=request.getHeaders().get("X-DKP-Tenant");if(tenant==null || tenant.isBlank() || tenant.length()>256)throw new DmFailure(403,"TENANT_DENIED");AuthorityDecision membership=tenants.resolve(authentication.getAttributes()).select(tenant);if(membership!=AuthorityDecision.ALLOW)throw new DmFailure(membership==AuthorityDecision.UNAVAILABLE?503:403,membership==AuthorityDecision.UNAVAILABLE?"AUTHORITY_UNAVAILABLE":"TENANT_DENIED");return new DocumentCaller(new Actor((String)authentication.getAttributes().get("iss"),(String)authentication.getAttributes().get("sub")),tenant,()->tenants.resolve(authentication.getAttributes()).select(tenant));}
    private String key(HttpRequest<?> request){String key=request.getHeaders().get("Idempotency-Key");if(key==null || !key.matches("[!-~]{1,128}"))throw DocumentWire.invalid();return key;}
    private void boundary(HttpRequest<?> request,boolean list){String path=request.getUri().getRawPath();if(path.contains("%") || path.contains("//"))throw DocumentWire.invalid();for(String header:List.of("Authorization","X-DKP-Tenant","Idempotency-Key","Content-Type"))if(request.getHeaders().getAll(header).size()>1)throw DocumentWire.invalid();request.getParameters().asMap().forEach((name,values)->{if(!list || !Set.of("limit","cursor").contains(name) || values.size()!=1)throw DocumentWire.invalid();});}
    private HttpResponse<?> respond(int status,InputStream body,Supplier<Object> work){try{return respond(status,work);}finally{if(body!=null)try{body.close();}catch(IOException | RuntimeException ignored){/* The response reflects the command outcome, not a completed stream close. */}}}
    private HttpResponse<?> respond(int status,Supplier<Object> work){try{return HttpResponse.status(HttpStatus.valueOf(status)).body(work.get()).header("Cache-Control","no-store");}catch(DmFailure failure){return HttpResponse.status(HttpStatus.valueOf(failure.status())).body(Map.of("code",failure.code())).header("Cache-Control","no-store");}catch(RuntimeException failure){return HttpResponse.serverError(Map.of("code","UNAVAILABLE")).status(HttpStatus.SERVICE_UNAVAILABLE).header("Cache-Control","no-store");}}
}
