package org.documentknowledge.knowledgebase.bootstrap;

import com.zaxxer.hikari.*;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import org.documentknowledge.identity.AuthorityDecision;
import org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeRequestFactory;
import org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeSetupBudget;
import org.documentknowledge.knowledgebase.adapters.outbound.catalog.FileKnowledgeAuthority;
import org.documentknowledge.knowledgebase.adapters.outbound.cursor.*;
import org.documentknowledge.knowledgebase.adapters.outbound.persistence.JdbcCollectionStore;
import org.documentknowledge.knowledgebase.adapters.outbound.remote.FixedDocumentShareClient;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.security.TrustedTenantResolver;

/** Runtime credentials never include the migration role; external capabilities fail closed. */
@Factory
public final class KnowledgeRuntimeFactory {
    @Singleton @Bean(preDestroy="close") @Requires(property="dkp.kb.enabled",value="true")
    HikariDataSource runtimePool(Environment environment){
        HikariConfig config=new HikariConfig();String url=required(environment,"dkp.kb.jdbc.url");
        if(!url.startsWith("jdbc:postgresql://"))throw KbFailure.unavailable();
        try{URI target=URI.create(url.substring(5));if(target.getHost()==null||target.getRawUserInfo()!=null||target.getRawFragment()!=null||!"/knowledge_base".equals(target.getRawPath())||target.getRawQuery()!=null&&!"currentSchema=kb".equals(target.getRawQuery()))throw KbFailure.unavailable();}catch(IllegalArgumentException malformed){throw KbFailure.unavailable();}
        String user=required(environment,"dkp.kb.jdbc.username");if(!"dkp_kb_runtime".equals(user))throw KbFailure.unavailable();
        config.setJdbcUrl(url);config.setUsername(user);config.setPassword(required(environment,"dkp.kb.jdbc.password"));
        config.setPoolName("kb-runtime");config.setConnectionTimeout(250);config.setValidationTimeout(250);config.setMaximumPoolSize(16);config.setMinimumIdle(0);config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("ApplicationName","dkp-kb-runtime");config.addDataSourceProperty("connectTimeout","1");config.addDataSourceProperty("socketTimeout","1");
        return new HikariDataSource(config);
    }
    @Singleton KnowledgeRequestFactory requests(Environment environment,BeanProvider<HikariDataSource> pools,TrustedTenantResolver tenants){
        RequestAdmission admission=new RequestAdmission();SecureRandom random=new SecureRandom();Clock clock=Clock.systemUTC();
        SnapshotTokenPort tokens=()->{byte[] value=new byte[32];random.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value);};
        return (verified,claims,authorization)->{
            if(!environment.getProperty("dkp.kb.enabled",Boolean.class).orElse(false))throw KbFailure.unavailable();
            KnowledgeSetupBudget setup=KnowledgeSetupBudget.current();
            Runnable current=()->timed(setup,()->{current(tenants,verified,claims,clock);return null;});current.run();
            try{
                var catalog=new FileKnowledgeAuthority(Path.of(required(environment,"dkp.kb.catalog.path")));
                KnowledgeAuthorityPort authority=new KnowledgeAuthorityPort(){
                    public Result authorize(Context target,Action action){current.run();return timed(setup,()->catalog.authorize(target,action));}
                    public Result delegationCeiling(Context target,Action action){current.run();return timed(setup,()->catalog.delegationCeiling(target,action));}
                };
                AudienceRelationPort relations=(target,selector)->{current.run();return timed(setup,()->catalog.matches(target,selector));};
                var cursorKey=Path.of(required(environment,"dkp.kb.cursor.key-path"));
                var memberCursor=new EncryptedMemberCursor(cursorKey,verified);var collectionCursor=new EncryptedCollectionCursor(cursorKey,verified);
                var remote=FixedDocumentShareClient.connect(URI.create(required(environment,"dkp.kb.dm.base-uri")),Path.of(required(environment,"dkp.kb.dm.ca-path")),verified,authorization);
                try{
                    DocumentShareBatchAuthorityPort sharing=(context,documents,deadline)->{current.run();return remote.assess(context,documents,deadline);};
                    DocumentShareEligibilityPort eligibility=(context,documents,deadline)->{current.run();return remote.discover(context,documents,deadline);};
                    var service=new KnowledgeService(new JdbcCollectionStore(pools.get()),authority,relations,sharing,eligibility,tokens,memberCursor,collectionCursor,admission,clock,UUID::randomUUID);
                    return new KnowledgeRequestFactory.RequestHandle(service,current,remote::close);
                }catch(RuntimeException failure){remote.close();throw failure;}
            }catch(KbFailure failure){throw failure;}catch(RuntimeException failure){throw KbFailure.unavailable();}
        };
    }
    private static <T>T timed(KnowledgeSetupBudget setup,java.util.function.Supplier<T> action){return setup==null?action.get():setup.call(action);}
    static void current(TrustedTenantResolver tenants,Context original,Map<String,Object> claims,Clock clock){
        if(!Objects.equals(original.actor().issuer(),claims.get("iss"))||!Objects.equals(original.actor().subject(),claims.get("sub")))throw KbFailure.unavailable();
        Object expiry=claims.get("exp");double epoch=expiry instanceof Date date?date.toInstant().getEpochSecond():expiry instanceof Number number?number.doubleValue():Double.NaN;
        if(!Double.isFinite(epoch)||epoch<=clock.instant().getEpochSecond())throw new KbFailure(401,"UNAUTHENTICATED");
        AuthorityDecision decision;
        try{decision=tenants.resolve(claims).select(original.tenant());}catch(RuntimeException unavailable){throw KbFailure.unavailable();}
        if(decision!=AuthorityDecision.ALLOW)throw new KbFailure(decision==AuthorityDecision.UNAVAILABLE?503:403,decision==AuthorityDecision.UNAVAILABLE?"UNAVAILABLE":"FORBIDDEN");
    }
    private static String required(Environment environment,String name){String value=environment.getProperty(name,String.class).orElse("");if(value.isBlank())throw KbFailure.unavailable();return value;}
}
