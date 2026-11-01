package org.documentknowledge.documentmanagement.adapters.outbound.temp;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FixedInodeProcessLockTest {
    @TempDir Path root;
    @Test void sameInodeAliasesCannotOpenSecondDescriptorAndReleasedOwnerIsIdempotent()throws Exception {
        Path file=Files.createFile(root.resolve("lock"));Path alias=Files.createLink(root.resolve("alias"),file);
        var owner=FixedInodeProcessLock.acquire(file);
        try {assertTrue(owner.isValid());assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(alias));assertTrue(owner.isValid());}
        finally {owner.close();}
        owner.close();assertFalse(owner.isValid());try(var successor=FixedInodeProcessLock.acquire(alias)){assertTrue(successor.isValid());}
    }
    @Test void externalOwnerFailureReleasesOnlyOwnClaimAndDoesNotInvalidateExternalLock()throws Exception {
        Path file=Files.createFile(root.resolve("lock"));
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),Holder.class.getName(),file.toString()).redirectErrorStream(true).start();
        try {
            var line=new java.util.concurrent.CompletableFuture<String>();Thread.ofVirtual().start(()->{try{line.complete(new BufferedReader(new InputStreamReader(child.getInputStream())).readLine());}catch(Exception e){line.completeExceptionally(e);}});
            assertEquals("HELD",line.get(5,TimeUnit.SECONDS));
            assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(file));assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(file));
            child.getOutputStream().write(1);child.getOutputStream().flush();assertTrue(child.waitFor(5,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
        } finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
        try(var successor=FixedInodeProcessLock.acquire(file)){assertTrue(successor.isValid());}
    }
    @Test void nonRegularMissingAndSymlinkPathsDoNotAcquireOrPoisonClaims()throws Exception {
        assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(root));assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(root.resolve("missing")));
        Path file=Files.createFile(root.resolve("lock"));Path alias=Files.createSymbolicLink(root.resolve("symlink"),file);
        assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(alias));try(var owner=FixedInodeProcessLock.acquire(file)){assertTrue(owner.isValid());}
    }
    @Test void replacedPathAfterLockAcquisitionCannotPublishOwnershipOrPoisonEitherInode()throws Exception {
        Path file=Files.createFile(root.resolve("lock"));Path old=root.resolve("old");
        assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(file,()->{try{Files.move(file,old);Files.createFile(file);}catch(IOException failure){throw new UncheckedIOException(failure);}}));
        try(var original=FixedInodeProcessLock.acquire(old);var replacement=FixedInodeProcessLock.acquire(file)){assertTrue(original.isValid());assertTrue(replacement.isValid());}
        assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(file,()->{throw new IllegalStateException("test-only hook failure");}));
        try(var successor=FixedInodeProcessLock.acquire(file)){assertTrue(successor.isValid());}
    }
    @Test void preOpenRedirectToManagedInodeRetainsSuspectAndBlocksEveryFurtherOpenUntilActualSettlement()throws Exception {
        Path a=Files.createFile(root.resolve("a")),b=Files.createFile(root.resolve("b")),c=Files.createFile(root.resolve("c"));
        var owner=FixedInodeProcessLock.acquire(b);var other=FixedInodeProcessLock.acquire(c);
        try {
            assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(a,()->{try{Files.delete(a);Files.createLink(a,b);}catch(IOException failure){throw new UncheckedIOException(failure);}},()->{}));
            assertTrue(owner.isValid());assertTrue(other.isValid());
            var calls=new java.util.concurrent.atomic.AtomicInteger();assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(a,calls::incrementAndGet,()->{}));assertEquals(0,calls.get());
            owner.close();assertThrows(IOException.class,()->FixedInodeProcessLock.acquire(b,calls::incrementAndGet,()->{}));assertEquals(0,calls.get());
        } finally {owner.close();other.close();}
        try(var successor=FixedInodeProcessLock.acquire(b)){assertTrue(successor.isValid());}
    }
    public static final class Holder {
        public static void main(String[] args)throws Exception {
            try(var channel=FileChannel.open(Path.of(args[0]),StandardOpenOption.WRITE);var lock=channel.lock()) {System.out.println("HELD");System.out.flush();if(System.in.read()!=1)throw new AssertionError();if(!lock.isValid())throw new AssertionError();}
        }
    }
}
