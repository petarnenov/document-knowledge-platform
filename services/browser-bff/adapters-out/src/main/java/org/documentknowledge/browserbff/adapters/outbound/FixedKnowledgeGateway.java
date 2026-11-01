package org.documentknowledge.browserbff.adapters.outbound;

import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

/** KB has its own response and time budgets; legacy DM transport is deliberately independent. */
@Singleton
public final class FixedKnowledgeGateway implements KnowledgeGateway {
    private static final tools.jackson.databind.json.JsonMapper STRICT=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final KnowledgeWire wire;private final String upstream;
    private final JsonMapper json;
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
    public FixedKnowledgeGateway(@Value("${dkp.bff.upstreams.kb:}") String upstream,JsonMapper json,KnowledgeWire wire){this.wire=wire;this.upstream=upstream;this.json=json;}
    @Override @SuppressWarnings("unchecked") public Result exchange(String method,String path,Map<String,String> query,Map<String,Object> body,String key,BrowserIdentity identity,String tenant,KnowledgeDeadline deadline){
        var route=wire.request(method,path,query,body,key);deadline.check();
        try{
            URI base=URI.create(upstream);
            if(!Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!Set.of("","/").contains(base.getPath()))throw new IllegalArgumentException();
            String parameters=query.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
            var request=HttpRequest.newBuilder(base.resolve(path+(parameters.isEmpty()?"":"?"+parameters))).timeout(Duration.ofNanos(deadline.remainingNanos())).header("Authorization","Bearer "+identity.accessToken()).header("X-DKP-Tenant",tenant).header("Accept","application/json");
            if(method.equals("GET"))request.GET();else{
                byte[] bytes=json.writeValueAsBytes(body);if(bytes.length>16384)throw new BffFailure(413,"PAYLOAD_TOO_LARGE");
                request.header("Content-Type","application/json");if(route.idempotent())request.header("Idempotency-Key",key);
                request.method(method,HttpRequest.BodyPublishers.ofByteArray(bytes));
            }
            var pending=client.sendAsync(request.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),2097152));
            deadline.own(pending);
            HttpResponse<byte[]> response;
            try{response=pending.get(deadline.remainingNanos(),TimeUnit.NANOSECONDS);}catch(Exception failure){pending.cancel(true);throw failure;}
            deadline.check();
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json")||response.headers().firstValue("Content-Encoding").isPresent()||!response.headers().allValues("Cache-Control").equals(List.of("no-store")))throw new IllegalArgumentException();
            Map<String,Object> fields=STRICT.readValue(response.body(),Map.class);int status=response.statusCode();
            if(status==route.status()){
                if(!wire.validate(route.response(),fields)||!bound(fields,path,Integer.parseInt(query.getOrDefault("limit","20"))))throw new IllegalArgumentException();
            }else if(!Set.of(400,401,403,404,406,409,413,415,422,429,503).contains(status)||!wire.validate("Error",fields))throw new IllegalArgumentException();
            deadline.check();return new Result(status,fields);
        }catch(BffFailure failure){throw failure;}catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new BffFailure(503,"UNAVAILABLE");}
    }
    private static boolean bound(Map<String,Object> fields,String path,int limit){
        String[] segments=path.split("/");
        if(segments.length>5){String name=segments[4].equals("collections")?"collectionId":"documentId";if(fields.containsKey(name)&&!fields.get(name).equals(segments[5]))return false;}
        if(fields.get("items") instanceof List<?> items){if(items.size()>limit)return false;for(Object item:items){if(!(item instanceof Map<?,?> m))return false;if(segments.length>5&&!Objects.equals(m.get("collectionId"),segments[5]))return false;}if(new HashSet<>(items.stream().map(item->((Map<?,?>)item).get(path.endsWith("/memberships")?"membershipId":"collectionId")).toList()).size()!=items.size())return false;}
        if(segments.length>7&&fields.containsKey("membershipId")&&!fields.get("membershipId").equals(segments[7]))return false;
        return true;
    }
}
