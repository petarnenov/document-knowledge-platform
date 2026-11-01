package org.documentknowledge.documentmanagement.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.junit.jupiter.api.*;
import org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.RealVerificationWorkspaceFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Actual private filesystem/processes; SQL, S3, scanner and inspector are explicit UNIT fixtures. */
class VerificationTempOwnershipRegressionTest {
 Path temporary;
 @BeforeEach void start()throws Exception{Path parent=Path.of("build/verification-service-tests").toAbsolutePath();Files.createDirectories(parent);Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));temporary=Files.createTempDirectory(parent,"owned-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));Files.writeString(temporary.resolve(".spool-authority"),"dkp-private-spool-v1\n");Files.setPosixFilePermissions(temporary.resolve(".spool-authority"),PosixFilePermissions.fromString("rw-------"));}
 @AfterEach void stop()throws Exception{try(var paths=Files.walk(temporary)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}}

 @Test void failedUnlinkRemainsAdmissionBlockingAfterMockRemoteExpiryAndQuotaRelease() throws Exception {
  var f=fixture(temporary);
  retainSourceThenReclaimMockRemoteQuota(f);
  assertAdmissionBlocked(f);
 }

 @Test void retainedPhysicalSourceRemainsAdmissionBlockingAcrossDistinctJvmProcesses() throws Exception {
  var first=child("retain",temporary);System.out.print(first);
  assertTrue(first.contains("retainedBytes=36;remoteTransient=0;remoteObjects=0"),first);
  long firstPid=pid(first);
  var second=child("admit",temporary);System.out.print(second);
  assertNotEquals(firstPid,pid(second),"Two actual JVM processes must use the same physical directory");
  assertTrue(second.contains("retainedBytes=36;newCompletionAccepted=false")||second.contains("retainedBytes=36;newCompletionAccepted=true;remainingPriorBytes=0"),
    "Restart must not forget retained physical verification bytes: "+second);
 }

 static UploadLifecycleTest fixture(Path directory) {
  var f=new UploadLifecycleTest();f.temporary=directory;f.testWorkspace=RealVerificationWorkspaceFixture.create(directory,path->{if(f.completeCleanup!=null)f.completeCleanup.accept(path);});f.create();return f;
 }

 static void retainSourceThenReclaimMockRemoteQuota(UploadLifecycleTest f) throws Exception {
  var retained=new AtomicReference<Path>();
  f.completeCleanup=path->{retained.set(path);try {
   Files.setPosixFilePermissions(f.temporary,PosixFilePermissions.fromString("r-x------"));
   Files.delete(path);
  } catch(IOException denied) {throw new UncheckedIOException(denied);}};
  var app=f.uploads();UUID slot=f.admitted(app,UploadFormat.PDF);
  app.transfer(f.caller,slot,"original-body",new ByteArrayInputStream(f.bytes()));
  try {f.failure(503,"UNAVAILABLE",()->app.complete(f.caller,slot,"failed-local-unlink"));}
  finally {Files.setPosixFilePermissions(f.temporary,PosixFilePermissions.fromString("rwx------"));}
  assertNotNull(retained.get());assertArrayEquals(f.bytes(),Files.readAllBytes(retained.get()));
  assertTrue(f.store.uploads.receipts.isEmpty());assertEquals(2L*f.bytes().length,f.store.uploads.global.transientBytes());
  // Injected unit clock and fake remote deletion are NOT a genuine expiry/provider acceptance run.
  f.clock.advance(3601);f.cleanup().expire(f.caller.tenant(),slot);
  for(int i=0;i<16&&!f.store.uploads.pendingCleanup(f.caller.tenant(),slot).isEmpty();i++) {
   for(var work:f.store.uploads.pendingCleanup(f.caller.tenant(),slot))
    f.cleanup().cleanup(f.caller.tenant(),work.workId(),UUID.randomUUID());
  }
  assertTrue(f.store.uploads.pendingCleanup(f.caller.tenant(),slot).isEmpty());
  assertEquals(0,f.store.uploads.global.admittedCount());
  assertEquals(0,f.store.uploads.global.transientBytes());
  assertEquals(0,f.store.uploads.global.promisedBytes());
  assertEquals(0,f.store.uploads.lockAccounts(f.caller.tenant()).tenant().transientBytes());
  assertEquals(0,f.storage.objects.size());
  assertEquals(f.bytes().length,physicalBytes(f.temporary));
  System.out.println("VLOCAL02 retainedBytes="+physicalBytes(f.temporary)+";remoteTransient=0;remoteObjects=0");
  f.testWorkspace.close();
 }

 static void assertAdmissionBlocked(UploadLifecycleTest f) throws Exception {
  f.completeCleanup=null;f.testWorkspace=RealVerificationWorkspaceFixture.create(f.temporary,null);var app=f.uploads();UUID slot=f.admitted(app,UploadFormat.PDF);
  app.transfer(f.caller,slot,"new-body",new ByteArrayInputStream(f.bytes()));
  // Existing public path must fail closed rather than issue a new receipt while old bytes are unowned.
  boolean accepted;
  try {app.complete(f.caller,slot,"new-completion");accepted=true;}
  catch(DmFailure denied){assertEquals(503,denied.status());assertEquals("UNAVAILABLE",denied.code());accepted=false;}
  if(accepted)assertEquals(0,physicalBytes(f.temporary),"New receipt requires confirmed removal of prior retained source bytes");
  else assertTrue(f.store.uploads.receipts.isEmpty());
  f.testWorkspace.close();
 }

 static long physicalBytes(Path directory) throws Exception {
  try(var files=Files.list(directory)) {return files.filter(p->p.getFileName().toString().startsWith("dkp-verification-")||p.getFileName().toString().startsWith("dkp-bytes-")).filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception failure){throw new AssertionError(failure);}}).sum();}
 }
 static long pid(String output) {return Long.parseLong(output.substring(output.indexOf("pid=")+4).split(";",2)[0]);}

 static String child(String mode,Path directory) throws Exception {
  var entries=new LinkedHashSet<String>();
  for(var entry:System.getProperty("java.class.path").split(java.io.File.pathSeparator))entries.add(entry);
  for(ClassLoader loader=VerificationTempOwnershipRegressionTest.class.getClassLoader();loader!=null;loader=loader.getParent())
   if(loader instanceof URLClassLoader urls)for(var url:urls.getURLs())if("file".equals(url.getProtocol()))entries.add(Path.of(url.toURI()).toString());
  var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",String.join(java.io.File.pathSeparator,entries),Probe.class.getName(),mode,directory.toString()).redirectErrorStream(true).start();
  try {
   assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Owned filesystem probe must terminate");
   byte[] output=process.getInputStream().readNBytes(4097);assertTrue(output.length<=4096,"Bounded safe probe output");
   String text=new String(output,StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),text);return text;
  } finally {if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
 }

 public static final class Probe {
  public static void main(String[] args) throws Exception {
   if(args.length!=2||!java.util.Set.of("retain","admit").contains(args[0]))throw new IllegalArgumentException("Invalid probe mode");
   Path directory=Path.of(args[1]);if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory))throw new IllegalArgumentException("Invalid private directory");
   var f=fixture(directory);System.out.print("VLOCAL02 pid="+ProcessHandle.current().pid()+";");
   if("retain".equals(args[0]))retainSourceThenReclaimMockRemoteQuota(f);
   else {
    long before=physicalBytes(directory);assertEquals(f.bytes().length,before);
    var app=f.uploads();UUID slot=f.admitted(app,UploadFormat.PDF);app.transfer(f.caller,slot,"restart-body",new ByteArrayInputStream(f.bytes()));
    boolean accepted;try{app.complete(f.caller,slot,"restart-completion");accepted=true;}catch(DmFailure denied){assertEquals(503,denied.status());assertEquals("UNAVAILABLE",denied.code());accepted=false;}
    long remaining=physicalBytes(directory);
    System.out.println("retainedBytes="+before+";newCompletionAccepted="+accepted+";remainingPriorBytes="+remaining);f.testWorkspace.close();
   }
  }
 }
}
