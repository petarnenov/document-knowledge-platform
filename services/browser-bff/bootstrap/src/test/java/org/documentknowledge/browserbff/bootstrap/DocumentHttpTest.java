package org.documentknowledge.browserbff.bootstrap;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.adapters.inbound.VerifiedOpenIdMapper;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentHttpTest {
    private final HttpClient client=HttpClient.newHttpClient();
    @Test void everyFixedRouteUsesRealSessionGuardsAndReconstructedUpstreamContext() throws Exception {
        var calls=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/api/v1/documents", exchange->{calls.incrementAndGet();assertEquals("Bearer synthetic-access",exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("tenant-a",exchange.getRequestHeaders().getFirst("X-DKP-Tenant"));assertNull(exchange.getRequestHeaders().getFirst("X-User-Id"));String body=exchange.getRequestMethod().equals("GET")?"{\"code\":\"NOT_FOUND\"}":"{\"documentId\":\""+DocumentGatewayTest.ID+"\",\"revision\":1}";int status=exchange.getRequestMethod().equals("GET")?404:exchange.getRequestMethod().equals("POST")?201:200;if(exchange.getRequestURI().getPath().endsWith("/versions")){body="{\"code\":\"UNAVAILABLE\"}";status=503;}exchange.getResponseHeaders().set("Content-Type","application/json");byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}});upstream.start();
        try(var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.bff.portal-origin","https://localhost:18443","dkp.bff.upstreams.dm","http://127.0.0.1:"+upstream.getAddress().getPort()))) {
            String path="/api/v1/dm/documents/"+DocumentGatewayTest.ID;String cookie=seed(server);var json=server.getApplicationContext().getBean(JsonMapper.class);
            assertEquals(401,send(server,"GET",path,null,null,false).statusCode());
            for(String suffix:List.of("","/acl","/legal-holds"))assertEquals(404,send(server,"GET",path+suffix,cookie,null,false).statusCode());
            assertEquals(404,send(server,"GET","/api/v1/dm/documents?limit=20&cursor=opaque",cookie,null,false).statusCode());
            String create=json.writeValueAsString(DocumentGatewayTest.CREATE), update=json.writeValueAsString(DocumentGatewayTest.UPDATE);
            var acl=Map.of("expectedRevision",0,"mode","RESTRICT","changes",List.of(Map.of("principal",Map.of("issuer","https://issuer.example","subject","staff-1"),"action","document.inspect-draft","operation","REMOVE_ALLOW")));
            var mutations=List.of(new String[]{"POST","/api/v1/dm/documents",create},new String[]{"PATCH",path,update},new String[]{"PATCH",path+"/acl",json.writeValueAsString(acl)},new String[]{"POST",path+"/legal-holds","{\"expectedRevision\":0,\"reason\":\"Synthetic hold\"}"},new String[]{"POST",path+"/versions","{\"expectedRevision\":0,\"receiptId\":\"opaque\"}"});
            for(var mutation:mutations){int before=calls.get();assertEquals(403,send(server,mutation[0],mutation[1],cookie,mutation[2],false).statusCode());assertEquals(before,calls.get());var result=send(server,mutation[0],mutation[1],cookie,mutation[2],true);assertEquals(mutation[1].endsWith("/versions")?503:mutation[0].equals("POST")?201:200,result.statusCode(),result.body());assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());if(result.statusCode()<300)assertEquals(java.util.Set.of("documentId","revision"),json.readValue(result.body(),Map.class).keySet());}
            for(String invalid:List.of(path+"?target=evil","/api/v1/dm/documents?limit=51","/api/v1/dm/documents?limit=1&limit=2","/api/v1/dm/documents?cursor="))assertEquals(400,send(server,"GET",invalid,cookie,null,false).statusCode(),invalid);
            for(String invalid:List.of("/api/v1/dm/documents/not-a-uuid",path+"/unexpected","/api/v1/dm/documents/11111111%2f1111-4111-8111-111111111111","/api/v1/dm/documents/11111111-1111-4111-8111-11111111111%31"))assertTrue(send(server,"GET",invalid,cookie,null,false).statusCode()>=400);
            for(String header:List.of("Idempotency-Key","Origin","X-CSRF-TOKEN")) {
                var duplicate=HttpRequest.newBuilder(server.getURI().resolve(path)).header("Cookie",cookie).header(header,"one").header(header,"two").GET().build();
                int before=calls.get();assertTrue(client.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode() >= 400);assertEquals(before,calls.get());
            }
            assertEquals(400,send(server,"PATCH",path,cookie,"{\"expectedRevision\":0,\"tenant\":\"forged\"}",true).statusCode());
            assertTrue(send(server,"PUT",path+"/acl",cookie,json.writeValueAsString(acl),true).statusCode()>=400);
            assertTrue(send(server,"POST","/api/v1/dm/documents",cookie,"{\"oversize\":\""+"x".repeat(17000)+"\"}",true).statusCode()>=400);
        } finally {upstream.stop(0);}
    }
    @SuppressWarnings("unchecked") private String seed(EmbeddedServer server) throws Exception {
        SessionStore<Session> store=server.getApplicationContext().getBean(SessionStore.class);Session session=store.newSession();var identity=new BrowserIdentity("https://issuer.example","staff-1","Synthetic",List.of("tenant-a"),true,"synthetic-access",Instant.now().plusSeconds(120));session.put(SecurityFilter.AUTHENTICATION,Authentication.build("staff-1",Map.of(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE,identity)));session.put("csrfToken",".fixture-csrf");session.put("dkp.selected.tenant","tenant-a");store.save(session).get();return "__Host-DKP-SESSION="+session.getId();
    }
    private HttpResponse<String> send(EmbeddedServer server,String method,String path,String cookie,String body,boolean guarded) throws Exception {
        var request=HttpRequest.newBuilder(server.getURI().resolve(path)).method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));if(cookie!=null)request.header("Cookie",cookie);if(body!=null)request.header("Content-Type","application/json");if(guarded)request.header("Origin","https://localhost:18443").header("X-CSRF-TOKEN",".fixture-csrf").header("Idempotency-Key","retry-key");request.header("X-DKP-Tenant","forged").header("X-User-Id","forged");return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
