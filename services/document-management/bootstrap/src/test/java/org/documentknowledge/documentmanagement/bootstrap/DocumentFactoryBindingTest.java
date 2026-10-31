package org.documentknowledge.documentmanagement.bootstrap;

import com.sun.security.auth.module.UnixSystem;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.documentknowledge.authorization.GrantCatalog;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.application.AclChange;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.DocumentCaller;
import org.documentknowledge.documentmanagement.domain.Action;
import org.documentknowledge.documentmanagement.domain.Metadata;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Technical binding tests: caller membership ALLOW does not supply operation authority. */
class DocumentFactoryBindingTest {
    private static final Actor ACTOR=new Actor("https://identity.example/realms/dkp","user");
    private static final String TENANT="tenant-a";

    @Test void externalBindingCannotConsumeLocalAllowCatalogOrReachDatabase() throws Exception {
        assertUnavailableBinding("external");
    }

    @Test void unconfiguredOrUnknownBindingCannotConsumeLocalAllowCatalogOrReachDatabase() throws Exception {
        for(String profile:List.of("unconfigured","unexpected",""))assertUnavailableBinding(profile);
    }

    private void assertUnavailableBinding(String profile) throws Exception {
        Path base=Path.of("build/factory-binding-fixtures").toAbsolutePath();
        Files.createDirectories(base);
        Files.setPosixFilePermissions(base,PosixFilePermissions.fromString("rwx------"));
        Path directory=Files.createTempDirectory(base.toRealPath(),"authority-");
        try {
            Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
            Path catalog=directory.resolve("grants.json");
            var actions=List.of("document.create","document.inspect-draft","document.update-metadata","document.acl.restrict","document.acl.delegate","document.legal-hold.place","document.legal-hold.inspect","document.version.append");
            var entry=Map.of("issuer",ACTOR.issuer(),"subject",ACTOR.subject(),"tenant",TENANT,"actions",actions,"delegationCeiling",actions);
            Files.writeString(catalog,JsonMapper.builder().build().writeValueAsString(Map.of("schemaVersion",1,"entries",List.of(entry))));
            Files.setPosixFilePermissions(catalog,PosixFilePermissions.fromString("rw-------"));

            // Linux acceptance uses the actual authority reader. The existing Java-only
            // filesystem seam isolates this binding unit on platforms whose runtime reader
            // deliberately fails closed; it is never passed into the production factory.
            TrustedAuthorityFiles controlFiles=System.getProperty("os.name","").equalsIgnoreCase("Linux")
                ?TrustedAuthorityFiles.system()
                :new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,new UnixSystem().getUid()));
            var control=new GrantCatalog(catalog,controlFiles);
            for(String action:actions)assertEquals(AuthorityDecision.ALLOW,control.authorize(ACTOR,TENANT,action,""));

            AtomicInteger providerInspections=new AtomicInteger(),databaseMaterializations=new AtomicInteger();
            BeanProvider<DataSource> database=new BeanProvider<>() {
                @Override public boolean isPresent(){providerInspections.incrementAndGet();return true;}
                @Override public DataSource get(){databaseMaterializations.incrementAndGet();throw new AssertionError("Unavailable authority must not materialize a database");}
            };
            try(var context=ApplicationContext.builder().properties(Map.of("dkp.security.profile",profile,"dkp.dm.catalog.path",catalog.toString())).start()) {
                var service=new DocumentFactory().documents(context.getEnvironment(),database);
                var caller=new DocumentCaller(ACTOR,TENANT,()->AuthorityDecision.ALLOW);
                assertEquals(AuthorityDecision.ALLOW,caller.membership());
                UUID id=UUID.randomUUID();
                var delta=new AclChange(AclChange.Mode.DELEGATE,List.of(new AclChange.Entry(AclChange.Operation.ADD_ALLOW,caller.principal(),Action.INSPECT_DRAFT)));
                List<Runnable> operations=List.of(
                    ()->service.create(caller,"create",new Metadata("title","description")),
                    ()->service.list(caller,20,null),
                    ()->service.inspect(caller,id),
                    ()->service.inspectAcl(caller,id),
                    ()->service.inspectHolds(caller,id),
                    ()->service.updateMetadata(caller,id,0,"metadata",new Metadata("updated","")),
                    ()->service.changeAcl(caller,id,0,"delegate",delta),
                    ()->service.placeHold(caller,id,0,"hold","preserve"),
                    ()->service.appendVersion(caller,id,0,"version","opaque-receipt")
                );
                for(Runnable operation:operations){var failure=assertThrows(DmFailure.class,operation::run,"profile="+profile);assertEquals(503,failure.status());assertEquals("UNAVAILABLE",failure.code());}
                assertEquals(0,providerInspections.get(),"No transaction may be opened after unavailable operation authority");
                assertEquals(0,databaseMaterializations.get());
            }
        } finally {
            try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
}
