package org.documentknowledge.documentmanagement.bootstrap;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.*;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.token.jwt.signature.jwks.*;
import jakarta.inject.Singleton;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.documentknowledge.security.BoundedJwkSetFetcher;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class SecurityIntegrationTest {
    static final String ISSUER="https://identity.example/realms/dkp";
    static final String JWKS=ISSUER+"/protocol/openid-connect/certs";
    static final RSAKey KEY=key("primary");
    static final java.util.concurrent.atomic.AtomicBoolean KEYS_UNAVAILABLE=new java.util.concurrent.atomic.AtomicBoolean();
    static RSAKey key(String kid) {try{return new RSAKeyGenerator(2048).keyID(kid).generate();}catch(Exception e){throw new IllegalStateException(e);}}
    @Factory
    @Requires(property="dkp.test.jwks",value="true")
    static class TestKeys {
        @Singleton @Replaces(HttpClientJwksClient.class)
        JwksClient client(){return (provider,url)->KEYS_UNAVAILABLE.get()?Mono.error(new IllegalStateException("test provider unavailable")):Mono.just(new JWKSet(KEY.toPublicJWK()).toString());}
    }
    Map<String,Object> settings(String profile) {
        Map<String,Object> c=new HashMap<>();
        c.put("micronaut.server.port",-1);c.put("micronaut.server.host","127.0.0.1");c.put("dkp.test.jwks",true);
        c.put("dkp.security.profile",profile);c.put("dkp.security.issuer",ISSUER);c.put("dkp.security.standalone-issuer",ISSUER);
        c.put("dkp.security.authorized-parties",List.of("dkp-bff"));c.put("dkp.security.jwks-url",JWKS);
        c.put("micronaut.security.token.jwt.signatures.jwks.dkp.url",JWKS);
        c.put("micronaut.security.token.jwt.claims-validators.issuer",ISSUER);
        return c;
    }
    Map<String,Object> claims() {
        return new HashMap<>(Map.of("iss",ISSUER,"sub","user","exp",Instant.now().plusSeconds(300).getEpochSecond(),
            "aud",List.of("dkp-dm","dkp-kb","dkp-search"),"azp","dkp-bff","typ","Bearer","dkp_tenants",List.of("tenant-a","tenant-b")));
    }
    String token(Map<String,Object> claims,RSAKey signingKey,String kid,JWSAlgorithm algorithm) throws Exception {
        var builder=new JWSHeader.Builder(algorithm);if(kid!=null)builder.keyID(kid);
        var jwt=new SignedJWT(builder.build(),JWTClaimsSet.parse(claims));jwt.sign(new RSASSASigner(signingKey));return jwt.serialize();
    }
    String token(Map<String,Object> claims) throws Exception {return token(claims,KEY,"primary",JWSAlgorithm.RS256);}
    HttpResponse<String> call(EmbeddedServer server,String token,String tenant) throws Exception {
        var request=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/security/context"))
            .header("X-Tenant-Id","forged").header("X-User-Id","admin").header("X-Spiffe-Id","spiffe://forged").GET();
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(tenant!=null)request.header("X-DKP-Tenant",tenant);
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void independentlyProtectsRealHttpBoundaryAndSafeTenantContext() throws Exception {
        try(var server=ApplicationContext.run(EmbeddedServer.class,settings("standalone"))) {
            assertInstanceOf(org.documentknowledge.security.StrictReactiveJwksSignature.class,
                server.getApplicationContext().getBean(io.micronaut.security.token.jwt.nimbus.ReactiveJwksSignature.class));
            String good=token(claims());
            for(String tenant:List.of("tenant-a","tenant-b")) {
                var response=call(server,good,tenant);assertEquals(200,response.statusCode(),response.body());
                assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
                var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
                assertEquals(4,body.size());assertEquals("dm",body.get("service").asText());
                assertEquals(ISSUER,body.get("issuer").asText());assertEquals("user",body.get("subject").asText());assertEquals(tenant,body.get("tenant").asText());
            }
            assertEquals(401,call(server,null,"tenant-a").statusCode());assertEquals(401,call(server,"malformed","tenant-a").statusCode());
            assertEquals(403,call(server,good,"tenant-c").statusCode());assertEquals(403,call(server,good,null).statusCode());
            for(String field:List.of("iss","sub","exp","aud","azp","typ")) {
                var bad=claims();bad.remove(field);assertEquals(401,call(server,token(bad),"tenant-a").statusCode(),"missing "+field);
            }
            for(var entry:Map.<String,Object>ofEntries(Map.entry("iss","http://identity.example/realms/dkp"),Map.entry("sub"," "),Map.entry("exp",Instant.now().minusSeconds(1).getEpochSecond()),Map.entry("nbf",Instant.now().plusSeconds(300).getEpochSecond()),Map.entry("aud",List.of("wrong")),Map.entry("azp","untrusted"),Map.entry("typ","ID")).entrySet()) {
                var bad=claims();bad.put(entry.getKey(),entry.getValue());assertEquals(401,call(server,token(bad),"tenant-a").statusCode(),"wrong "+entry.getKey());
            }
            assertEquals(401,call(server,token(claims(),KEY,null,JWSAlgorithm.RS256),"tenant-a").statusCode());
            assertEquals(401,call(server,token(claims(),KEY,"unknown",JWSAlgorithm.RS256),"tenant-a").statusCode());
            assertEquals(401,call(server,token(claims(),KEY,"primary",JWSAlgorithm.RS512),"tenant-a").statusCode());
            assertEquals(401,call(server,token(claims(),key("primary"),"primary",JWSAlgorithm.RS256),"tenant-a").statusCode());
            for(Object membership:List.of(List.of(),List.of("tenant-a","tenant-a"),"tenant-a",List.of(7))) {
                var bad=claims();bad.put("dkp_tenants",membership);assertEquals(403,call(server,token(bad),"tenant-a").statusCode());
            }
        }
    }
    @Test void externalLocalStyleGrantsAreIgnoredAndAuthorityUnavailabilityIsDistinct() throws Exception {
        try(var server=ApplicationContext.run(EmbeddedServer.class,settings("external"))) {
            var response=call(server,token(claims()),"tenant-a");
            assertEquals(503,response.statusCode(),response.body());assertTrue(response.body().contains("AUTHORITY_UNAVAILABLE"));
        }
    }
    @Test void unsupportedSecurityModeCannotRemoveStrictPoliciesOrOpenContext() throws Exception {
        for(String mode:List.of("off","unknown","")) {
            var configuration=settings("standalone");configuration.put("dkp.security.mode",mode);
            try(var server=ApplicationContext.run(EmbeddedServer.class,configuration)) {
                assertInstanceOf(org.documentknowledge.security.StrictReactiveJwksSignature.class,
                    server.getApplicationContext().getBean(io.micronaut.security.token.jwt.nimbus.ReactiveJwksSignature.class));
                var wrongParty=claims();wrongParty.put("azp","untrusted");
                var idStyle=claims();idStyle.put("typ","ID");
                for(var candidate:List.of(claims(),wrongParty,idStyle)) {
                    var response=call(server,token(candidate),"tenant-a");
                    assertEquals(401,response.statusCode(),"mode="+mode+" "+response.body());
                    assertEquals("{\"code\":\"UNAUTHENTICATED\"}",response.body());
                }
            }
        }
    }
    @Test void tenantIdentifierContractBoundaryIsEnforcedAtHttpBoundary() throws Exception {
        try(var server=ApplicationContext.run(EmbeddedServer.class,settings("standalone"))) {
            String longest="t".repeat(256);var accepted=claims();accepted.put("dkp_tenants",List.of(longest));
            var response=call(server,token(accepted),longest);assertEquals(200,response.statusCode(),response.body());
            assertTrue(response.body().contains(longest));
            String oversized="t".repeat(257);var rejected=claims();rejected.put("dkp_tenants",List.of(oversized));
            assertEquals(403,call(server,token(rejected),oversized).statusCode());
        }
    }
    @Test void unavailableRequiredKeySourceFailsClosedWhileLivenessStaysAvailable() throws Exception {
        KEYS_UNAVAILABLE.set(true);
        try(var server=ApplicationContext.run(EmbeddedServer.class,settings("standalone"))) {
            var response=call(server,token(claims()),"tenant-a");assertEquals(401,response.statusCode());
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
            assertEquals("{\"code\":\"UNAUTHENTICATED\"}",response.body());
            var probe=HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.getURI().resolve("/health/liveness")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,probe.statusCode());assertEquals("{\"status\":\"UP\"}",probe.body());
        } finally { KEYS_UNAVAILABLE.set(false); }
    }
    @Test void missingTrustConfigurationCannotOpenProtectedCapability() throws Exception {
        try(var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1"))) {
            assertEquals(401,call(server,token(claims()),"tenant-a").statusCode());
        }
    }
}
