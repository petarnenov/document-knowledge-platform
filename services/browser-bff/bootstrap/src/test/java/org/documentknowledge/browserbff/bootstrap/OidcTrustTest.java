package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.Map;
import org.documentknowledge.browserbff.adapters.inbound.OidcTrust;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OidcTrustTest {
    @Test void productionTrustRequiresHttpsExactIssuerAndSameOriginEndpoints() {
        try(var context=ApplicationContext.run()) {
            var env=context.getEnvironment();String issuer="https://issuer.example/realm";
            var trust=new OidcTrust(issuer,true,"dkp-bff","synthetic-secret","https://portal.example/oauth/callback/keycloak","https://portal.example",env);
            trust.configuration(issuer,null);trust.configuration(issuer,"https://issuer.example/jwks");
            trust.metadata(issuer,"https://issuer.example/authorize","https://issuer.example/token","https://issuer.example/jwks");
            for(String unsafe:List.of("http://issuer.example/realm","", "relative", "https://user@issuer.example/realm", "https://issuer.example/realm?override=1", "https://issuer.example/realm#fragment","http://localhost:1234/realm")) assertThrows(IllegalArgumentException.class,()->new OidcTrust(unsafe,true,"dkp-bff","secret","https://portal.example/callback","https://portal.example",env));
            assertThrows(IllegalArgumentException.class,()->new OidcTrust(issuer,true,"","secret","https://portal.example/callback","https://portal.example",env));
            assertThrows(IllegalArgumentException.class,()->new OidcTrust(issuer,true,"x".repeat(257),"secret","https://portal.example/callback","https://portal.example",env));
            assertThrows(IllegalArgumentException.class,()->new OidcTrust(issuer,true,"dkp-bff","","https://portal.example/callback","https://portal.example",env));
            assertThrows(IllegalArgumentException.class,()->new OidcTrust(issuer,true,"dkp-bff","secret","https://other.example/callback","https://portal.example",env));
            assertThrows(IllegalArgumentException.class,()->trust.configuration(issuer+"/wrong",null));
            for(String unsafe:List.of("http://issuer.example/token","https://other.example/token","https://issuer.example/token?x=1","ftp://issuer.example/token")) {
                assertThrows(IllegalArgumentException.class,()->trust.metadata(issuer,issuer+"/authorize",unsafe,issuer+"/jwks"));
                assertThrows(IllegalArgumentException.class,()->trust.configuration(issuer,unsafe));
            }
            assertThrows(IllegalArgumentException.class,()->trust.metadata("https://other.example",issuer+"/authorize",issuer+"/token",issuer+"/jwks"));
        }
    }
    @Test void onlyExplicitIsolatedTestEnvironmentCanUseLoopbackHttp() {
        try(var context=ApplicationContext.run(Map.of(),"test-oidc-loopback")) {
            var trust=new OidcTrust("http://127.0.0.1:1234/issuer",true,"dkp-bff","secret","http://localhost:4321/callback","http://localhost:4321",context.getEnvironment());
            trust.configuration("http://127.0.0.1:1234/issuer",null);trust.metadata("http://127.0.0.1:1234/issuer","http://127.0.0.1:1234/auth","http://127.0.0.1:1234/token","http://127.0.0.1:1234/jwks");
            assertThrows(IllegalArgumentException.class,()->new OidcTrust("http://remote.example/issuer",true,"dkp-bff","secret","http://localhost:4321/callback","http://localhost:4321",context.getEnvironment()));
        }
    }
    @Test void disabledLoginClosesCapabilityWithoutRequiringInfrastructure() {
        try(var context=ApplicationContext.run()) {var trust=new OidcTrust("",false,"","","","",context.getEnvironment());trust.configuration("",null);trust.metadata("",null,null,null);}
    }
    @Test void unsafeConfiguredIssuerIsRejectedBeforeAnyDiscoveryConnection() {
        var config=Map.<String,Object>of("micronaut.security.oauth2.clients.keycloak.enabled",true,"micronaut.security.oauth2.clients.keycloak.openid.issuer","http://127.0.0.1:1/issuer","dkp.bff.issuer","http://127.0.0.1:1/issuer","micronaut.security.oauth2.clients.keycloak.client-secret","synthetic-secret","dkp.bff.portal-origin","https://localhost:18443");
        var failure=assertThrows(RuntimeException.class,()->ApplicationContext.run(config));Throwable root=failure;while(root.getCause()!=null)root=root.getCause();assertTrue(root.getMessage().contains("Unsafe OIDC trust configuration"),root.getClass().getSimpleName());
    }
}
