package org.documentknowledge.documentmanagement.application.upload;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.identity.Actor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GenericTempPortTest {
 @Test void identityAndArtifactFactsAreBoundedServerValuesAndUnavailableNeverAllocates(){
  var identity=GenericTempPort.Identity.of("tenant",new Actor("https://issuer.example","subject"),UUID.randomUUID(),UUID.randomUUID(),null,100_000_000);assertEquals(64,identity.tenantDigest().length());assertEquals(64,identity.actorDigest().length());
  for(long size:new long[]{0,100_000_001})assertThrows(DmFailure.class,()->new GenericTempPort.ArtifactFile(Path.of("/owned"),size,"a".repeat(64)));
  for(String hash:new String[]{null,"bad"})assertThrows(DmFailure.class,()->new GenericTempPort.ArtifactFile(Path.of("/owned"),1,hash));assertThrows(NullPointerException.class,()->new GenericTempPort.ArtifactFile(null,1,"a".repeat(64)));
  try(var unavailable=GenericTempPort.unavailable()){assertFalse(unavailable.ready());assertEquals(503,assertThrows(DmFailure.class,()->unavailable.reserve(GenericTempPort.Role.TRANSFER,identity,new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER))).status());}
 }
 @Test void legacyStorageCannotSilentlyDropEitherGenericRoleBudget(){
  var calls=new java.util.concurrent.atomic.AtomicInteger();
  UploadStoragePort legacy=new UploadStoragePort(){
   public org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity put(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey k,Path p,long n,String h){calls.incrementAndGet();throw new AssertionError();}
   public java.io.InputStream open(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity o){calls.incrementAndGet();throw new AssertionError();}
   public org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity copy(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity o,org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey k){throw new AssertionError();}
   public java.util.List<org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity> versions(org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey k){throw new AssertionError();}
  };
  var key=new org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectKey("dkp-quarantine","a".repeat(64)+"/"+UUID.randomUUID()+"/"+UUID.randomUUID());var object=new org.documentknowledge.documentmanagement.domain.upload.UploadModels.ObjectIdentity(key,"immutable-version");
  for(var role:GenericTempPort.Role.values()){
   var budget=new GenericTempBudget(Clock.systemUTC(),role);
   assertEquals(503,assertThrows(DmFailure.class,()->legacy.put(key,Path.of("/owned"),1,"a".repeat(64),budget)).status());
   assertEquals(503,assertThrows(DmFailure.class,()->legacy.open(object,budget)).status());
  }
  assertEquals(0,calls.get());
 }
 @Test void duplicateBudgetUseRemovedHooksAndClosedInputProgressFailWithoutAnotherWorker(){
  var budget=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.TRANSFER);var calls=new java.util.concurrent.atomic.AtomicInteger();var hook=budget.onCancel(calls::incrementAndGet);hook.run();hook.run();assertEquals(1,calls.get());
  var reading=budget.reading(java.io.InputStream.nullInputStream());assertThrows(DmFailure.class,()->budget.reading(java.io.InputStream.nullInputStream()));reading.close();reading.close();assertThrows(DmFailure.class,reading::progress);
  assertTrue(budget.execute(()->true));assertThrows(DmFailure.class,()->budget.execute(()->true));var checked=new GenericTempBudget(Clock.systemUTC(),GenericTempPort.Role.PREAPPEND);assertThrows(DmFailure.class,()->checked.execute(()->{throw new java.io.IOException("test-only checked failure");}));
 }
}
