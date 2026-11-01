package org.documentknowledge.knowledgebase.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.time.*;
import java.util.*;
import org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeRequestFactory;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.security.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeRuntimeFactoryTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
    final Context actor=new Context(new Principal("https://issuer.example/realms/test","subject"),"tenant-a");
    TrustedTenantResolver resolver(String profile){
        var configuration=new TrustConfiguration();configuration.setMode("resource");configuration.setProfile(profile);configuration.setIssuer(actor.actor().issuer());configuration.setStandaloneIssuer(actor.actor().issuer());configuration.setAudience("dkp-kb");configuration.setAuthorizedParties(List.of("dkp-bff"));configuration.setJwksUrl("https://issuer.example/certs");return new TrustedTenantResolver(configuration);
    }
    Map<String,Object> claims(){return new HashMap<>(Map.of("iss",actor.actor().issuer(),"sub",actor.actor().subject(),"exp",clock.instant().plusSeconds(60).getEpochSecond(),"dkp_tenants",List.of("tenant-a")));}
    @Test void originalMembershipAndTokenExpiryRecheckedWithoutTargetIdentitySubstitution(){
        var resolver=resolver("standalone");var claims=claims();KnowledgeRuntimeFactory.current(resolver,actor,claims,clock);
        claims.put("exp",Date.from(clock.instant().plusSeconds(1)));KnowledgeRuntimeFactory.current(resolver,actor,claims,clock);
        claims.put("dkp_tenants",List.of("other"));assertEquals(403,assertThrows(KbFailure.class,()->KnowledgeRuntimeFactory.current(resolver,actor,claims,clock)).status());
        claims.put("dkp_tenants",List.of("tenant-a"));
        for(Object expiry:List.of(clock.instant().getEpochSecond(),Double.NaN,"100000000000",Date.from(clock.instant()))){claims.put("exp",expiry);assertEquals(401,assertThrows(KbFailure.class,()->KnowledgeRuntimeFactory.current(resolver,actor,claims,clock)).status());}
        claims.remove("exp");assertEquals(401,assertThrows(KbFailure.class,()->KnowledgeRuntimeFactory.current(resolver,actor,claims,clock)).status());
        claims.clear();claims.putAll(claims());var different=new Context(new Principal(actor.actor().issuer(),"target"),actor.tenant());var original=claims;assertEquals(503,assertThrows(KbFailure.class,()->KnowledgeRuntimeFactory.current(resolver,different,original,clock)).status());
        var wrongIssuer=new Context(new Principal("https://other",actor.actor().subject()),actor.tenant());assertEquals(503,assertThrows(KbFailure.class,()->KnowledgeRuntimeFactory.current(resolver,wrongIssuer,original,clock)).status());
        assertEquals(503,assertThrows(KbFailure.class,()->KnowledgeRuntimeFactory.current(resolver("external"),actor,original,clock)).status());
    }
    @Test void absentEnabledProfileDoesNotCreatePoolOrOpenBusinessCapability(){
        try(var context=ApplicationContext.run()){
            assertFalse(context.containsBean(com.zaxxer.hikari.HikariDataSource.class));
            var factory=context.getBean(KnowledgeRequestFactory.class);
            assertEquals(503,assertThrows(KbFailure.class,()->factory.open(actor,claims(),"Bearer token")).status());
        }
    }
    @Test void runtimePoolHasFixedAcquisitionBoundAndNoMigratorCredentials(){
        var settings=Map.<String,Object>of("dkp.kb.jdbc.url","jdbc:postgresql://127.0.0.1:1/knowledge_base","dkp.kb.jdbc.username","dkp_kb_runtime","dkp.kb.jdbc.password","synthetic-test-only");
        try(var context=ApplicationContext.run(settings);var pool=new KnowledgeRuntimeFactory().runtimePool(context.getEnvironment())){
            assertEquals(250,pool.getConnectionTimeout());assertEquals(250,pool.getValidationTimeout());assertEquals(16,pool.getMaximumPoolSize());assertEquals(0,pool.getMinimumIdle());assertEquals(-1,pool.getInitializationFailTimeout());assertEquals("dkp_kb_runtime",pool.getUsername());assertEquals("1",pool.getDataSourceProperties().get("connectTimeout"));assertEquals("1",pool.getDataSourceProperties().get("socketTimeout"));
        }
        for(Map<String,Object> bad:List.of(Map.<String,Object>of(),Map.<String,Object>of("dkp.kb.jdbc.url","jdbc:h2:mem:wrong"),Map.<String,Object>of("dkp.kb.jdbc.url","jdbc:postgresql://localhost/knowledge_base"))){try(var context=ApplicationContext.run(bad)){assertThrows(KbFailure.class,()->new KnowledgeRuntimeFactory().runtimePool(context.getEnvironment()));}}
    }
    @Test void urlCannotOverrideCredentialTimeoutOrSchemaProperties(){
        for(String query:List.of("", "user=postgres","password=secret","options=-c%20statement_timeout=0","connectTimeout=0","socketTimeout=0","currentSchema=public","currentSchema=kb&currentSchema=kb","currentSchema=kb&user=postgres","%75ser=postgres","current%53chema=kb","currentSchema=%6bb","currentSchema=kb#fragment")){
            var config=Map.<String,Object>of("dkp.kb.jdbc.url","jdbc:postgresql://127.0.0.1:1/knowledge_base?"+query,"dkp.kb.jdbc.username","dkp_kb_runtime","dkp.kb.jdbc.password","synthetic-test-only");
            try(var context=ApplicationContext.run(config)){assertEquals(503,assertThrows(KbFailure.class,()->new KnowledgeRuntimeFactory().runtimePool(context.getEnvironment())).status(),query);}
        }
        for(String query:List.of("","?currentSchema=kb")){
            var config=Map.<String,Object>of("dkp.kb.jdbc.url","jdbc:postgresql://127.0.0.1:1/knowledge_base"+query,"dkp.kb.jdbc.username","dkp_kb_runtime","dkp.kb.jdbc.password","synthetic-test-only");
            try(var context=ApplicationContext.run(config);var pool=new KnowledgeRuntimeFactory().runtimePool(context.getEnvironment())){assertEquals(250,pool.getConnectionTimeout());assertEquals("1",pool.getDataSourceProperties().get("connectTimeout"));assertEquals("1",pool.getDataSourceProperties().get("socketTimeout"));}
        }
    }

}
