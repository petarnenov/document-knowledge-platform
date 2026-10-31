package org.documentknowledge.documentmanagement.adapters.inbound;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.io.*;
import java.util.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;

/** Separate bounded binary transport; metadata never uses the binary body allowance. */
@Controller(value="/api/v1/documents",produces=MediaType.APPLICATION_JSON)
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class UploadController {
    private final UploadService uploads;
    private final ContentService content;
    private final DocumentController boundary;
    public UploadController(UploadService uploads,ContentService content,DocumentController boundary){this.uploads=uploads;this.content=content;this.boundary=boundary;}
    @Post(value="/uploads",consumes=MediaType.ALL)
    public HttpResponse<?> initialize(HttpRequest<?> request,Authentication authentication,@Nullable @Body InputStream body){return boundary.respond(201,body,()->{
        boundary.boundary(request,false);var caller=boundary.caller(request,authentication);var value=boundary.decode(request,body);
        DocumentWire.keys(value,"documentId","expectedRevision","format","sizeBytes");UploadFormat format;
        try{format=UploadFormat.valueOf(DocumentWire.text(value.get("format"),1,8));}catch(IllegalArgumentException invalid){throw DocumentWire.invalid();}
        long size=DocumentWire.revision(value.get("sizeBytes"));if(size<1)throw DocumentWire.invalid();
        return Map.of("uploadId",uploads.initialize(caller,boundary.key(request),DocumentWire.id(DocumentWire.text(value.get("documentId"),36,36)),DocumentWire.revision(value.get("expectedRevision")),format,size).uploadId().toString());
    });}
    @Put(value="/uploads/{uploadId}/content",consumes=MediaType.ALL)
    public HttpResponse<?> transfer(HttpRequest<?> request,Authentication authentication,String uploadId,@Nullable @Body InputStream body){return boundary.respond(200,body,()->{
        boundary.boundary(request,false);binaryBoundary(request);var caller=boundary.caller(request,authentication);
        if(body==null)throw DocumentWire.invalid();return Map.of("uploadId",uploads.transfer(caller,DocumentWire.id(uploadId),boundary.key(request),body).uploadId().toString());
    });}
    @Post(value="/uploads/{uploadId}/complete",consumes=MediaType.ALL)
    public HttpResponse<?> complete(HttpRequest<?> request,Authentication authentication,String uploadId,@Nullable @Body InputStream body){return boundary.respond(200,body,()->{
        boundary.boundary(request,false);var caller=boundary.caller(request,authentication);DocumentWire.keys(boundary.decode(request,body));
        var result=uploads.complete(caller,DocumentWire.id(uploadId),boundary.key(request));return Map.of("uploadId",result.uploadId().toString(),"receiptId",result.receiptId().toString());
    });}
    @Get("/uploads/{uploadId}/status")
    public HttpResponse<?> status(HttpRequest<?> request,Authentication authentication,String uploadId){return boundary.respond(200,()->{
        boundary.boundary(request,false);var result=uploads.status(boundary.caller(request,authentication),DocumentWire.id(uploadId));Map<String,Object> value=new LinkedHashMap<>();value.put("uploadId",result.uploadId().toString());value.put("state",result.state().name());value.put("expiresAt",result.expiresAt().toString());value.put("failureCode",result.failureCode());return value;
    });}
    @Get("/{documentId}/upload-receipts/{receiptId}/version")
    public HttpResponse<?> discover(HttpRequest<?> request,Authentication authentication,String documentId,String receiptId){return boundary.respond(200,()->{
        boundary.boundary(request,false);var result=uploads.discover(boundary.caller(request,authentication),DocumentWire.id(documentId),DocumentWire.id(receiptId));return Map.of("documentId",result.documentId().toString(),"versionId",result.versionId().toString());
    });}
    @Get(value="/{documentId}/versions/{versionId}/content",produces={"application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public HttpResponse<?> download(HttpRequest<?> request,Authentication authentication,String documentId,String versionId){
        ContentService.ContentResponse result=null;
        try{
            boundary.boundary(request,false);if(request.getHeaders().getAll("Range").size()>1)throw DocumentWire.invalid();
            result=content.open(boundary.caller(request,authentication),DocumentWire.id(documentId),DocumentWire.id(versionId),request.getHeaders().get("Range"));
            // This opens only after the final current authority recheck. The stream owns spool cleanup.
            var stream=result.openStream();var file=new StreamedFile(stream,MediaType.of(result.mimeType()),System.currentTimeMillis(),result.length());
            var response=HttpResponse.status(HttpStatus.valueOf(result.status())).body(file).contentType(result.mimeType()).header("Content-Length",Long.toString(result.length())).header("Content-Disposition","attachment; filename=\""+result.filename()+"\"").header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Accept-Ranges","bytes");
            if(result.contentRange()!=null)response.header("Content-Range",result.contentRange());return response;
        }catch(RuntimeException failure){if(result!=null)result.close();return boundary.respond(200,()->{throw failure;});}
    }
    static void binaryBoundary(HttpRequest<?> request){
        String type=request.getHeaders().get("Content-Type");if(type==null || !type.equalsIgnoreCase("application/octet-stream"))throw new DmFailure(415,"UNSUPPORTED_MEDIA_TYPE");
        if(request.getHeaders().getAll("Content-Length").size()>1 || request.getHeaders().getAll("Transfer-Encoding").size()>1)throw DocumentWire.invalid();
        long length=request.getHeaders().contentLength().orElse(-1);if(length>100_000_000)throw new DmFailure(413,"PAYLOAD_TOO_LARGE");if(length==0)throw DocumentWire.invalid();
        if(request.getHeaders().contains("Content-Encoding") || (request.getHeaders().contains("Content-Length")&&request.getHeaders().contains("Transfer-Encoding")))throw DocumentWire.invalid();
    }
}
