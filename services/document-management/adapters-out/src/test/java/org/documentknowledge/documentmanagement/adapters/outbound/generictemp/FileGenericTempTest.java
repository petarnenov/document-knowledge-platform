package org.documentknowledge.documentmanagement.adapters.outbound.generictemp;

import com.sun.security.auth.module.UnixSystem;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.authorization.TrustedAuthorityFiles;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.application.upload.GenericTempPort.*;
import org.documentknowledge.identity.Actor;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual files/locks/bytes; injected Linux Java descriptor is explicitly a host-unit seam. */
class FileGenericTempTest {
 Path root;FileGenericTemp manager;
 final TrustedAuthorityFiles files=new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,new UnixSystem().getUid()));
 @BeforeEach void setup()throws Exception{
  Path parent=Path.of("build/generic-private-tests").toAbsolutePath();Files.createDirectories(parent);Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));root=Files.createTempDirectory(parent,"owned-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  Files.writeString(root.resolve(".spool-authority"),"dkp-private-spool-v1\n",StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(root.resolve(".spool-authority"),PosixFilePermissions.fromString("rw-------"));manager=open();
 }
 FileGenericTemp open(){return new FileGenericTemp(root,files);}
 @AfterEach void close()throws Exception{manager.close();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
 GenericTempBudget budget(Role role){return new GenericTempBudget(Clock.systemUTC(),role);}
 Identity identity(Role role,long size){return Identity.of("tenant",new Actor("https://issuer.example","subject"),UUID.randomUUID(),UUID.randomUUID(),role==Role.TRANSFER?null:UUID.randomUUID(),size);}
 void unavailable(Runnable action){var failure=assertThrows(DmFailure.class,action::run);assertEquals(503,failure.status());assertEquals("UNAVAILABLE",failure.code());}
 long payloadBytes()throws IOException{try(var paths=Files.list(root)){return paths.filter(p->p.getFileName().toString().startsWith("dkp-transfer-")||p.getFileName().toString().startsWith("dkp-preappend-")).mapToLong(p->{try{return Files.size(p);}catch(IOException failure){throw new UncheckedIOException(failure);}}).sum();}}
 @Test void rolesHaveIndependentPhysicalCapacityBeforeAnyUntrustedRead()throws Exception{
  var transfer=budget(Role.TRANSFER);List<Workspace> active=new ArrayList<>();
  try{
   for(int i=0;i<10;i++){var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,100_000_000),transfer);active.add(workspace);workspace.write(new Repeated(100_000_000),transfer);}
   assertEquals(1_000_000_000,payloadBytes());assertEquals(429,assertThrows(DmFailure.class,()->manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),transfer)).status());
   var preappend=budget(Role.PREAPPEND);Identity receipt=identity(Role.PREAPPEND,100_000_000);for(int i=0;i<2;i++){var workspace=manager.reserve(Role.PREAPPEND,receipt,preappend);active.add(workspace);workspace.write(new Repeated(100_000_000),preappend);}
   assertEquals(1_200_000_000,payloadBytes());assertEquals(429,assertThrows(DmFailure.class,()->manager.reserve(Role.PREAPPEND,receipt,preappend)).status());assertTrue(manager.ready());
   for(int i=0;i<active.size();i++)active.get(i).finish(i<10?transfer:preappend);assertEquals(0,payloadBytes());
  }finally{for(var workspace:active)workspace.close();}
 }
 @Test void partialZeroAndOversendNeverExceedDeclaredSizeOrCreateASecondGeneration()throws Exception{
  var budget=budget(Role.TRANSFER);
  for(long count:new long[]{0,2,4})try(var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,3),budget)){
   assertEquals(422,assertThrows(DmFailure.class,()->workspace.write(new Repeated(count),budget)).status());assertTrue(payloadBytes()<=3);unavailable(()->workspace.write(new Repeated(3),budget));workspace.finish(budget);assertEquals(0,payloadBytes());
  }
  try(var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,100_000_000),budget)){assertEquals(413,assertThrows(DmFailure.class,()->workspace.write(new Repeated(100_000_001),budget)).status());assertTrue(payloadBytes()<=100_000_000);workspace.finish(budget);}
 }
 @Test void failedActualUnlinkRetainsLaneAcrossManagerRecreationAndIndependentIdentity()throws Exception{
  var budget=budget(Role.TRANSFER);var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,7),budget);Path payload=workspace.write(new Repeated(7),budget).path();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("r-x------"));
  try{unavailable(()->workspace.finish(budget));assertEquals(7,Files.size(payload));workspace.close();assertFalse(manager.ready());unavailable(()->manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),budget));assertEquals(7,payloadBytes());}
  finally{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));}
  manager.close();manager=open();assertTrue(manager.ready());assertEquals(0,payloadBytes());try(var accepted=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),budget)){accepted.finish(budget);}
 }
 @Test void actualReadersKeepProcessLockAndOldCapabilitiesCannotDeleteReplacement()throws Exception{
  var budget=budget(Role.PREAPPEND);var old=manager.reserve(Role.PREAPPEND,identity(Role.PREAPPEND,11),budget);Path path=old.write(new Repeated(11),budget).path();var reader=old.retain(budget);
  try(var input=Files.newInputStream(path)){assertEquals(0,input.read());assertTrue(child("blocked","unused").contains("locked=true"));
   unavailable(()->old.finish(budget));old.close();manager.close();
   unavailable(()->open());
   assertTrue(child("blocked","unused").contains("locked=true"));assertEquals(11,payloadBytes());}finally{reader.close();reader.close();}
  manager=open();assertTrue(manager.ready());var nextBudget=budget(Role.PREAPPEND);try(var current=manager.reserve(Role.PREAPPEND,identity(Role.PREAPPEND,11),nextBudget)){current.write(new Repeated(11),nextBudget);unavailable(()->old.finish(nextBudget));old.close();assertEquals(11,payloadBytes());current.finish(nextBudget);}
 }
 @Test void canceledBlockedWriterCannotTransferLockOrWriteSuccessAfterSettlement()throws Exception{
  var budget=budget(Role.TRANSFER);var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),budget);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
  var worker=Thread.ofVirtual().start(()->{try{workspace.write(new InputStream(){public int read(){entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return -1;}},budget);}catch(Throwable failed){failure.set(failed);}});
  try{assertTrue(entered.await(2,TimeUnit.SECONDS));budget.cancel();workspace.close();manager.close();unavailable(()->open());assertFalse(manager.ready());}finally{release.countDown();worker.join(3000);}
  assertFalse(worker.isAlive());assertInstanceOf(DmFailure.class,failure.get());manager=open();assertTrue(manager.ready());assertEquals(0,payloadBytes());
 }
 @Test void firstOwnedUnlinkFailureNeverImplicitlyRetriesAndRecoveredAbsencePrecedesReuse()throws Exception{
  manager.close();var calls=new AtomicInteger();manager=new FileGenericTemp(root,files,path->{calls.incrementAndGet();throw new IOException("test-only owned unlink failure");},1000);var budget=budget(Role.PREAPPEND);
  try(var workspace=manager.reserve(Role.PREAPPEND,identity(Role.PREAPPEND,5),budget)){workspace.write(new Repeated(5),budget);unavailable(()->workspace.finish(budget));unavailable(()->workspace.finish(budget));assertEquals(1,calls.get());assertEquals(5,payloadBytes());}
  manager.close();assertEquals(1,calls.get());manager=open();assertTrue(manager.ready());assertEquals(0,payloadBytes());
 }
 @Test void unknownGenerationsMalformedStateAndNextNeverAuthorizePayloadDeletion()throws Exception{
  Path foreign=root.resolve("dkp-transfer-unknown.bin");Files.write(foreign,new byte[9]);Files.setPosixFilePermissions(foreign,PosixFilePermissions.fromString("rw-------"));assertFalse(manager.ready());assertEquals(9,Files.size(foreign));Files.delete(foreign);
  Path state=root.resolve(".upload-temp/transfer-00.json");Files.writeString(state,"{}");Files.setPosixFilePermissions(state,PosixFilePermissions.fromString("rw-------"));assertFalse(manager.ready());unavailable(()->manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),budget(Role.TRANSFER)));Files.delete(state);
  Path next=root.resolve(".upload-temp/transfer-00.next");Files.writeString(next,"uncommitted");Files.setPosixFilePermissions(next,PosixFilePermissions.fromString("rw-------"));assertTrue(manager.ready());assertFalse(Files.exists(next));
 }
 @Test void wrongRoleLaneAndLinkedPayloadFailClosedWithExactBytesPreserved()throws Exception{
  unavailable(()->manager.reserve(Role.TRANSFER,identity(Role.PREAPPEND,1),budget(Role.TRANSFER)));unavailable(()->manager.reserve(Role.PREAPPEND,identity(Role.TRANSFER,1),budget(Role.PREAPPEND)));
  var budget=budget(Role.TRANSFER);try(var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,3),budget)){Path payload=workspace.write(new Repeated(3),budget).path();Path alias=root.resolve("foreign-hardlink");Files.createLink(alias,payload);unavailable(()->workspace.finish(budget));assertEquals(3,Files.size(payload));Files.delete(alias);}
  assertTrue(manager.ready());assertEquals(0,payloadBytes());
 }
 @Test void actualSeparateJvmCrashAtEveryPublicationBoundaryRecoversOnlyTheCommittedGeneration()throws Exception{
  manager.close();for(String point:List.of("NEXT_FORCED:RESERVED","RENAMED:RESERVED","RECORD_FORCED:RESERVED","RECORD_FORCED:MAY_EXIST","BEFORE_CREATE:MAY_EXIST","AFTER_WRITE:MAY_EXIST","PAYLOAD_FORCED:MAY_EXIST","RECORD_FORCED:DELETE_PENDING","BEFORE_DELETE:DELETE_PENDING","AFTER_DELETE:DELETE_PENDING","RECORD_FORCED:ABSENT","RECORD_REMOVED:ABSENT")){
   String crashed=child("crash",point);assertTrue(crashed.contains("crash=true"),crashed);assertTrue(payloadBytes()<=7);String recovered=child("recover",point);assertTrue(recovered.contains("payloadBytes=0"),recovered);assertEquals(0,payloadBytes());assertNotEquals(crashed.split(";",2)[0],recovered.split(";",2)[0]);
  }manager=open();assertTrue(manager.ready());
 }
 @Test void actualSecondJvmCannotAcquireAnExistingOwnerLock()throws Exception{assertTrue(child("blocked","unused").contains("locked=true"));assertTrue(manager.ready());}
 @Test void recoveryTimeoutRetainsTheActualBlockedUnlinkOwnerWithoutQueueOrNewGeneration()throws Exception{
  var budget=budget(Role.TRANSFER);try(var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,5),budget)){workspace.write(new Repeated(5),budget);}manager.close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var settled=new CountDownLatch(1);
  manager=new FileGenericTemp(root,files,path->{entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}try{Files.delete(path);}finally{settled.countDown();}},1000);
  try{assertFalse(manager.ready());assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(manager.ready());unavailable(()->manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),budget));manager.close();unavailable(()->open());assertEquals(5,payloadBytes());}finally{release.countDown();assertTrue(settled.await(3,TimeUnit.SECONDS));}
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);FileGenericTemp next=null;while(next==null&&System.nanoTime()<until)try{next=open();}catch(DmFailure stillSettling){Thread.onSpinWait();}assertNotNull(next);manager=next;assertTrue(manager.ready());assertEquals(0,payloadBytes());
 }
 @Test void activeReadinessIoIsBoundedAndCannotReleaseAStillRunningProbeOrWriterOwner()throws Exception{
  manager.close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
  manager=new FileGenericTemp(root,files,Files::delete,1000,(event,record)->{if(event==FileGenericTemp.Event.HEALTH_CHECK){entered.countDown();try{awaitIgnoringInterrupt(release);}finally{exited.countDown();}}});
  var budget=budget(Role.TRANSFER);var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,3),budget);workspace.write(new Repeated(3),budget);
  try{assertFalse(manager.ready());assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(manager.ready());workspace.close();manager.close();unavailable(()->open());assertEquals(3,payloadBytes());}finally{release.countDown();assertTrue(exited.await(3,TimeUnit.SECONDS));}
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);FileGenericTemp next=null;while(next==null&&System.nanoTime()<until)try{next=open();}catch(DmFailure stillSettling){Thread.onSpinWait();}assertNotNull(next);manager=next;assertTrue(manager.ready());assertEquals(0,payloadBytes());
 }
 @Test void requestRecoveryIsBoundedAndCannotOverlapAStillExecutableUnlink()throws Exception{
  var original=budget(Role.TRANSFER);try(var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,7),original)){workspace.write(new Repeated(7),original);}manager.close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var settled=new CountDownLatch(1);
  manager=new FileGenericTemp(root,files,path->{entered.countDown();awaitIgnoringInterrupt(release);try{Files.delete(path);}finally{settled.countDown();}},1000);
  try{unavailable(()->manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),budget(Role.TRANSFER)));assertTrue(entered.await(2,TimeUnit.SECONDS));assertFalse(manager.ready());unavailable(()->manager.reserve(Role.PREAPPEND,identity(Role.PREAPPEND,1),budget(Role.PREAPPEND)));assertEquals(7,payloadBytes());manager.close();unavailable(()->open());}finally{release.countDown();assertTrue(settled.await(3,TimeUnit.SECONDS));}
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);FileGenericTemp next=null;while(next==null&&System.nanoTime()<until)try{next=open();}catch(DmFailure stillSettling){Thread.onSpinWait();}assertNotNull(next);manager=next;assertTrue(manager.ready());assertEquals(0,payloadBytes());
 }
 @Test void aFreshOrWrongRoleBudgetCannotExtendAnOwnedCapability()throws Exception{
  var original=budget(Role.PREAPPEND);try(var workspace=manager.reserve(Role.PREAPPEND,identity(Role.PREAPPEND,3),original)){
   unavailable(()->workspace.write(new Repeated(3),budget(Role.PREAPPEND)));workspace.write(new Repeated(3),original);unavailable(()->workspace.finish(budget(Role.TRANSFER)));assertEquals(3,payloadBytes());workspace.finish(original);
  }
  unavailable(()->manager.reserve(Role.PREAPPEND,identity(Role.PREAPPEND,1),budget(Role.TRANSFER)));unavailable(()->manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),budget(Role.PREAPPEND)));
 }
 @Test void staleCapabilitiesDuplicateWritesAndWrongMetadataCannotRetargetAnotherGeneration()throws Exception{
  var budget=budget(Role.TRANSFER);var old=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,3),budget);unavailable(()->old.retain(budget));
  old.write(new InputStream(){boolean empty=true;long remaining=3;public int read(){throw new AssertionError();}public int read(byte[] b,int o,int n){if(empty){empty=false;return 0;}if(remaining==0)return -1;int count=(int)Math.min(n,remaining);Arrays.fill(b,o,o+count,(byte)0);remaining-=count;return count;}},budget);
  unavailable(()->old.write(new Repeated(3),budget));old.finish(budget);old.close();
  try(var current=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,3),budget)){current.write(new Repeated(3),budget);unavailable(()->old.finish(budget));unavailable(()->old.retain(budget));assertEquals(3,payloadBytes());Path state=root.resolve(".upload-temp/transfer-00.json");byte[] original=Files.readAllBytes(state);var record=GenericTempLedger.decode(original);Files.write(state,record.epoch(UUID.randomUUID()).encode());unavailable(()->current.finish(budget));assertEquals(3,payloadBytes());Files.write(state,original);current.finish(budget);}
 }
 @Test void unsafeMetadataLegacyAmbiguityAndReplacedLockNeverAuthorizeAdoption()throws Exception{
  manager.close();Path marker=root.resolve(".upload-temp/.owner");Files.setPosixFilePermissions(marker,PosixFilePermissions.fromString("rw-r-----"));unavailable(()->open());Files.setPosixFilePermissions(marker,PosixFilePermissions.fromString("rw-------"));
  Path state=root.resolve(".upload-temp/transfer-00.json");Files.createSymbolicLink(state,root.resolve(".spool-authority"));unavailable(()->open());Files.delete(state);manager=open();Path extra=root.resolve(".upload-temp/unknown");Files.writeString(extra,"not owned");assertFalse(manager.ready());Files.delete(extra);
  Path lock=root.resolve(".upload-temp.lock");Files.delete(lock);Files.createFile(lock,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));assertFalse(manager.ready());manager.close();manager=open();assertTrue(manager.ready());manager.close();
  try(var paths=Files.walk(root.resolve(".upload-temp"))){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}Path legacy=root.resolve("dkp-bytes-ambiguous.tmp");Files.write(legacy,new byte[7]);unavailable(()->open());assertEquals(7,Files.size(legacy));Files.delete(legacy);manager=open();
  unavailable(()->new FileGenericTemp(root.resolve("child/.."),files));unavailable(()->new FileGenericTemp(Path.of("relative"),files));unavailable(()->new FileGenericTemp(root,files,Files::delete,0));unavailable(()->new FileGenericTemp(root,files,Files::delete,60001));
 }
 @Test void verificationNamespacePayloadIsNeverAdoptedOrSweptByGenericRecovery()throws Exception{
  try(var verification=org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.RealVerificationWorkspaceFixture.open(root);var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE);var owned=verification.reserve(VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example","other-scope"),UUID.randomUUID(),UUID.randomUUID(),5),phase)){
   var file=owned.source().write(new Repeated(5),phase).path();var budget=budget(Role.PREAPPEND);try(var generic=manager.reserve(Role.PREAPPEND,identity(Role.PREAPPEND,3),budget)){generic.write(new Repeated(3),budget);}assertTrue(manager.ready());assertEquals(0,payloadBytes());assertEquals(5,Files.size(file));owned.finish(phase);
  }
 }
 @Test void admittedDifferentLanesSynchronizeMetadataWithoutSpuriousFailure()throws Exception{
  manager.close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean(true);
  manager=new FileGenericTemp(root,files,Files::delete,60_000,(event,record)->{if(event==FileGenericTemp.Event.RECORD_FORCED&&record.state()==GenericTempLedger.State.MAY_EXIST&&once.compareAndSet(true,false)){entered.countDown();awaitIgnoringInterrupt(release);}});
  var firstBudget=budget(Role.TRANSFER);var secondBudget=budget(Role.TRANSFER);var first=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,7),firstBudget);var second=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,7),secondBudget);
  var firstFailure=new AtomicReference<Throwable>();var secondFailure=new AtomicReference<Throwable>();var secondStarted=new CountDownLatch(1);
  Thread a=Thread.ofVirtual().start(()->{try{first.write(new Repeated(7),firstBudget);}catch(Throwable failure){firstFailure.set(failure);}});assertTrue(entered.await(2,TimeUnit.SECONDS));
  Thread b=Thread.ofVirtual().start(()->{secondStarted.countDown();try{second.write(new Repeated(7),secondBudget);}catch(Throwable failure){secondFailure.set(failure);}});
  try{assertTrue(secondStarted.await(2,TimeUnit.SECONDS));assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->{while(b.getState()!=Thread.State.WAITING&&b.getState()!=Thread.State.TIMED_WAITING)Thread.onSpinWait();});}finally{release.countDown();a.join(3000);b.join(3000);}
  assertFalse(a.isAlive());assertFalse(b.isAlive());assertNull(firstFailure.get());assertNull(secondFailure.get());assertEquals(14,payloadBytes());first.finish(firstBudget);second.finish(secondBudget);first.close();second.close();assertEquals(0,payloadBytes());
 }
 @Test void deadlineInterruptAndStaleOwnerRejectEveryPostMetadataWaitDispatch()throws Exception{
  for(String mode:List.of("deadline","interrupt","stale")){
   manager.close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean(true);
   manager=new FileGenericTemp(root,files,Files::delete,60_000,(event,record)->{if(event==FileGenericTemp.Event.RECORD_FORCED&&record.state()==GenericTempLedger.State.MAY_EXIST&&once.compareAndSet(true,false)){entered.countDown();awaitIgnoringInterrupt(release);}});
   var firstBudget=budget(Role.TRANSFER);var clock=new AdjustableClock();var secondBudget=new GenericTempBudget(clock,Role.PREAPPEND);var first=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),firstBudget);var second=manager.reserve(Role.PREAPPEND,identity(Role.PREAPPEND,1),secondBudget);var reads=new AtomicInteger();var failure=new AtomicReference<Throwable>();
   Thread a=Thread.ofVirtual().start(()->first.write(new Repeated(1),firstBudget));assertTrue(entered.await(2,TimeUnit.SECONDS));Thread b=Thread.ofVirtual().start(()->{try{second.write(new InputStream(){public int read(){reads.incrementAndGet();return -1;}},secondBudget);}catch(Throwable expected){failure.set(expected);}});
   try{assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->{while(b.getState()!=Thread.State.WAITING&&b.getState()!=Thread.State.TIMED_WAITING)Thread.onSpinWait();});if(mode.equals("deadline"))clock.advance(60);if(mode.equals("interrupt"))b.interrupt();if(mode.equals("stale"))second.close();}finally{release.countDown();a.join(3000);b.join(3000);}
   assertFalse(a.isAlive());assertFalse(b.isAlive());assertInstanceOf(DmFailure.class,failure.get());assertEquals(0,reads.get());assertEquals(1,payloadBytes());first.finish(firstBudget);first.close();second.close();assertTrue(manager.ready());assertEquals(0,payloadBytes());
  }
 }
 static void awaitIgnoringInterrupt(CountDownLatch release){while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}}
 static final class AdjustableClock extends java.time.Clock{volatile java.time.Instant instant=java.time.Instant.parse("2026-11-01T00:00:00Z");void advance(long seconds){instant=instant.plusSeconds(seconds);}public java.time.Instant instant(){return instant;}public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}}
 String child(String mode,String point)throws Exception{
  var classpath=new LinkedHashSet<String>(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())if(loader instanceof java.net.URLClassLoader urls)for(var url:urls.getURLs())if("file".equals(url.getProtocol()))classpath.add(Path.of(url.toURI()).toString());
  Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",String.join(java.io.File.pathSeparator,classpath),Probe.class.getName(),mode,point,root.toString()).redirectErrorStream(true).start();try{assertTrue(process.waitFor(20,TimeUnit.SECONDS));byte[] output=process.getInputStream().readNBytes(4097);assertTrue(output.length<=4096);String text=new String(output,java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),text);return text;}finally{if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
 }
 public static final class Probe{
  public static void main(String[] args)throws Exception{
   assertEquals(3,args.length);assertTrue(Set.of("crash","recover","blocked").contains(args[0]));Path root=Path.of(args[2]);var trusted=new TrustedAuthorityFiles(new TrustedAuthorityFiles.Platform("Linux",false,new UnixSystem().getUid()));
   if(args[0].equals("blocked")){assertEquals(503,assertThrows(DmFailure.class,()->new FileGenericTemp(root,trusted)).status());System.out.println("pid="+ProcessHandle.current().pid()+";locked=true");return;}
   if(args[0].equals("recover")){try(var manager=new FileGenericTemp(root,trusted)){assertTrue(manager.ready());try(var paths=Files.list(root)){assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith("dkp-transfer-")));}System.out.println("pid="+ProcessHandle.current().pid()+";payloadBytes=0");}return;}
   String[] selected=args[1].split(":");var event=FileGenericTemp.Event.valueOf(selected[0]);var state=GenericTempLedger.State.valueOf(selected[1]);
   try(var manager=new FileGenericTemp(root,trusted,Files::delete,60_000,(at,record)->{if(at==event&&record.state()==state){System.out.println("pid="+ProcessHandle.current().pid()+";crash=true");System.out.flush();Runtime.getRuntime().halt(0);}})){
    var budget=new GenericTempBudget(Clock.systemUTC(),Role.TRANSFER);try(var workspace=manager.reserve(Role.TRANSFER,Identity.of("tenant",new Actor("https://issuer.example","process-fixture"),UUID.randomUUID(),UUID.randomUUID(),null,event==FileGenericTemp.Event.AFTER_WRITE?17:7),budget)){workspace.write(new Repeated(7),budget);workspace.finish(budget);}throw new AssertionError("Selected fault boundary was not reached");
   }
  }
 }
 static final class Repeated extends InputStream{long remaining;Repeated(long count){remaining=count;}public int read(){return remaining--<=0?-1:0;}public int read(byte[] bytes,int offset,int length){if(remaining==0)return -1;int n=(int)Math.min(remaining,length);Arrays.fill(bytes,offset,offset+n,(byte)0);remaining-=n;return n;}}
 @Test void recoveryLaunchGapNeverWaitsBehindAnAlreadyAdmittedMetadataPublisher()throws Exception{
  manager.close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  manager=new FileGenericTemp(root,files,Files::delete,1000,(event,record)->{if(event==FileGenericTemp.Event.RECORD_FORCED&&record.state()==GenericTempLedger.State.MAY_EXIST){entered.countDown();awaitUninterruptibly(release);}});
  var budget=budget(Role.TRANSFER);var workspace=manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,7),budget);
  var field=FileGenericTemp.class.getDeclaredField("gate");field.setAccessible(true);var gate=new AdmissionGapLock();field.set(manager,gate);
  var response=new CompletableFuture<Boolean>();var failure=new AtomicReference<Throwable>();
  var probe=Thread.ofVirtual().start(()->{gate.target=Thread.currentThread();try{response.complete(manager.ready());}catch(Throwable error){response.completeExceptionally(error);}});Thread writer=null;
  try{
   assertTrue(gate.unlocked.await(2,TimeUnit.SECONDS));writer=Thread.ofVirtual().start(()->{try{workspace.write(new Repeated(7),budget);}catch(Throwable error){failure.set(error);}});assertTrue(entered.await(2,TimeUnit.SECONDS));gate.resume.countDown();
   assertFalse(response.get(500,TimeUnit.MILLISECONDS),"Recovery admission must fail immediately instead of waiting outside its budget");assertTrue(writer.isAlive());unavailable(()->manager.reserve(Role.TRANSFER,identity(Role.TRANSFER,1),budget));
  }finally{gate.resume.countDown();release.countDown();if(writer!=null)writer.join(3000);probe.join(3000);}
  assertFalse(probe.isAlive());assertNotNull(writer);assertFalse(writer.isAlive());assertNull(failure.get());assertEquals(7,payloadBytes());workspace.finish(budget);workspace.close();assertTrue(manager.ready());assertEquals(0,payloadBytes());
 }
 private static void awaitUninterruptibly(CountDownLatch latch){while(latch.getCount()>0)try{latch.await();}catch(InterruptedException ignored){}}
 private static final class AdmissionGapLock extends java.util.concurrent.locks.ReentrantLock{
  volatile Thread target;final java.util.concurrent.atomic.AtomicBoolean once=new java.util.concurrent.atomic.AtomicBoolean(true);final CountDownLatch unlocked=new CountDownLatch(1),resume=new CountDownLatch(1);
  @Override public void unlock(){super.unlock();if(Thread.currentThread()==target&&once.compareAndSet(true,false)){unlocked.countDown();awaitUninterruptibly(resume);}}
 }

}
