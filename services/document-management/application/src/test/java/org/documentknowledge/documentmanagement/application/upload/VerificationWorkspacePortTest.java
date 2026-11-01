package org.documentknowledge.documentmanagement.application.upload;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.identity.Actor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VerificationWorkspacePortTest {
 @Test void identityHashesExactUtf8AndLengthDelimitsIssuerAndSubject(){
  var document=UUID.randomUUID();var upload=UUID.randomUUID();var first=VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example/a","bc"),document,upload,1);
  assertEquals(first,VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example/a","bc"),document,upload,1));
  assertNotEquals(first.actorDigest(),VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example/ab","c"),document,upload,1).actorDigest());
  assertNotEquals(first.tenantDigest(),VerificationWorkspacePort.Identity.of("tenant ",new Actor("https://issuer.example/a","bc"),document,upload,1).tenantDigest());
  for(long size:new long[]{0,-1,100_000_001})assertThrows(DmFailure.class,()->new VerificationWorkspacePort.Identity(first.tenantDigest(),first.actorDigest(),document,upload,size));
  assertThrows(DmFailure.class,()->new VerificationWorkspacePort.Identity(null,first.actorDigest(),document,upload,1));assertThrows(DmFailure.class,()->new VerificationWorkspacePort.Identity("bad",first.actorDigest(),document,upload,1));
  assertThrows(DmFailure.class,()->new VerificationWorkspacePort.Identity(first.tenantDigest(),null,document,upload,1));assertThrows(DmFailure.class,()->new VerificationWorkspacePort.Identity(first.tenantDigest(),"BAD",document,upload,1));
  assertThrows(DmFailure.class,()->new VerificationWorkspacePort.Identity(first.tenantDigest(),first.actorDigest(),null,upload,1));assertThrows(DmFailure.class,()->new VerificationWorkspacePort.Identity(first.tenantDigest(),first.actorDigest(),document,null,1));
 }
 @Test void compatibilityBindingCannotVerifyAndArtifactFactsAreBounded(){
  var unavailable=VerificationWorkspacePort.unavailable();assertFalse(unavailable.ready());
  try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE)){
   var identity=VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example","actor"),UUID.randomUUID(),UUID.randomUUID(),1);
   assertThrows(DmFailure.class,()->unavailable.reserve(identity,phase));assertThrows(DmFailure.class,()->unavailable.check(null,phase));
  }unavailable.close();
  var file=new VerificationWorkspacePort.ArtifactFile(Path.of("source"),1,"a".repeat(64));assertEquals(1,file.size());
  for(long size:new long[]{0,-1,100_000_001})assertThrows(DmFailure.class,()->new VerificationWorkspacePort.ArtifactFile(Path.of("source"),size,"a".repeat(64)));
  for(String hash:new String[]{null,"bad","A".repeat(64)})assertThrows(DmFailure.class,()->new VerificationWorkspacePort.ArtifactFile(Path.of("source"),1,hash));
  assertThrows(NullPointerException.class,()->new VerificationWorkspacePort.ArtifactFile(null,1,"a".repeat(64)));
 }
}
