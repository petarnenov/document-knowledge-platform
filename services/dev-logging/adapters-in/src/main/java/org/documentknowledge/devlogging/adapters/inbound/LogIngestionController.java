package org.documentknowledge.devlogging.adapters.inbound;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import org.documentknowledge.devlogging.adapters.inbound.requests.*;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.*;

/** Producer-scoped commit/receipt boundary; browsers require the separate BFF context/Beacon adapter. */
@Controller("/api/v1/dev-logs")
@Requires(beans={LogProducerAuthentication.class,LogManifestCatalog.class,LogPersistence.class})
@ExecuteOn(LogResponseExecutor.NAME)
public final class LogIngestionController {
    private final LogProducerAuthentication authentication;
    private final LogManifestCatalog manifests;
    private final LogPersistence persistence;
    private final DeadlineLogRequests requests;
    private final Clock clock;
    public LogIngestionController(LogProducerAuthentication authentication,LogManifestCatalog manifests,LogPersistence persistence,
                                  DeadlineLogRequests requests,Clock clock) {
        this.authentication=authentication;this.manifests=manifests;this.persistence=persistence;this.requests=requests;this.clock=clock;
    }
    @Post("/batches")
    public HttpResponse<?> batches(HttpRequest<?> request,@Body byte[] bytes) {
        return execute(request,deadline->{
            var authority=authentication.authenticate(token(request),deadline);body(request,bytes);
            var manifest=manifests.load(authority,deadline);
            var batch=LogIngestionJson.batch(bytes,authority,manifest,deadline);
            var receipts=persistence.commit(authority,batch,clock.instant(),deadline);
            var rows=receipts.stream().map(item->Map.of("eventId",item.eventId().toString(),"state","COMMITTED","receivedAt",item.receivedAt().toString())).toList();
            return response(200,Map.of("receipts",rows));
        });
    }
    @Post("/receipts")
    public HttpResponse<?> receipts(HttpRequest<?> request,@Body byte[] bytes) {
        return execute(request,deadline->{
            var authority=authentication.authenticate(token(request),deadline);body(request,bytes);
            var result=persistence.receipts(authority,LogIngestionJson.receipts(bytes),clock.instant(),deadline);
            var rows=new ArrayList<Map<String,Object>>();
            for(var item:result) {
                var row=new HashMap<String,Object>();row.put("eventId",item.eventId().toString());row.put("state",item.state().name());
                if(item.receivedAt()!=null)row.put("receivedAt",item.receivedAt().toString());rows.add(row);
            }
            return response(200,Map.of("receipts",rows));
        });
    }
    private HttpResponse<?> execute(HttpRequest<?> request,Function<RequestDeadline,HttpResponse<?>> operation) {
        try {return requests.submit(LogRouteDeadline.admitted(request),operation).await();}
        catch(LogFailure failure) {
            int status=switch(failure.kind()) {
                case INVALID,UNREGISTERED -> 400;case UNAUTHENTICATED -> 401;case FORBIDDEN,EXPIRED -> 403;
                case CONFLICT -> 409;case EVENT_EXPIRED -> 410;case TOO_LARGE -> 413;case RATE_LIMITED -> 429;default -> 503;
            };
            String code=switch(failure.kind()) {
                case INVALID -> "INVALID_LOG_BATCH";case UNREGISTERED -> "UNREGISTERED_DIAGNOSTIC";case UNAUTHENTICATED -> "AUTHENTICATION_REQUIRED";
                case FORBIDDEN -> "LOG_SCOPE_DENIED";case EXPIRED -> "LOG_GRANT_EXPIRED";case EVENT_EXPIRED -> "LOG_EVENT_EXPIRED";
                case CONFLICT -> "EVENT_CONFLICT";case TOO_LARGE -> "LOG_BATCH_TOO_LARGE";case RATE_LIMITED -> "LOG_RATE_LIMITED";
                case CAPACITY -> "LOG_CAPACITY_EXHAUSTED";default -> "LOG_STORE_UNAVAILABLE";
            };
            return response(status,Map.of("code",code));
        }
    }
    private static String token(HttpRequest<?> request) {
        var headers=request.getHeaders().getAll(HttpHeaders.AUTHORIZATION);
        if(headers.size()!=1 || !headers.getFirst().regionMatches(true,0,"Bearer ",0,7))throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        return headers.getFirst().substring(7);
    }
    private static void body(HttpRequest<?> request,byte[] bytes) {
        if(bytes!=null && bytes.length>32768)throw new LogFailure(LogFailure.Kind.TOO_LARGE);
        if(bytes==null || bytes.length==0 || request.getUri().toASCIIString().length()>4096 || !request.getParameters().asMap().isEmpty()
                || !request.getHeaders().getAll(HttpHeaders.CONTENT_ENCODING).isEmpty() || request.getContentType().isEmpty()
                || !request.getContentType().get().getName().equals(MediaType.APPLICATION_JSON))throw new LogFailure(LogFailure.Kind.INVALID);
    }
    private static HttpResponse<?> response(int status,Object value) {
        return HttpResponse.status(HttpStatus.valueOf(status)).body(value).contentType(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff");
    }
}
