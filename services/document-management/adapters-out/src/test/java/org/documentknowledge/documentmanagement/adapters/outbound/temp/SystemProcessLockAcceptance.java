package org.documentknowledge.documentmanagement.adapters.outbound.temp;

import org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.FileVerificationWorkspace;

import com.sun.security.auth.module.UnixSystem;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.identity.Actor;

/** Issue344: public SYSTEM filesystem policy, real separate JVMs, no provider or platform substitute. */
public final class SystemProcessLockAcceptance {
    public static void main(String[] args)throws Exception {
        require("Linux".equals(System.getProperty("os.name")) && new UnixSystem().getUid()==10001);
        if(args.length==1){
            try(var ignored=new FileVerificationWorkspace(Path.of(args[0]))) {throw new AssertionError("Live owner lost its OS lock");}
            catch(DmFailure blocked){require(blocked.status()==503);System.out.println("CHILD_LOCKED");return;}
        }
        require(args.length==0);
        Path root=Files.createTempDirectory(Path.of("/fixture"),"owned-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(root.resolve(".spool-authority"),"dkp-private-spool-v1\n",StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(root.resolve(".spool-authority"),PosixFilePermissions.fromString("rw-------"));
        var manager=new FileVerificationWorkspace(root);
        try(var phase=new CompleteBudget(Clock.systemUTC()).phase(CompleteBudget.Kind.STORAGE)) {
            var identity=VerificationWorkspacePort.Identity.of("synthetic-tenant",new Actor("https://issuer.example","synthetic-subject"),UUID.randomUUID(),UUID.randomUUID(),7);
            var workspace=manager.reserve(identity,phase);workspace.source().write(new ByteArrayInputStream(new byte[7]),phase);
            var reader=workspace.retain(phase);
            try {
                child(root);System.out.println("LOCK_CASE_PASS initial_child_blocked");
                denied(root);child(root);System.out.println("LOCK_CASE_PASS failed_same_jvm_contender_preserves_os_lock");
                Path a=Files.createFile(root.resolve("redirect.lock"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try {
                    FixedInodeProcessLock.acquire(a,()->{try{Files.delete(a);Files.createLink(a,root.resolve(".verification-spool.lock"));}catch(IOException failure){throw new UncheckedIOException(failure);}},()->{});
                    throw new AssertionError("Pre-open redirected descriptor was accepted");
                } catch(IOException expected) {Files.delete(a);}
                denied(root);child(root);System.out.println("LOCK_CASE_PASS pre_open_redirect_preserves_live_os_lock_and_poison_blocks_new_open");
                workspace.close();manager.close();denied(root);child(root);
                try(var payloads=Files.list(root)){require(payloads.filter(p->p.getFileName().toString().startsWith("dkp-verification-")).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum()==7);}
                System.out.println("LOCK_CASE_PASS shutdown_retains_lock_and_bytes_until_reader_settles");
            } finally {reader.close();workspace.close();}
        } finally {manager.close();}
        try(var successor=new FileVerificationWorkspace(root)){require(successor.ready());require(!Files.exists(root.resolve(".verification-spool/state.json")));}
        System.out.println("LOCK_CASE_PASS actual_settlement_allows_successor_recovery");
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
    private static void denied(Path root){try(var ignored=new FileVerificationWorkspace(root)){throw new AssertionError("Same JVM owner bypassed");}catch(DmFailure expected){require(expected.status()==503);}}
    private static void child(Path root)throws Exception {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx64m","-XX:ActiveProcessorCount=2","-cp",System.getProperty("java.class.path"),SystemProcessLockAcceptance.class.getName(),root.toString()).redirectErrorStream(true).start();
        try {require(process.waitFor(10,TimeUnit.SECONDS));String output=new String(process.getInputStream().readNBytes(2048),java.nio.charset.StandardCharsets.UTF_8);if(process.exitValue()!=0||!output.contains("CHILD_LOCKED"))throw new AssertionError("Child process lock proof failed");}
        finally {if(process.isAlive()){process.destroyForcibly();require(process.waitFor(5,TimeUnit.SECONDS));}}
    }
    private static void require(boolean value){if(!value)throw new AssertionError("Process lock acceptance failed");}
}
