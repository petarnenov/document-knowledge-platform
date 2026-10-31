package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.documentknowledge.browserbff.adapters.inbound.VerifiedOpenIdMapper;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BffHttpIntegrationTest {
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private EmbeddedServer server() { return ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.bff.portal-origin","https://localhost:18443")); }
    @Test void probesAndAnonymousRoutesAreClosed() throws Exception {
        try(var server=server()) {
            for(String path:List.of("/health/liveness","/health/readiness")) assertEquals(200,get(server,path,null).statusCode());
            var anonymous=get(server,"/api/v1/session",null);assertEquals(401,anonymous.statusCode());assertEquals("{\"code\":\"AUTHENTICATION_REQUIRED\"}",anonymous.body());
            var unknown=get(server,"/api/v1/unknown",null);assertTrue(unknown.statusCode()>=400);assertTrue(unknown.body().contains("\"code\":"));assertFalse(unknown.body().contains("_links"));
            assertEquals(401,get(server,"/api/v1/security/context/dm",null).statusCode());
            assertEquals(404,get(server,"/api/v1/security/context/https:%2F%2Fevil.example",null).statusCode());
            for(String path:List.of("/logout","/api/v1/session/logout","/api/v1/session/tenant","/env","/beans","/api/v1/documents")) assertTrue(get(server,path,null).statusCode()>=400,path);
            assertEquals(401,post(server,"/api/v1/session/tenant",null,"{}",null,null,"application/json").statusCode());
        }
    }
    @Test void safeSessionsRotateAndMutationsRequireBothGuardsForJson() throws Exception {
        try(var server=server()) {
            Session original=seed(server,Instant.now().plusSeconds(240),true);String cookie="__Host-DKP-SESSION="+original.getId();
            var initial=get(server,"/api/v1/session",cookie);assertEquals(200,initial.statusCode());assertEquals("no-store",initial.headers().firstValue("Cache-Control").orElseThrow());
            assertFalse(initial.body().contains("synthetic-access"));assertFalse(initial.body().contains(original.getId()));
            var fields=server.getApplicationContext().getBean(io.micronaut.json.JsonMapper.class).readValue(initial.body(),Map.class);assertEquals(java.util.Set.of("issuer","subject","displayName","tenants","selectedTenant","csrfToken"),fields.keySet());assertTrue(fields.containsKey("selectedTenant"));assertNull(fields.get("selectedTenant"));
            for(String origin:new String[]{null,"https://evil.example","https://localhost:18443.evil.example"}) assertEquals(403,post(server,"/api/v1/session/tenant",cookie,"{\"tenantId\":\"tenant-a\"}",".fixture-csrf",origin,"application/json").statusCode());
            for(String csrf:new String[]{null,"bad"}) assertEquals(403,post(server,"/api/v1/session/tenant",cookie,"{\"tenantId\":\"tenant-a\"}",csrf,"https://localhost:18443","application/json").statusCode());
            assertEquals(403,post(server,"/api/v1/session/tenant",cookie,"{\"tenantId\":\"forged\"}",".fixture-csrf","https://localhost:18443","application/json").statusCode());
            assertEquals(400,post(server,"/api/v1/session/tenant",cookie,"{\"tenantId\":\"tenant-a\",\"extra\":true}",".fixture-csrf","https://localhost:18443","application/json").statusCode());
            assertTrue(post(server,"/api/v1/session/tenant",cookie,"tenantId=tenant-a",".fixture-csrf","https://localhost:18443","text/plain").statusCode()>=400);
            assertEquals(200,get(server,"/api/v1/session",cookie).statusCode());assertFalse(original.contains("dkp.selected.tenant"));
            var selected=post(server,"/api/v1/session/tenant",cookie,"{\"tenantId\":\"tenant-b\"}",".fixture-csrf","https://localhost:18443","application/json");assertEquals(200,selected.statusCode(),selected.body());
            String newCookie=selected.headers().firstValue("Set-Cookie").orElseThrow();assertTrue(newCookie.toLowerCase().contains("secure"),newCookie);assertTrue(newCookie.toLowerCase().contains("httponly"),newCookie);assertTrue(newCookie.toLowerCase().contains("samesite=lax"),newCookie);assertTrue(newCookie.toLowerCase().contains("path=/"),newCookie);assertFalse(newCookie.toLowerCase().contains("domain="),newCookie);newCookie=newCookie.split(";",2)[0];
            assertNotEquals(cookie,newCookie);assertEquals(401,get(server,"/api/v1/session",cookie).statusCode());
            Session next=store(server).findSession(newCookie.substring(newCookie.indexOf('=')+1)).get().orElseThrow();String csrf=next.get("csrfToken",String.class).orElseThrow();assertNotEquals(".fixture-csrf",csrf);
            assertEquals(503,get(server,"/api/v1/security/context/dm",newCookie).statusCode());
            assertEquals(400,post(server,"/api/v1/session/logout",newCookie,"{\"unexpected\":true}",csrf,"https://localhost:18443","application/json").statusCode());
            var logout=post(server,"/api/v1/session/logout",newCookie,"{}",csrf,"https://localhost:18443","application/json");assertEquals(204,logout.statusCode(),logout.body());assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().toLowerCase().contains("max-age=0"));
            assertEquals(401,get(server,"/api/v1/session",newCookie).statusCode());
        }
    }
    @Test void tenantAndIdentityWireBoundsAreExplicitAtHttpBoundary() throws Exception {
        try(var server=server()) {
            String bounded="a".repeat(256);var identity=new BrowserIdentity("https://issuer.example",bounded,bounded,List.of(bounded),true,"synthetic-access",Instant.now().plusSeconds(60));
            var session=store(server).newSession();session.put(SecurityFilter.AUTHENTICATION,Authentication.build(bounded,Map.of(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE,identity)));session.put("csrfToken",".fixture-csrf");store(server).save(session).get();String cookie="__Host-DKP-SESSION="+session.getId();
            var response=get(server,"/api/v1/session",cookie);assertEquals(200,response.statusCode());var fields=server.getApplicationContext().getBean(io.micronaut.json.JsonMapper.class).readValue(response.body(),Map.class);assertEquals(256,((String)fields.get("subject")).length());assertEquals(256,((String)fields.get("displayName")).length());assertTrue(fields.containsKey("selectedTenant"));assertNull(fields.get("selectedTenant"));
            var rejected=post(server,"/api/v1/session/tenant",cookie,"{\"tenantId\":\""+bounded+"a\"}",".fixture-csrf","https://localhost:18443","application/json");assertEquals(400,rejected.statusCode());assertTrue(rejected.body().contains("INVALID_REQUEST"));assertEquals(200,get(server,"/api/v1/session",cookie).statusCode());
            var selected=post(server,"/api/v1/session/tenant",cookie,"{\"tenantId\":\""+bounded+"\"}",".fixture-csrf","https://localhost:18443","application/json");assertEquals(200,selected.statusCode());
        }
    }
    @Test void tokenExpiryUnavailableEntitlementsAndNoSelectionFailClosed() throws Exception {
        try(var server=server()) {
            for(var item:List.of(Map.entry(Instant.now().minusSeconds(1),true),Map.entry(Instant.now().plusSeconds(60),false))) {
                Session session=seed(server,item.getKey(),item.getValue());assertEquals(item.getValue()?401:503,get(server,"/api/v1/session","__Host-DKP-SESSION="+session.getId()).statusCode());
            }
            var session=seed(server,Instant.now().plusSeconds(60),true);assertEquals(403,get(server,"/api/v1/security/context/dm","__Host-DKP-SESSION="+session.getId()).statusCode());
        }
    }
    @SuppressWarnings("unchecked") private SessionStore<Session> store(EmbeddedServer server) { return server.getApplicationContext().getBean(SessionStore.class); }
    private Session seed(EmbeddedServer server,Instant expiry,boolean membership) throws Exception {
        var identity=new BrowserIdentity("https://issuer.example","staff-1","Synthetic Staff",List.of("tenant-a","tenant-b"),membership,"synthetic-access",expiry);
        Session session=store(server).newSession();session.put(SecurityFilter.AUTHENTICATION,Authentication.build("staff-1",Map.of(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE,identity)));session.put("csrfToken",".fixture-csrf");store(server).save(session).get();return session;
    }
    private HttpResponse<String> get(EmbeddedServer server,String path,String cookie) throws Exception {var req=HttpRequest.newBuilder(server.getURI().resolve(path)).GET();if(cookie!=null)req.header("Cookie",cookie);return client.send(req.build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> post(EmbeddedServer server,String path,String cookie,String body,String csrf,String origin,String type) throws Exception {var req=HttpRequest.newBuilder(server.getURI().resolve(path)).header("Content-Type",type).POST(HttpRequest.BodyPublishers.ofString(body));if(cookie!=null)req.header("Cookie",cookie);if(csrf!=null)req.header("X-CSRF-TOKEN",csrf);if(origin!=null)req.header("Origin",origin);return client.send(req.build(),HttpResponse.BodyHandlers.ofString());}
}
