package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.InspectionEvidence;

/** Fixed CA-verified internal endpoint. Browser credentials and selectors are never forwarded. */
public final class IsolatedInspectionAdapter implements StructureInspectionPort, AutoCloseable {
    private static final Set<String> FIELDS=Set.of("schemaVersion","format","mimeType","sizeBytes","sha256","pageCount","inspectorVersion","profileDigest");
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client;
    private final URI endpoint;
    private final String authorization,profile,image;
    private final Clock clock;
    private final Duration deadline;
    public IsolatedInspectionAdapter(URI endpoint,SSLContext tls,byte[] key,String profileDigest,String imageDigest,Clock clock) {
        this(endpoint,tls,key,profileDigest,imageDigest,clock,Duration.ofSeconds(60));
    }
    // Java-only shorter deadline seam; production always uses the frozen sixty-second cap.
    IsolatedInspectionAdapter(URI endpoint,SSLContext tls,byte[] key,String profileDigest,String imageDigest,Clock clock,Duration deadline) {
        if(deadline.isZero() || deadline.isNegative() || deadline.compareTo(Duration.ofSeconds(60))>0)throw new IllegalArgumentException("Invalid deadline");
        this.deadline=deadline;
        if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || !"/internal/v1/inspect".equals(endpoint.getRawPath()) || key.length!=32 || !profileDigest.matches("[0-9a-f]{64}") || !imageDigest.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid inspector configuration");
        this.endpoint=endpoint;profile=profileDigest;image=imageDigest;this.clock=Objects.requireNonNull(clock);
        authorization="Bearer "+Base64.getEncoder().encodeToString(key);
        client=HttpClient.newBuilder().sslContext(Objects.requireNonNull(tls)).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public InspectionEvidence inspect(ArtifactSource source) {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            if(Files.isSymbolicLink(source.path()) || !Files.isRegularFile(source.path(),LinkOption.NOFOLLOW_LINKS) || Files.size(source.path())!=source.sizeBytes())throw unavailable();
            var request=HttpRequest.newBuilder(endpoint).timeout(deadline).header("Authorization",authorization).header("Content-Type","application/octet-stream").header("X-DKP-Format",source.format().name()).POST(HttpRequest.BodyPublishers.ofFile(source.path())).build();
            // JDK25 cancels HttpRequest's timer when headers arrive. Bound the entire
            // asynchronous exchange, including the body, and cancel transport on failure.
            pending=client.sendAsync(request,ignored->new BoundedInspectionResponse());
            var response=pending.get(deadline.toNanos(),TimeUnit.NANOSECONDS);
            byte[] bytes=response.body();
            if(!response.headers().firstValue("Content-Type").orElse("").equals("application/json"))throw unavailable();
            JsonNode value=JSON.readTree(bytes);if(value==null || !value.isObject())throw unavailable();
            if(response.statusCode()!=200) {
                if(response.statusCode()==422 && value.size()==1 && value.has("code") && value.get("code").isString() && Set.of("INVALID_FILE","STRUCTURE_LIMIT","UNSUPPORTED_CONTENT").contains(value.get("code").asString()))throw new DmFailure(422,value.get("code").asString());
                throw unavailable();
            }
            Set<String> keys=new HashSet<>();value.propertyNames().forEach(keys::add);if(!keys.equals(FIELDS))throw unavailable();
            if(!value.get("schemaVersion").isIntegralNumber() || !value.get("schemaVersion").canConvertToInt() || value.get("schemaVersion").intValue()!=1 || !text(value,"format").equals(source.format().name()) || !text(value,"mimeType").equals(source.format().mime()) || !text(value,"sha256").equals(source.sha256()) || !text(value,"profileDigest").equals(profile) || !text(value,"inspectorVersion").equals("1") || !value.get("sizeBytes").isIntegralNumber() || !value.get("sizeBytes").canConvertToLong() || value.get("sizeBytes").longValue()!=source.sizeBytes())throw unavailable();
            JsonNode pages=value.get("pageCount");Integer pageCount=null;if(!pages.isNull()){if(!pages.isIntegralNumber() || !pages.canConvertToInt())throw unavailable();pageCount=pages.intValue();}
            return new InspectionEvidence(source.format(),source.format().mime(),source.sizeBytes(),source.sha256(),pageCount,"1",profile,image,clock.instant());
        }catch(DmFailure failure){throw failure;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}catch(Exception invalid){throw unavailable();}
        finally {if(pending!=null && !pending.isDone())pending.cancel(true);}
    }
    private static String text(JsonNode node,String field) {JsonNode value=node.get(field);if(value==null || !value.isString())throw unavailable();return value.asString();}
    private static DmFailure unavailable() {return new DmFailure(503,"UNAVAILABLE");}
    @Override public void close() {client.shutdownNow();}
}
