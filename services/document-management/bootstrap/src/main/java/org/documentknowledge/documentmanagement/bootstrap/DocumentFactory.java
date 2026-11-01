package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.*;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.documentknowledge.authorization.GrantCatalog;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.adapters.outbound.CatalogDelegationAdapter;
import org.documentknowledge.documentmanagement.adapters.outbound.persistence.JdbcDocumentStore;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.identity.*;

@Factory
public final class DocumentFactory {
    public DocumentService documents(Environment environment,BeanProvider<DataSource> runtime) {
        return documents(bindings(environment,runtime));
    }
    record Bindings(DocumentUnitOfWork transactions,ResourceAuthorizationPort grants,DelegationPolicyPort delegation,DocumentCursorPort cursors,UploadRuntime uploads) implements AutoCloseable {public void close(){uploads.close();}}
    @Singleton
    @Bean(preDestroy="close")
    Bindings bindings(Environment environment,@Named("default") BeanProvider<DataSource> runtime) {
        boolean standalone=environment.getProperty("dkp.security.profile",String.class).orElse("").equals("standalone");
        var catalog=new GrantCatalog(path(environment,"dkp.dm.catalog.path","/run/dkp/authority/grants.json"));
        ResourceAuthorizationPort grants=standalone?catalog:(actor,tenant,action,resource)->AuthorityDecision.UNAVAILABLE;
        DelegationPolicyPort delegation=standalone?new CatalogDelegationAdapter(catalog):new DelegationPolicyPort(){
            public AuthorityDecision targetGrant(Actor actor,String tenant,org.documentknowledge.documentmanagement.domain.Action action){return AuthorityDecision.UNAVAILABLE;}
            public AuthorityDecision ceiling(Actor actor,String tenant,org.documentknowledge.documentmanagement.domain.Action action){return AuthorityDecision.UNAVAILABLE;}
        };
        DocumentUnitOfWork transactions=new DocumentUnitOfWork(){
            @Override public <T> T execute(Function<DocumentRepository,T> work){
                try {if(!runtime.isPresent())throw new DmFailure(503,"UNAVAILABLE");return new JdbcDocumentStore(runtime.get()).execute(work);}
                catch(DmFailure failure){throw failure;}catch(RuntimeException failure){throw new DmFailure(503,"UNAVAILABLE");}
            }
        };
        Path secret=path(environment,"dkp.dm.cursor.key-path","/run/dkp/authority/cursor.key");
        DocumentCursorPort cursors=new DocumentCursorPort(){
            public PagePosition decode(String cursor,Actor actor,String tenant){return cursor(secret).decode(cursor,actor,tenant);}
            public String encode(PagePosition position,Actor actor,String tenant){return cursor(secret).encode(position,actor,tenant);}
        };
        return new Bindings(transactions,grants,delegation,cursors,new UploadRuntime(environment,Clock.systemUTC()));
    }
    @Singleton
    public DocumentService documents(Bindings bindings){return new DocumentService(bindings.transactions(),bindings.grants(),bindings.delegation(),new ReceiptArtifactAuthority(bindings.transactions(),bindings.uploads(),Clock.systemUTC(),bindings.uploads().spool()),bindings.cursors(),Clock.systemUTC());}
    @Singleton
    public UploadService uploads(Bindings bindings){return new UploadService(bindings.transactions(),bindings.grants(),bindings.uploads(),bindings.uploads(),bindings.uploads(),Clock.systemUTC(),bindings.uploads().spool(),bindings.uploads().verification());}
    @Singleton
    @Bean(preDestroy="close")
    public ContentService content(Bindings bindings){return new ContentService(bindings.transactions(),bindings.grants(),bindings.uploads(),Clock.systemUTC(),bindings.uploads().spool());}
    @Singleton
    public UploadCleanupService cleanup(Bindings bindings){return new UploadCleanupService(bindings.transactions(),bindings.uploads().cleanup(),Clock.systemUTC());}
    private static Path path(Environment environment,String name,String fallback){try{return Path.of(environment.getProperty(name,String.class).orElse(fallback));}catch(RuntimeException invalid){return null;}}
    private static HmacDocumentCursor cursor(Path path){
        try {
            byte[] encoded=TrustedAuthorityFiles.read(path,128,true);
            if(encoded.length>128)throw new IllegalArgumentException();byte[] secret=Base64.getDecoder().decode(new String(encoded,java.nio.charset.StandardCharsets.US_ASCII).strip());if(secret.length!=32)throw new IllegalArgumentException();return new HmacDocumentCursor(secret);
        }catch(Exception failure){throw new DmFailure(503,"UNAVAILABLE");}
    }
}
