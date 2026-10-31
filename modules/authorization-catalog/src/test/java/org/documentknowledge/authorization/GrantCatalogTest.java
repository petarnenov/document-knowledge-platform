package org.documentknowledge.authorization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.documentknowledge.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GrantCatalogTest {
 @TempDir Path directory;
 private final Actor actor=new Actor("https://issuer.example/realm","staff");
 private GrantCatalog catalog(Path path){return new GrantCatalog(path,new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,new com.sun.security.auth.module.UnixSystem().getUid())));}
 private Path file() throws Exception {Path parent=directory.toRealPath();Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));return parent.resolve("grants.json");}
 private String entry(){return "{\"issuer\":\"https://issuer.example/realm\",\"subject\":\"staff\",\"tenant\":\"tenant-a\",\"actions\":[\"document.create\",\"document.inspect-draft\"],\"delegationCeiling\":[\"document.inspect-draft\"]}";}
 private String catalog(){return "{\"schemaVersion\":1,\"entries\":["+entry()+"]}";}
 private void write(Path path,String body)throws Exception{Files.writeString(path,body);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
 private AuthorityDecision decide(Path path){return catalog(path).authorize(actor,"tenant-a","document.create","");}
 @Test void exactIdentityActionAndCeilingAndEveryDecisionRereads()throws Exception{
  Path p=file();write(p,catalog());var c=catalog(p);assertEquals(AuthorityDecision.ALLOW,decide(p));
  assertEquals(AuthorityDecision.DENY,c.authorize(new Actor("https://other.example","staff"),"tenant-a","document.create",""));
  assertEquals(AuthorityDecision.DENY,c.authorize(new Actor(actor.issuer(),"other"),"tenant-a","document.create",""));
  assertEquals(AuthorityDecision.DENY,c.authorize(actor,"tenant-b","document.create",""));assertEquals(AuthorityDecision.DENY,c.authorize(actor,"tenant-a","admin",""));
  assertEquals(AuthorityDecision.ALLOW,c.delegationCeiling(actor,"tenant-a","document.inspect-draft"));assertEquals(AuthorityDecision.DENY,c.delegationCeiling(actor,"tenant-a","document.create"));
  write(p,"{\"schemaVersion\":1,\"entries\":[]}");assertEquals(AuthorityDecision.DENY,c.authorize(actor,"tenant-a","document.create",""));
  write(p,"partial");assertEquals(AuthorityDecision.UNAVAILABLE,c.authorize(actor,"tenant-a","document.create",""));
  Files.delete(p);assertEquals(AuthorityDecision.UNAVAILABLE,c.delegationCeiling(actor,"tenant-a","document.create"));
 }
 @Test void rejectsEveryMalformedShapeWithoutStalePositive()throws Exception{
  Path p=file();
  for(String body:List.of(catalog()+"{}", "", "null","[]","{}","{\"schemaVersion\":2,\"entries\":[]}","{\"schemaVersion\":true,\"entries\":[]}","{\"schemaVersion\":1,\"entries\":{},\"extra\":true}",catalog().replace("\"entries\":", "\"entries\":[],\"entries\":"),catalog().replace("\"subject\":\"staff\",",""),catalog().replace("\"staff\"","\"*\""),catalog().replace("document.create","admin"),catalog().replace("\"tenant-a\"","12"),catalog().replace("\"actions\":[","\"actions\":{} ,\"unknown\":["),"{\"schemaVersion\":1,\"entries\":["+entry()+","+entry()+"]}",catalog().replace("\"document.create\",","\"document.create\",\"document.create\","),catalog().replace("https://issuer.example/realm","relative"),catalog().replace("https://issuer.example/realm","https://user@issuer.example/realm"))) {
   write(p,body);assertEquals(AuthorityDecision.UNAVAILABLE,decide(p),body);
  }
 }
 @Test void enforcesStringCollectionAndFileSizeBounds()throws Exception{
  Path p=file();for(String body:List.of(catalog().replace("\"staff\"","\""+"s".repeat(257)+"\""),catalog().replace("\"staff\"","\" staff\""),catalog().replace("\"staff\"","\"staff\\n\""),"{\"schemaVersion\":1,\"entries\":["+String.join(",",java.util.Collections.nCopies(101,entry()))+"]}",catalog().replace("[\"document.create\",\"document.inspect-draft\"]","["+String.join(",",java.util.Collections.nCopies(33,"\"document.create\""))+"]"),"x".repeat(65537))) {write(p,body);assertEquals(AuthorityDecision.UNAVAILABLE,decide(p));}
  write(p,catalog().replace(",\"delegationCeiling\":[\"document.inspect-draft\"]",""));assertEquals(AuthorityDecision.ALLOW,decide(p));assertEquals(AuthorityDecision.DENY,catalog(p).delegationCeiling(actor,"tenant-a","document.inspect-draft"));
 }
 @Test void unsafeFilesystemAndMissingConfigurationAreUnavailable()throws Exception{
  assertEquals(AuthorityDecision.UNAVAILABLE,decide(null));assertEquals(AuthorityDecision.UNAVAILABLE,decide(Path.of("relative.json")));assertEquals(AuthorityDecision.UNAVAILABLE,decide(Path.of("/")));
  Path p=file();assertEquals(AuthorityDecision.UNAVAILABLE,decide(p));write(p,catalog());
  Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-rw----"));assertEquals(AuthorityDecision.UNAVAILABLE,decide(p));Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));
  Files.setPosixFilePermissions(p.getParent(),PosixFilePermissions.fromString("rwxrwx---"));assertEquals(AuthorityDecision.UNAVAILABLE,decide(p));Files.setPosixFilePermissions(p.getParent(),PosixFilePermissions.fromString("rwx------"));
  Path link=p.resolveSibling("link.json");Files.createSymbolicLink(link,p);assertEquals(AuthorityDecision.UNAVAILABLE,decide(link));
  Path parentLink=p.resolveSibling("linked-directory");Files.createSymbolicLink(parentLink,p.getParent());assertEquals(AuthorityDecision.UNAVAILABLE,decide(parentLink.resolve(p.getFileName())));
  assertEquals(AuthorityDecision.UNAVAILABLE,catalog(p).authorize(null,"tenant-a","document.create",""));
 }
 @Test void writableGrandparentAndParentReplacementNeverEstablishTrust()throws Exception{
  Path grand=directory.toRealPath().resolve("grand");Files.createDirectory(grand,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  Path parent=grand.resolve("authority");Files.createDirectory(parent,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));Path p=parent.resolve("grants.json");write(p,"{\"schemaVersion\":1,\"entries\":[]}");var c=catalog(p);
  assertEquals(AuthorityDecision.DENY,c.authorize(actor,"tenant-a","document.create",""));
  Files.setPosixFilePermissions(grand,PosixFilePermissions.fromString("rwxrwxrwx"));assertEquals(AuthorityDecision.UNAVAILABLE,c.authorize(actor,"tenant-a","document.create",""));
  Files.move(parent,grand.resolve("original"));Files.createDirectory(parent,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));write(p,catalog());
  assertEquals(AuthorityDecision.UNAVAILABLE,c.authorize(actor,"tenant-a","document.create",""));
  Files.setPosixFilePermissions(grand,PosixFilePermissions.fromString("rwx------"));assertEquals(AuthorityDecision.ALLOW,c.authorize(actor,"tenant-a","document.create",""));
 }
 @Test void unsupportedPlatformFailsClosedWithoutShellFallback()throws Exception{
  Path p=file();write(p,catalog());var unsupported=new GrantCatalog(p,new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Mac OS X",false,new com.sun.security.auth.module.UnixSystem().getUid())));
  assertEquals(AuthorityDecision.UNAVAILABLE,unsupported.authorize(actor,"tenant-a","document.create",""));
 }
 @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
 @Test void productionLinuxPlatformUsesTheDefaultFactory()throws Exception{Path p=file();write(p,catalog());assertEquals(AuthorityDecision.ALLOW,new GrantCatalog(p).authorize(actor,"tenant-a","document.create",""));}

 @Test void additiveContentActionRequiresExplicitGrantAndCeilingWithAllOldActionsPreserved()throws Exception{
  Path p=file();assertEquals(10,GrantCatalog.ACTIONS.size());
  for(String action:GrantCatalog.ACTIONS){if(action.equals("document.create"))continue;write(p,catalog().replace("document.inspect-draft",action));assertEquals(AuthorityDecision.ALLOW,catalog(p).authorize(actor,"tenant-a",action,""));assertEquals(AuthorityDecision.ALLOW,catalog(p).delegationCeiling(actor,"tenant-a",action));}
  write(p,catalog());assertEquals(AuthorityDecision.DENY,catalog(p).authorize(actor,"tenant-a","document.content.review",""));assertEquals(AuthorityDecision.DENY,catalog(p).delegationCeiling(actor,"tenant-a","document.content.review"));
  write(p,catalog().replace("document.inspect-draft","document.content.review"));assertEquals(AuthorityDecision.ALLOW,catalog(p).authorize(actor,"tenant-a","document.content.review",""));
  for(String invalid:List.of("document.content.*","admin","document.content.review.extra")){write(p,catalog().replace("document.inspect-draft",invalid));assertEquals(AuthorityDecision.UNAVAILABLE,decide(p));}
 }

}
