package org.documentknowledge.devlogging.adapters.inbound;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.Map;
import org.documentknowledge.devlogging.adapters.inbound.requests.*;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.LogFailure;

/** Only trusted server/operator callers; a browser/producer credential grants no registration authority. */
@Controller("/api/v1/dev-logs")
@Requires(beans={LogProducerRegistry.class,LogRegistrationAuthentication.class})
@ExecuteOn(LogResponseExecutor.NAME)
public final class LogRegistrationController {
    private final LogRegistrationAuthentication authentication;
    private final LogProducerRegistry registry;
    private final DeadlineLogRequests requests;
    public LogRegistrationController(LogRegistrationAuthentication authentication,LogProducerRegistry registry,DeadlineLogRequests requests) {
        this.authentication=authentication; this.registry=registry; this.requests=requests;
    }
    @Post("/registrations")
    public HttpResponse<?> register(HttpRequest<?> request,@Body byte[] bytes) {
        return execute(request,deadline->{
            var registrar=authentication.registrar(token(request));
            if(bytes!=null && bytes.length>32768) return response(413,Map.of("code","LOG_BATCH_TOO_LARGE"));
            body(request,bytes);
            var input=LogRegistrationJson.registration(bytes);
            var result=registry.register(registrar,input.artifact(),input.scope(),input.acceptUntil(),deadline);
            return response(201,Map.of("producerId",result.authority().producerId().toString(),"scopeId",result.authority().scope().id().toString(),
                "acceptUntil",result.authority().replayUntil().toString(),"producerToken",result.producerToken()));
        });
    }
    @Post("/delegations")
    public HttpResponse<?> delegate(HttpRequest<?> request,@Body byte[] bytes) {
        return execute(request,deadline->{
            var delegator=authentication.delegator(token(request));
            if(bytes!=null && bytes.length>32768) return response(413,Map.of("code","LOG_BATCH_TOO_LARGE"));
            body(request,bytes);
            var input=LogRegistrationJson.delegation(bytes);
            var result=registry.delegate(delegator,input.parent(),input.artifact(),input.scope(),input.epoch(),input.captureUntil(),deadline);
            return response(201,Map.of("producerId",result.authority().producerId().toString(),"scopeId",result.authority().scope().id().toString(),
                "captureUntil",result.captureUntil().toString(),"replayUntil",result.authority().replayUntil().toString(),"producerToken",result.producerToken()));
        });
    }
    @Delete("/registrations/{producerId}")
    public HttpResponse<?> revoke(HttpRequest<?> request,String producerId) {
        return execute(request,deadline->{
            var registrar=authentication.registrar(token(request)); parameters(request);
            registry.revoke(registrar,LogRegistrationJson.id(producerId),deadline); return response(204,null);
        });
    }
    private HttpResponse<?> execute(HttpRequest<?> request,java.util.function.Function<RequestDeadline,HttpResponse<?>> operation) {
        try { return requests.submit(LogRouteDeadline.admitted(request),operation).await(); }
        catch(LogFailure failure) {
            int status=switch(failure.kind()) {
                case INVALID -> 400; case UNAUTHENTICATED -> 401; case FORBIDDEN,EXPIRED -> 403;
                case CONFLICT -> 409; case RATE_LIMITED -> 429; default -> 503;
            };
            String code=switch(failure.kind()) {
                case INVALID -> "INVALID_LOG_BATCH"; case UNAUTHENTICATED -> "AUTHENTICATION_REQUIRED";
                case FORBIDDEN -> "LOG_SCOPE_DENIED"; case EXPIRED -> "LOG_GRANT_EXPIRED";
                case CONFLICT -> "EVENT_CONFLICT"; case RATE_LIMITED -> "LOG_RATE_LIMITED";
                case CAPACITY -> "LOG_CAPACITY_EXHAUSTED"; default -> "LOG_STORE_UNAVAILABLE";
            };
            return response(status,Map.of("code",code));
        }
    }
    private static String token(HttpRequest<?> request) {
        var values=request.getHeaders().getAll(HttpHeaders.AUTHORIZATION);
        if(values.size()!=1 || !values.getFirst().regionMatches(true,0,"Bearer ",0,7)) throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        return values.getFirst().substring(7);
    }
    private static void body(HttpRequest<?> request,byte[] bytes) {
        parameters(request);
        if(bytes==null || bytes.length==0 || !request.getHeaders().getAll(HttpHeaders.CONTENT_ENCODING).isEmpty()
                || request.getContentType().isEmpty() || !request.getContentType().get().getName().equals(MediaType.APPLICATION_JSON))
            throw new LogFailure(LogFailure.Kind.INVALID);
    }
    private static void parameters(HttpRequest<?> request) {
        if(request.getUri().toASCIIString().length()>4096 || !request.getParameters().asMap().isEmpty()) throw new LogFailure(LogFailure.Kind.INVALID);
    }
    private static HttpResponse<?> response(int status,Object body) {
        return HttpResponse.status(HttpStatus.valueOf(status)).body(body).contentType(MediaType.APPLICATION_JSON_TYPE)
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff");
    }
}
