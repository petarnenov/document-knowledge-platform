package org.documentknowledge.knowledgebase.adapters.outbound.catalog;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.knowledgebase.application.KnowledgeAuthorityPort.Result;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class FileKnowledgeAuthorityTest {
    @TempDir Path directory;
    Context actor=new Context(new Principal("https://issuer.example/realm","staff"),"tenant-a");
    Path path; FileKnowledgeAuthority catalog;
    @BeforeEach void setup()throws Exception {
        path=directory.resolve("catalog.json");
        catalog=new FileKnowledgeAuthority(path,new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,new com.sun.security.auth.module.UnixSystem().getUid())));
        write(document());
    }
    String entry(){return "{\"issuer\":\"https://issuer.example/realm\",\"subject\":\"staff\",\"tenant\":\"tenant-a\",\"actions\":[\"knowledge.collection.create\"],\"delegationCeiling\":[\"knowledge.collection.update\"],\"firms\":[\"firm-a\"],\"resourceScopes\":[\"scope-a\"]}";}
    String document(){return "{\"schemaVersion\":1,\"entries\":["+entry()+"]}";}
    void write(String body)throws Exception {Files.writeString(path,body); Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
    @Test void exactAuthorityExplicitCeilingAndCurrentRelationsNeverReusePositive()throws Exception {
        assertEquals(Result.ALLOW,catalog.authorize(actor,Action.CREATE)); assertEquals(Result.DENY,catalog.authorize(actor,Action.UPDATE));
        assertEquals(Result.ALLOW,catalog.delegationCeiling(actor,Action.UPDATE)); assertEquals(Result.DENY,catalog.delegationCeiling(actor,Action.CREATE));
        for(Context other:List.of(new Context(actor.actor(),"tenant-b"),new Context(new Principal("https://other.example","staff"),"tenant-a"),new Context(new Principal(actor.actor().issuer(),"other"),"tenant-a"))) assertEquals(Result.DENY,catalog.authorize(other,Action.CREATE));
        assertEquals(Result.ALLOW,catalog.matches(actor,new Selector(SelectorKind.FIRM,actor.actor().issuer(),"firm-a")));
        assertEquals(Result.ALLOW,catalog.matches(actor,new Selector(SelectorKind.RESOURCE_SCOPE,actor.actor().issuer(),"scope-a")));
        assertEquals(Result.DENY,catalog.matches(actor,new Selector(SelectorKind.FIRM,"https://other.example","firm-a")));
        assertEquals(Result.DENY,catalog.matches(actor,new Selector(SelectorKind.RESOURCE_SCOPE,actor.actor().issuer(),"missing")));
        assertEquals(Result.ALLOW,catalog.matches(actor,new Selector(SelectorKind.PRINCIPAL,actor.actor().issuer(),"staff")));
        assertEquals(Result.DENY,catalog.matches(actor,new Selector(SelectorKind.PRINCIPAL,actor.actor().issuer(),"other")));
        assertEquals(Result.ALLOW,catalog.matches(actor,new Selector(SelectorKind.TENANT,null,"tenant-a")));
        assertEquals(Result.DENY,catalog.matches(actor,new Selector(SelectorKind.TENANT,null,"tenant-b")));
        write("{\"schemaVersion\":1,\"entries\":[]}"); assertEquals(Result.DENY,catalog.authorize(actor,Action.CREATE)); assertEquals(Result.DENY,catalog.matches(actor,new Selector(SelectorKind.FIRM,actor.actor().issuer(),"firm-a")));
        write("broken"); assertEquals(Result.UNAVAILABLE,catalog.authorize(actor,Action.CREATE)); assertEquals(Result.UNAVAILABLE,catalog.matches(actor,new Selector(SelectorKind.FIRM,actor.actor().issuer(),"firm-a")));
        Files.delete(path); assertEquals(Result.UNAVAILABLE,catalog.delegationCeiling(actor,Action.UPDATE));
    }
    @Test void closedCatalogRejectsMalformedUnrelatedRowsAndBounds()throws Exception {
        for(String bad:List.of("null","[]","{}",document()+"{}",document().replace("\"schemaVersion\":1","\"schemaVersion\":1.1"),document().replace("\"schemaVersion\":1","\"schemaVersion\":2"),document().replace("\"entries\":","\"entries\":[],\"entries\":"),document().replace("\"subject\":\"staff\",",""),document().replace("knowledge.collection.create","unknown"),document().replace("\"firm-a\"","\"firm-a\",\"firm-a\""),document().replace("https://issuer.example/realm","relative"),document().replace("https://issuer.example/realm","https://user@issuer.example/realm"),document().replace("https://issuer.example/realm","https://issuer.example/realm?query=1"),document().replace("https://issuer.example/realm","https://issuer.example/realm#fragment"),document().replace("\"staff\"","\" staff\""),document().replace("\"staff\"","\"*\""),document().replace("\"staff\"","\"staff\\n\""),document().replace("\"staff\"","\""+"s".repeat(257)+"\""),document().replace("\"actions\":[","\"actions\":{},\"extra\":["),"{\"schemaVersion\":1,\"entries\":["+entry()+","+entry()+"]}","{\"schemaVersion\":1,\"entries\":["+String.join(",",Collections.nCopies(101,entry()))+"]}",document().replace("[\"firm-a\"]","["+String.join(",",Collections.nCopies(33,"\"firm-a\""))+"]"),"x".repeat(65537))) {
            write(bad); assertEquals(Result.UNAVAILABLE,catalog.authorize(actor,Action.CREATE),bad.substring(0,Math.min(100,bad.length())));
        }
        write("{\"schemaVersion\":1,\"entries\":["+entry().replace(",\"delegationCeiling\":[\"knowledge.collection.update\"],\"firms\":[\"firm-a\"],\"resourceScopes\":[\"scope-a\"]","")+"]}");
        assertEquals(Result.ALLOW,catalog.authorize(actor,Action.CREATE)); assertEquals(Result.DENY,catalog.delegationCeiling(actor,Action.UPDATE));
    }
    @Test void operationalValidityNeverInventsAnAuthorityDecision()throws Exception {
        assertTrue(catalog.ready());
        write("{\"schemaVersion\":1,\"entries\":[]}");assertTrue(catalog.ready());assertEquals(Result.DENY,catalog.authorize(actor,Action.CREATE));
        write("broken");assertFalse(catalog.ready());Files.delete(path);assertFalse(catalog.ready());
    }
    @Test void productionSystemFactoryIsExercisedWithoutRuntimeOverride() {
        Result expected=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")?Result.ALLOW:Result.UNAVAILABLE;
        assertEquals(expected,new FileKnowledgeAuthority(path).authorize(actor,Action.CREATE));
    }
    @Test void unsafeFilePermissionsAndSymlinkCannotGrant()throws Exception {
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxrwx---")); assertEquals(Result.UNAVAILABLE,catalog.authorize(actor,Action.CREATE));
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        Path old=path.resolveSibling("old.json"); Files.move(path,old); Files.createSymbolicLink(path,old); assertEquals(Result.UNAVAILABLE,catalog.authorize(actor,Action.CREATE));
        assertEquals(Result.UNAVAILABLE,new FileKnowledgeAuthority(path).authorize(actor,Action.CREATE));
    }
}
