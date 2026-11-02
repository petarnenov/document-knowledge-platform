package org.documentknowledge.devlogging.adapters.inbound;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.core.annotation.NonBlocking;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.devlogging.adapters.inbound.requests.LogRouteDeadline;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.LogFailure;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Bounded event-loop control, independent of both blocking logging worker pools and SQL. */
@Controller("/api/v1/dev-logs/storage-observation")
@Requires(beans={LogObserverAuthentication.class,LogVolumeObservation.class})
@NonBlocking
public final class LogStorageObservationController {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxDocumentLength(1024).maxStringLength(128).maxNumberLength(16).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AtomicInteger active=new AtomicInteger();
    private final LogObserverAuthentication authentication;
    private final LogVolumeObservation observation;
    public LogStorageObservationController(LogObserverAuthentication authentication,LogVolumeObservation observation){this.authentication=authentication;this.observation=observation;}
    @Post
    public HttpResponse<?> update(HttpRequest<?> request,@Body byte[] bytes) {
        if(active.incrementAndGet()>2){active.decrementAndGet();return response(429,"LOG_RATE_LIMITED");}
        try {
            var deadline=LogRouteDeadline.admitted(request);deadline.requireTime();
            var values=request.getHeaders().getAll(HttpHeaders.AUTHORIZATION);
            if(values.size()!=1 || !values.getFirst().regionMatches(true,0,"Bearer ",0,7))throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
            authentication.authenticate(values.getFirst().substring(7));deadline.requireTime();
            if(bytes!=null && bytes.length>1024)return response(413,"LOG_BATCH_TOO_LARGE");
            if(bytes==null || bytes.length==0 || request.getUri().toASCIIString().length()>4096 || !request.getParameters().asMap().isEmpty()
                    || !request.getHeaders().getAll(HttpHeaders.CONTENT_ENCODING).isEmpty() || request.getContentType().isEmpty()
                    || !request.getContentType().get().getName().equals(MediaType.APPLICATION_JSON))throw new LogFailure(LogFailure.Kind.INVALID);
            var body=parse(bytes);
            if(body==null || !body.keySet().equals(Set.of("volumeIdentity","freeBytes","totalBytes"))
                    || !(body.get("volumeIdentity") instanceof String volume) || !volume.matches("[0-9a-f]{64}"))throw new LogFailure(LogFailure.Kind.INVALID);
            long free=number(volume,body.get("freeBytes")),total=number(volume,body.get("totalBytes"));deadline.requireTime();
            observation.observe(volume,free,total);deadline.requireTime();return response(204,null);
        } catch(LogFailure failure) {
            return switch(failure.kind()) {
                case UNAUTHENTICATED -> response(401,"AUTHENTICATION_REQUIRED");
                case FORBIDDEN -> response(403,"LOG_SCOPE_DENIED");
                case EXPIRED -> response(403,"LOG_GRANT_EXPIRED");
                case INVALID -> response(400,"INVALID_LOG_BATCH");
                default -> response(503,"LOG_STORE_UNAVAILABLE");
            };
        } catch(Exception unavailable){return response(503,"LOG_STORE_UNAVAILABLE");}
        finally {active.decrementAndGet();}
    }
    private static Map<?,?> parse(byte[] bytes) {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return JSON.readValue(text,Map.class);
        } catch(Exception invalid){throw new LogFailure(LogFailure.Kind.INVALID);}
    }
    private long number(String volume,Object value) {
        if(!(value instanceof Integer) && !(value instanceof Long)) {
            // The port checks the exact volume before invalidating a trusted observation.
            observation.observe(volume,-1,0);throw new LogFailure(LogFailure.Kind.INVALID);
        }
        return ((Number)value).longValue();
    }
    private static HttpResponse<?> response(int status,String code) {
        var response=HttpResponse.status(HttpStatus.valueOf(status)).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff");
        return code==null?response:response.body(Map.of("code",code)).contentType(MediaType.APPLICATION_JSON_TYPE);
    }
}
