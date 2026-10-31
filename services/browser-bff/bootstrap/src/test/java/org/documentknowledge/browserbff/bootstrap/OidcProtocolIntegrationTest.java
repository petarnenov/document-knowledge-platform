package org.documentknowledge.browserbff.bootstrap;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled provider faults exercise real Micronaut HTTP routes and token validation, not helper stubs. */
class OidcProtocolIntegrationTest {
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @Test void frameworkCompletesCodeS256AndRotatesSessionWithoutReturningTokens() throws Exception {
        try(var fixture=new Fixture("valid")) {
            var start=fixture.begin();assertEquals("S256",start.parameters().get("code_challenge_method"));assertNotNull(start.parameters().get("nonce"));
            assertEquals(fixture.callback,start.parameters().get("redirect_uri"));
            var response=fixture.callback(start,start.parameters().get("state"));assertEquals(303,response.statusCode(),response.body());
            String cookie=cookie(response);assertNotEquals(start.cookie(),cookie);assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().toLowerCase().contains("secure"));
            var session=fixture.get("/api/v1/session",cookie);assertEquals(200,session.statusCode(),session.body());assertTrue(session.body().contains("tenant-a"));assertFalse(session.body().contains("synthetic-access"));assertFalse(session.body().contains("id_token"));
            assertEquals(401,fixture.get("/api/v1/session",start.cookie()).statusCode());assertEquals(1,fixture.provider.tokenRequests);
            var context=fixture.server.getApplicationContext();@SuppressWarnings("unchecked") var store=(SessionStore<Session>)context.getBean(SessionStore.class);
            var stored=store.findSession(cookie.substring(cookie.indexOf('=')+1)).get().orElseThrow();
            assertFalse(stored.asMap().toString().contains("refresh-secret"));assertFalse(stored.asMap().toString().contains(fixture.provider.lastIdToken));
        }
    }
    @Test void wrongStateNeverExchangesCodeOrCreatesAuthenticatedSession() throws Exception {
        try(var fixture=new Fixture("valid")) {
            var start=fixture.begin();var response=fixture.callback(start,"wrong-state");assertNotEquals(200,response.statusCode());assertEquals(0,fixture.provider.tokenRequests);
            fixture.assertUnauthenticated(start,response);
        }
    }
    @Test void idTokenAndNonceFailuresNeverAuthenticateAtHttpBoundary() throws Exception {
        for(String mode:List.of("wrong-nonce","wrong-issuer","wrong-audience","unsigned","expired","missing-sub","malformed-membership","provider-down")) {
            try(var fixture=new Fixture(mode)) {
                var start=fixture.begin();var response=fixture.callback(start,start.parameters().get("state"));fixture.assertUnauthenticated(start,response);assertEquals(1,fixture.provider.tokenRequests,mode);
            }
        }
    }
    @Test void missingAndInvalidPkceVerifierCannotCompleteCodeExchange() throws Exception {
        for(boolean missing:List.of(true,false)) try(var fixture=new Fixture("valid")) {
            var start=fixture.begin();@SuppressWarnings("unchecked") var store=(SessionStore<Session>)fixture.server.getApplicationContext().getBean(SessionStore.class);
            var session=store.findSession(start.cookie().substring(start.cookie().indexOf('=')+1)).get().orElseThrow();
            if(missing)session.remove("oauth2pkce");else session.put("oauth2pkce","invalid-verifier-value-that-does-not-match-challenge");store.save(session).get();
            var response=fixture.callback(start,start.parameters().get("state"));fixture.assertUnauthenticated(start,response);
        }
    }
    @Test void rejectedRedirectUriAndExternalLocalClaimsCannotGrantMembership() throws Exception {
        try(var fixture=new Fixture("wrong-redirect")) {var start=fixture.begin();var response=fixture.callback(start,start.parameters().get("state"));fixture.assertUnauthenticated(start,response);}
        try(var fixture=new Fixture("external")) {var start=fixture.begin();var response=fixture.callback(start,start.parameters().get("state"));assertEquals(503,fixture.get("/api/v1/session",cookie(response)).statusCode());}
    }
    @Test void providerAuthorizationErrorReturnsOnlyFixedSafePortalLocation() throws Exception {
        try(var fixture=new Fixture("valid")) {var start=fixture.begin();var response=fixture.get("/oauth/callback/keycloak?error=access_denied&state="+java.net.URLEncoder.encode(start.parameters().get("state"),StandardCharsets.UTF_8),start.cookie());assertEquals(303,response.statusCode());assertEquals("/?authentication=failed",response.headers().firstValue("Location").orElseThrow());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());fixture.assertUnauthenticated(start,response);}
    }
    private static String cookie(HttpResponse<?> response) {return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("__Host-DKP-SESSION=")).findFirst().orElseThrow().split(";",2)[0];}
    private static Map<String,String> parameters(String query) {var result=new HashMap<String,String>();if(query!=null)for(String part:query.split("&")){String[] pair=part.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}return result;}
    private record Start(String cookie,Map<String,String> parameters) { }
    private final class Fixture implements AutoCloseable {
        final Provider provider;final EmbeddedServer server;final String callback;
        Fixture(String mode) throws Exception {
            provider=new Provider(mode);int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
            callback="http://127.0.0.1:"+port+"/oauth/callback/keycloak";
            var config=new HashMap<String,Object>();config.put("micronaut.server.port",port);config.put("micronaut.server.host","127.0.0.1");
            config.put("micronaut.security.oauth2.clients.keycloak.enabled",true);config.put("micronaut.security.oauth2.clients.keycloak.client-id","dkp-bff");config.put("micronaut.security.oauth2.clients.keycloak.client-secret",provider.secret);
            config.put("micronaut.security.oauth2.clients.keycloak.openid.issuer",provider.issuer);config.put("micronaut.security.oauth2.callback-uri",callback);
            config.put("dkp.bff.issuer",provider.issuer);config.put("dkp.bff.portal-origin","http://127.0.0.1:"+port);config.put("dkp.bff.profile",mode.equals("external")?"external":"standalone");
            provider.registeredCallback=callback;server=ApplicationContext.run(EmbeddedServer.class,config,"test-oidc-loopback");
        }
        Start begin() throws Exception {
            var response=get("/oauth/login/keycloak",null);assertEquals(302,response.statusCode(),response.body());
            URI authorization=URI.create(response.headers().firstValue("Location").orElseThrow());var params=parameters(authorization.getRawQuery());provider.authorization=params;
            assertTrue(response.headers().allValues("Set-Cookie").stream().anyMatch(value -> value.startsWith("__Host-DKP-SESSION=")),response.headers().allValues("Set-Cookie").stream().map(value -> value.replaceFirst("=[^;]*", "=REDACTED")).toList().toString());
            assertNotNull(params.get("state"));assertNotNull(params.get("code_challenge"));
            @SuppressWarnings("unchecked") var sessionStore=(SessionStore<Session>)server.getApplicationContext().getBean(SessionStore.class);
            var transaction=sessionStore.findSession(cookie(response).substring(cookie(response).indexOf('=')+1)).get().orElseThrow();
            var stored=transaction.get("oauth2State",io.micronaut.security.oauth2.endpoint.authorization.state.State.class).orElseThrow();
            var serdes=server.getApplicationContext().getBean(io.micronaut.security.oauth2.endpoint.authorization.state.StateSerDes.class);
            assertNotNull(serdes.deserialize(params.get("state")),"returned state must deserialize");
            assertEquals(stored,serdes.deserialize(params.get("state")),"stored transaction state parity");
            assertTrue(transaction.contains("oauth2pkce"),"server-side verifier");assertTrue(transaction.contains("openIdNonce"),"server-side nonce");
            return new Start(cookie(response),params);
        }
        HttpResponse<String> callback(Start start,String state) throws Exception {return get("/oauth/callback/keycloak?code=synthetic-code&state="+java.net.URLEncoder.encode(state,StandardCharsets.UTF_8),start.cookie());}
        HttpResponse<String> get(String path,String cookie) throws Exception {var request=HttpRequest.newBuilder(server.getURI().resolve(path)).GET();if(cookie!=null)request.header("Cookie",cookie);return client.send(request.build(),HttpResponse.BodyHandlers.ofString());}
        void assertUnauthenticated(Start start,HttpResponse<String> response) throws Exception {
            assertEquals(401,get("/api/v1/session",start.cookie()).statusCode(),provider.mode+" prior");
            for(String value:response.headers().allValues("Set-Cookie")) assertEquals(401,get("/api/v1/session",value.split(";",2)[0]).statusCode(),provider.mode+" response");
            assertFalse(response.body().contains("synthetic-access"));assertFalse(response.body().contains("synthetic-code"));
        }
        @Override public void close(){server.close();provider.close();}
    }
    private static final class Provider implements AutoCloseable {
        final HttpServer server;final String issuer;final String mode;final RSAKey key;final String secret=UUID.randomUUID().toString();String registeredCallback;Map<String,String> authorization;int tokenRequests;String lastIdToken="";
        Provider(String mode) throws Exception {
            this.mode=mode;key=new RSAKeyGenerator(2048).keyID("synthetic-key").generate();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);issuer="http://127.0.0.1:"+server.getAddress().getPort()+"/issuer";
            server.createContext("/issuer/.well-known/openid-configuration",exchange -> respond(exchange,200,Map.of("issuer",issuer,"authorization_endpoint",issuer+"/authorize","token_endpoint",issuer+"/token","jwks_uri",issuer+"/jwks","response_types_supported",List.of("code"),"subject_types_supported",List.of("public"),"id_token_signing_alg_values_supported",List.of("RS256"),"token_endpoint_auth_methods_supported",List.of("client_secret_basic"),"code_challenge_methods_supported",List.of("S256"))));
            server.createContext("/issuer/jwks",exchange -> respond(exchange,200,new JWKSet(key.toPublicJWK()).toJSONObject()));
            server.createContext("/issuer/token",exchange -> {
                tokenRequests++;
                try {
                    if(mode.equals("provider-down")){respond(exchange,503,Map.of("error","server_error"));return;}
                    var form=parameters(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    String verifier=form.get("code_verifier");String challenge=verifier==null?"":Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                    String expectedBasic="Basic "+Base64.getEncoder().encodeToString(("dkp-bff:"+secret).getBytes(StandardCharsets.UTF_8));
                    boolean confidential=expectedBasic.equals(exchange.getRequestHeaders().getFirst("Authorization")) || ("dkp-bff".equals(form.get("client_id")) && secret.equals(form.get("client_secret")));
                    if(!confidential || !"synthetic-code".equals(form.get("code")) || !challenge.equals(authorization.get("code_challenge")) || !registeredCallback.equals(form.get("redirect_uri")) || mode.equals("wrong-redirect")) {respond(exchange,400,Map.of("error","invalid_grant"));return;}
                    var claims=new JWTClaimsSet.Builder().issuer(mode.equals("wrong-issuer")?issuer+"/other":issuer).audience(mode.equals("wrong-audience")?"other-client":"dkp-bff").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(mode.equals("expired")?-60:240))).claim("nonce",mode.equals("wrong-nonce")?"wrong-nonce":authorization.get("nonce")).claim("dkp_tenants",mode.equals("malformed-membership")?"tenant-a":List.of("tenant-a","tenant-b")).claim("name","Synthetic Staff");
                    if(!mode.equals("missing-sub"))claims.subject("staff-1");
                    if(mode.equals("unsigned"))lastIdToken=new PlainJWT(claims.build()).serialize();else {var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims.build());jwt.sign(new RSASSASigner(key));lastIdToken=jwt.serialize();}
                    respond(exchange,200,Map.of("access_token","synthetic-access","token_type","Bearer","expires_in",180,"refresh_token","refresh-secret","id_token",lastIdToken));
                } catch(Exception failure) {respond(exchange,500,Map.of("error","synthetic_provider_failure"));}
            });server.start();
        }
        private static void respond(com.sun.net.httpserver.HttpExchange exchange,int status,Map<String,?> body) throws java.io.IOException {byte[] bytes=JSONObjectUtils.toJSONString(body).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}
        @Override public void close(){server.stop(0);}
    }
}
