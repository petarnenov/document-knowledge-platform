package org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp;

import com.sun.security.auth.module.UnixSystem;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.identity.Actor;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Issue335: reuse reviewed filesystem assertions with public SYSTEM construction only.
 * Standalone acceptance main, isolated nonroot Linux; no provider or platform substitute. */
public final class SystemVerificationWorkspaceAcceptance {
    public static void main(String[] args)throws Exception {
        if(args.length!=0||!"Linux".equals(System.getProperty("os.name"))||new UnixSystem().getUid()!=10001)throw new IllegalStateException("Exact unprivileged Linux profile required");
        String[] cases={
            "sourceAndFreshCopyReadbackCapabilitiesNeverOverlapOrReuseStaleGeneration",
            "physicalTwoHundredMillionBoundAndOversendAreEnforcedBeforeWrite",
            "unsafeDeletionRetainsPhysicalBytesAndReservationUntilPermissionRestored",
            "processLockAndClosedOwnerCannotAffectLaterWorkspace",
            "workerClosingRetainsLockUntilBlockedWriterActuallySettles",
            "foreignExtraGenerationAndNamespaceEntriesAreNeverAdoptedOrRemoved",
            "malformedPrivateStateAndUncommittedNextCannotAuthorizePayload",
            "lockPathReplacementInvalidatesOldOwner",
            "actualChildJvmCrashRetainsPartialBytesUntilNewProcessDurablyRecovers",
            "distinctChildJvmCannotAcquireTheLiveProcessLock",
            "retainedActualReaderPreventsCleanupAndProcessTransferUntilReleased",
            "noncanonicalRootAndUnsafePrivateMetadataFailClosedWithoutAdoption",
            "reservationsRejectDuplicateWritesConcurrentAdmissionAndInvalidScratchOrder",
            "changedOwnerMetadataAndMultiplyLinkedPayloadNeverAuthorizeDelete"};
        for(String name:cases){
            var fixture=new SystemVerificationWorkspaceAcceptance();fixture.start();
            try{SystemVerificationWorkspaceAcceptance.class.getDeclaredMethod(name).invoke(fixture);System.out.println("SYSTEM_CASE_PASS "+name);}
            finally{fixture.stop();}
        }
        System.out.println("{\"schemaVersion\":1,\"result\":\"PASS\",\"cases\":14,\"uid\":10001,\"platform\":\"Linux\",\"publicConstructorOnly\":true,\"maxPayloadBytes\":200000000,\"providerAccess\":false}");
    }
    Path root;FileVerificationWorkspace manager;
    void start()throws Exception{
        Path parent=Path.of("build/verification-private-tests").toAbsolutePath();Files.createDirectories(parent);Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));root=Files.createTempDirectory(parent,"owned-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(root.resolve(".spool-authority"),"dkp-private-spool-v1\n",StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(root.resolve(".spool-authority"),PosixFilePermissions.fromString("rw-------"));manager=open();
    }
    FileVerificationWorkspace open(){return new FileVerificationWorkspace(root);}
    void stop()throws Exception{manager.close();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    VerificationWorkspacePort.Identity identity(long size){return VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example","subject"),UUID.randomUUID(),UUID.randomUUID(),size);}
    CompleteBudget.Phase phase(){return new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE);}
    void unavailable(Runnable call){var failure=assertThrows(DmFailure.class,call::run);assertEquals(503,failure.status());assertEquals("UNAVAILABLE",failure.code());}
    long payloadBytes()throws IOException{try(var paths=Files.list(root)){return paths.filter(p->p.getFileName().toString().startsWith("dkp-verification-")).mapToLong(p->{try{return Files.size(p);}catch(IOException failure){throw new UncheckedIOException(failure);}}).sum();}}
    void sourceAndFreshCopyReadbackCapabilitiesNeverOverlapOrReuseStaleGeneration()throws Exception{
        assertTrue(manager.ready());try(var p=phase();var workspace=manager.reserve(identity(4),p)){
            var source=workspace.source().write(new ByteArrayInputStream(new byte[4]),p);assertEquals(4,source.size());assertEquals(4,payloadBytes());
            var copy=workspace.scratch(VerificationWorkspacePort.Role.COPY,p);copy.write(new ByteArrayInputStream(new byte[4]),p);assertEquals(8,payloadBytes());unavailable(()->workspace.scratch(VerificationWorkspacePort.Role.READBACK,p));copy.cleanup(p);assertEquals(4,payloadBytes());
            var readback=workspace.scratch(VerificationWorkspacePort.Role.READBACK,p);readback.write(new ByteArrayInputStream(new byte[4]),p);unavailable(()->copy.cleanup(p));assertEquals(8,payloadBytes());readback.cleanup(p);workspace.finish(p);assertEquals(0,payloadBytes());assertFalse(Files.exists(root.resolve(".verification-spool/state.json")));
        }assertTrue(manager.ready());
    }
    void physicalTwoHundredMillionBoundAndOversendAreEnforcedBeforeWrite()throws Exception{
        try(var p=phase();var workspace=manager.reserve(identity(100_000_000),p)){
            workspace.source().write(new Repeated(100_000_000),p);var scratch=workspace.scratch(VerificationWorkspacePort.Role.COPY,p);scratch.write(new Repeated(100_000_000),p);assertEquals(200_000_000,payloadBytes());unavailable(()->workspace.scratch(VerificationWorkspacePort.Role.COPY,p));scratch.cleanup(p);workspace.finish(p);
        }
        try(var p=phase();var workspace=manager.reserve(identity(100_000_000),p)){
            assertEquals(422,assertThrows(DmFailure.class,()->workspace.source().write(new Repeated(100_000_001),p)).status());assertTrue(payloadBytes()<=100_000_000);workspace.finish(p);assertEquals(0,payloadBytes());
        }
    }
    void unsafeDeletionRetainsPhysicalBytesAndReservationUntilPermissionRestored()throws Exception{
        var p=phase();var workspace=manager.reserve(identity(9),p);workspace.source().write(new Repeated(9),p);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("r-x------"));
        try{unavailable(()->workspace.finish(p));assertEquals(9,payloadBytes());workspace.close();assertFalse(manager.ready());unavailable(()->manager.reserve(identity(9),p));assertEquals(9,payloadBytes());}
        finally{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));p.close();}
        assertTrue(manager.ready());assertEquals(0,payloadBytes());try(var next=phase();var accepted=manager.reserve(identity(9),next)){accepted.finish(next);}
    }
    void processLockAndClosedOwnerCannotAffectLaterWorkspace()throws Exception{
        unavailable(()->open());var p=phase();var old=manager.reserve(identity(5),p);var oldBuffer=old.source();oldBuffer.write(new Repeated(5),p);old.close();manager.close();manager=open();assertTrue(manager.ready());
        try(var next=phase();var current=manager.reserve(identity(5),next)){current.source().write(new Repeated(5),next);unavailable(()->oldBuffer.cleanup(next));unavailable(()->old.finish(next));unavailable(()->manager.check(old,next));assertEquals(5,payloadBytes());current.finish(next);}p.close();
    }
    void workerClosingRetainsLockUntilBlockedWriterActuallySettles()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var result=new AtomicReference<Throwable>();var p=phase();var workspace=manager.reserve(identity(1),p);
        var worker=Thread.ofVirtual().start(()->{try{workspace.source().write(new InputStream(){public int read(){entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return -1;}},p);}catch(Throwable failure){result.set(failure);}});
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));workspace.close();manager.close();unavailable(()->open());assertFalse(manager.ready());}
        finally{release.countDown();worker.join(3000);p.close();}
        assertFalse(worker.isAlive());assertInstanceOf(DmFailure.class,result.get());manager=open();assertTrue(manager.ready());assertEquals(0,payloadBytes());
    }
    void foreignExtraGenerationAndNamespaceEntriesAreNeverAdoptedOrRemoved()throws Exception{
        Path foreign=root.resolve("dkp-verification-foreign.bin");Files.write(foreign,new byte[3]);Files.setPosixFilePermissions(foreign,PosixFilePermissions.fromString("rw-------"));assertFalse(manager.ready());assertEquals(3,Files.size(foreign));Files.delete(foreign);
        Path extra=root.resolve(".verification-spool/foreign");Files.writeString(extra,"not-owned");assertFalse(manager.ready());assertTrue(Files.exists(extra));Files.delete(extra);assertTrue(manager.ready());
    }
    void malformedPrivateStateAndUncommittedNextCannotAuthorizePayload()throws Exception{
        Path state=root.resolve(".verification-spool/state.json");Files.writeString(state,"{}");Files.setPosixFilePermissions(state,PosixFilePermissions.fromString("rw-------"));assertFalse(manager.ready());assertTrue(Files.exists(state));Files.delete(state);
        Path next=root.resolve(".verification-spool/state.next");Files.writeString(next,"ignored metadata");Files.setPosixFilePermissions(next,PosixFilePermissions.fromString("rw-------"));assertTrue(manager.ready());assertFalse(Files.exists(next));
    }
    void lockPathReplacementInvalidatesOldOwner()throws Exception{
        Path lock=root.resolve(".verification-spool.lock");Files.delete(lock);Files.createFile(lock,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));assertFalse(manager.ready());try(var p=phase()){unavailable(()->manager.reserve(identity(1),p));}
    }
    void actualChildJvmCrashRetainsPartialBytesUntilNewProcessDurablyRecovers()throws Exception{
        manager.close();String crashed=child("crash");assertTrue(crashed.contains("partialBytes=7"),crashed);assertEquals(7,payloadBytes());
        String recovered=child("recover");assertTrue(recovered.contains("recoveredBytes=0"),recovered);assertEquals(0,payloadBytes());
        assertNotEquals(crashed.split(";",2)[0],recovered.split(";",2)[0]);manager=open();assertTrue(manager.ready());
    }
    void distinctChildJvmCannotAcquireTheLiveProcessLock()throws Exception{String output=child("blocked");assertTrue(output.contains("locked=true"),output);assertTrue(manager.ready());}

    void retainedActualReaderPreventsCleanupAndProcessTransferUntilReleased()throws Exception{
        try(var p=phase()){
            var workspace=manager.reserve(identity(11),p);var artifact=workspace.source().write(new Repeated(11),p);
            var use=workspace.retain(p);try(var input=Files.newInputStream(artifact.path())){
                assertEquals(0,input.read());unavailable(()->workspace.finish(p));workspace.close();manager.close();unavailable(()->open());assertEquals(11,payloadBytes());
            }finally{use.close();use.close();}
        }
        manager=open();assertTrue(manager.ready());assertEquals(0,payloadBytes());
    }

    void noncanonicalRootAndUnsafePrivateMetadataFailClosedWithoutAdoption()throws Exception{
        unavailable(()->new FileVerificationWorkspace(root.resolve("child/..")));
        manager.close();Path marker=root.resolve(".verification-spool/.owner");Files.setPosixFilePermissions(marker,PosixFilePermissions.fromString("rw-r-----"));unavailable(()->open());Files.setPosixFilePermissions(marker,PosixFilePermissions.fromString("rw-------"));
        Path state=root.resolve(".verification-spool/state.json");Files.createSymbolicLink(state,root.resolve(".spool-authority"));unavailable(()->open());Files.delete(state);manager=open();assertTrue(manager.ready());
    }
    void reservationsRejectDuplicateWritesConcurrentAdmissionAndInvalidScratchOrder()throws Exception{
        try(var p=phase();var workspace=manager.reserve(identity(3),p)){
            assertTrue(manager.ready());assertEquals(429,assertThrows(DmFailure.class,()->manager.reserve(identity(3),p)).status());
            unavailable(()->workspace.scratch(VerificationWorkspacePort.Role.COPY,p));
            var source=workspace.source();source.write(new Repeated(3),p);unavailable(()->source.write(new Repeated(3),p));
            unavailable(()->workspace.scratch(VerificationWorkspacePort.Role.READBACK,p));
            var copy=workspace.scratch(VerificationWorkspacePort.Role.COPY,p);copy.write(new Repeated(3),p);copy.cleanup(p);copy.cleanup(p);
            unavailable(()->workspace.scratch(VerificationWorkspacePort.Role.COPY,p));
            var readback=workspace.scratch(VerificationWorkspacePort.Role.READBACK,p);readback.write(new Repeated(3),p);readback.cleanup(p);unavailable(()->workspace.scratch(VerificationWorkspacePort.Role.READBACK,p));workspace.finish(p);
        }
    }
    void changedOwnerMetadataAndMultiplyLinkedPayloadNeverAuthorizeDelete()throws Exception{
        try(var p=phase();var workspace=manager.reserve(identity(2),p)){
            Path payload=workspace.source().write(new Repeated(2),p).path();Path alias=root.resolve("foreign-hardlink");Files.createLink(alias,payload);
            unavailable(()->workspace.finish(p));assertEquals(2,Files.size(payload));assertEquals(2,Files.size(alias));Files.delete(alias);
        }
        manager.close();manager=open();assertTrue(manager.ready());
        try(var p=phase();var workspace=manager.reserve(identity(2),p)){
            workspace.source().write(new Repeated(2),p);Path state=root.resolve(".verification-spool/state.json");String original=Files.readString(state);
            var before=VerificationLedger.decode(Files.readAllBytes(state));Files.write(state,before.epoch(UUID.randomUUID()).encode());
            unavailable(()->workspace.finish(p));assertEquals(2,payloadBytes());Files.writeString(state,original);
        }
        assertTrue(manager.ready());assertEquals(0,payloadBytes());
    }

    String child(String mode)throws Exception{
        var classpath=new LinkedHashSet<String>(Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())if(loader instanceof java.net.URLClassLoader urls)for(var url:urls.getURLs())if("file".equals(url.getProtocol()))classpath.add(Path.of(url.toURI()).toString());
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",String.join(java.io.File.pathSeparator,classpath),Probe.class.getName(),mode,root.toString()).redirectErrorStream(true).start();
        try{assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Owned child must terminate");byte[] bytes=process.getInputStream().readNBytes(4097);assertTrue(bytes.length<=4096);String output=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,process.exitValue(),output);return output;}
        finally{if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
    }
    public static final class Probe {
        public static void main(String[] args)throws Exception{
            if(args.length!=2||!Set.of("crash","recover","blocked").contains(args[0]))throw new IllegalArgumentException("Invalid probe mode");Path root=Path.of(args[1]);
            if("blocked".equals(args[0])){try{new FileVerificationWorkspace(root);throw new AssertionError("Second process acquired ownership");}catch(DmFailure expected){assertEquals(503,expected.status());System.out.println("pid="+ProcessHandle.current().pid()+";locked=true");}return;}
            try(var manager=new FileVerificationWorkspace(root)){
                if("recover".equals(args[0])){assertTrue(manager.ready());System.out.println("pid="+ProcessHandle.current().pid()+";recoveredBytes=0");return;}
                try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE)){
                    var workspace=manager.reserve(VerificationWorkspacePort.Identity.of("tenant",new Actor("https://issuer.example","subject"),UUID.randomUUID(),UUID.randomUUID(),17),phase);
                    assertEquals(422,assertThrows(DmFailure.class,()->workspace.source().write(new Repeated(7),phase)).status());
                    System.out.println("pid="+ProcessHandle.current().pid()+";partialBytes=7");System.out.flush();Runtime.getRuntime().halt(0);
                }
            }
        }
    }
    static final class Repeated extends InputStream {
        long remaining;Repeated(long remaining){this.remaining=remaining;}
        public int read(){if(remaining--<=0)return -1;return 0;}
        public int read(byte[] bytes,int start,int length){if(remaining==0)return -1;int count=(int)Math.min(remaining,length);Arrays.fill(bytes,start,start+count,(byte)0);remaining-=count;return count;}
    }
}
