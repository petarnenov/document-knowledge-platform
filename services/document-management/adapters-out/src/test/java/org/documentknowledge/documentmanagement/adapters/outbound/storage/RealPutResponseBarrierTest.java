package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.documentknowledge.identity.Actor;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Actual production copy/workspace/files, synthetic SDK transport only. Not native provider acceptance. */
class RealPutResponseBarrierTest {
 Path root;FileVerificationWorkspace manager;
 final byte[] bytes="public synthetic COPY bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
 final AtomicInteger puts=new AtomicInteger(),closes=new AtomicInteger();
 final AtomicReference<Thread> transportThread=new AtomicReference<>();
 final ObjectKey source=new ObjectKey("dkp-quarantine","a".repeat(64)+"/"+UUID.randomUUID()+"/"+UUID.randomUUID());
 @BeforeEach void setup()throws Exception{
  Path parent=Path.of("build/barrier-private-tests").toAbsolutePath();Files.createDirectories(parent);Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));root=Files.createTempDirectory(parent,"owned-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  Files.writeString(root.resolve(".spool-authority"),"dkp-private-spool-v1\n");Files.setPosixFilePermissions(root.resolve(".spool-authority"),PosixFilePermissions.fromString("rw-------"));manager=RealVerificationWorkspaceFixture.open(root);
 }
 @AfterEach void cleanup()throws Exception{manager.close();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
 S3Client client(boolean reject){return (S3Client)Proxy.newProxyInstance(S3Client.class.getClassLoader(),new Class<?>[]{S3Client.class},(proxy,method,args)->{
  if(method.getName().equals("putObject")){transportThread.set(Thread.currentThread());puts.incrementAndGet();if(reject)throw new IllegalStateException("synthetic transport failure");try(var in=((RequestBody)args[1]).contentStreamProvider().newStream()){assertArrayEquals(bytes,in.readAllBytes());}return PutObjectResponse.builder().versionId("real-response-barrier-version").build();}
  if(method.getName().equals("getObject")){var request=(GetObjectRequest)args[0];return new ResponseInputStream<>(GetObjectResponse.builder().versionId(request.versionId()).contentLength((long)bytes.length).build(),AbortableInputStream.create(new ByteArrayInputStream(bytes)));}
  if(method.getName().equals("close")){closes.incrementAndGet();return null;}throw new AssertionError("Unexpected SDK operation");
 });}
 VerificationWorkspacePort.Identity identity(){return VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example","synthetic"),UUID.randomUUID(),UUID.randomUUID(),bytes.length);}
 ObjectKey target(){return new ObjectKey("dkp-originals",source.key().substring(0,102)+UUID.randomUUID());}
 @Test void alreadyDispatchedPutOwnsReaderAndWorkspaceAcrossCallerFailureUntilActualSettlement()throws Exception{
  var budget=new CompleteBudget(Clock.systemUTC());var phase=budget.phase(CompleteBudget.Kind.STORAGE);var workspace=manager.reserve(identity(),phase);workspace.source().write(new ByteArrayInputStream(bytes),phase);
  try(var barrier=new RealPutResponseBarrier(client(false),root,manager);var executor=Executors.newSingleThreadExecutor()){try{
   var outcome=executor.submit(()->barrier.storage().copy(new ObjectIdentity(source,"source-version"),target(),phase,workspace));
   assertTrue(barrier.entered().await(5,TimeUnit.SECONDS));assertEquals(1,puts.get());assertEquals(1,barrier.dispatched());assertFalse(outcome.isDone());
   assertEquals(503,assertThrows(DmFailure.class,()->workspace.finish(phase)).status());
   transportThread.get().interrupt();budget.cancel();workspace.close();phase.close();assertFalse(manager.ready());assertTrue(Files.exists(root.resolve(".verification-spool/state.json")));
   // Cancellation/closed caller capability neither releases actual reader ownership nor dispatches again.
   assertEquals(429,assertThrows(DmFailure.class,()->manager.reserve(identity(),new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE))).status());
   barrier.release().countDown();var late=assertThrows(ExecutionException.class,()->outcome.get(5,TimeUnit.SECONDS));assertEquals(503,assertInstanceOf(DmFailure.class,late.getCause()).status());assertEquals(1,puts.get());assertEquals(1,barrier.dispatched());
   assertTrue(manager.ready());assertFalse(Files.exists(root.resolve(".verification-spool/state.json")));assertEquals(0,closes.get());
  }finally{barrier.release().countDown();}}assertEquals(1,closes.get());
 }
 @Test void successfulReleaseReturnsExactIdentityAndAllowsNormalWorkspaceFinish()throws Exception{
  try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE);var workspace=manager.reserve(identity(),phase);var barrier=new RealPutResponseBarrier(client(false),root,manager);var executor=Executors.newSingleThreadExecutor()){try{
   workspace.source().write(new ByteArrayInputStream(bytes),phase);var target=target();var outcome=executor.submit(()->barrier.storage().copy(new ObjectIdentity(source,"source-version"),target,phase,workspace));assertTrue(barrier.entered().await(5,TimeUnit.SECONDS));barrier.release().countDown();assertEquals(new ObjectIdentity(target,"real-response-barrier-version"),outcome.get(5,TimeUnit.SECONDS));workspace.finish(phase);assertEquals(1,puts.get());
  }finally{barrier.release().countDown();}}
 }
 @Test void transportFailureDoesNotClaimDispatchedSuccessOrWaitForResponseBarrier()throws Exception{
  try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE);var workspace=manager.reserve(identity(),phase);var barrier=new RealPutResponseBarrier(client(true),root,manager)){
   workspace.source().write(new ByteArrayInputStream(bytes),phase);assertEquals(503,assertThrows(DmFailure.class,()->barrier.storage().copy(new ObjectIdentity(source,"source-version"),target(),phase,workspace)).status());assertEquals(1,puts.get());assertEquals(0,barrier.dispatched());assertEquals(1,barrier.entered().getCount());workspace.finish(phase);
  }
 }
 @Test void barrierRejectsDuplicateOriginalPutAndDoesNotDelayQuarantineWrites()throws Exception{
  try(var barrier=new RealPutResponseBarrier(client(false),root,manager)){
   Path sourceFile=root.resolve("public-fixture");Files.write(sourceFile,bytes);Files.setPosixFilePermissions(sourceFile,PosixFilePermissions.fromString("rw-------"));
   String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
   assertNotNull(barrier.storage().put(source,sourceFile,bytes.length,hash));assertEquals(0,barrier.dispatched());
   barrier.release().countDown();assertNotNull(barrier.storage().put(target(),sourceFile,bytes.length,hash));
   assertThrows(AssertionError.class,()->barrier.storage().put(target(),sourceFile,bytes.length,hash));assertEquals(2,barrier.dispatched());
  }
 }

}
