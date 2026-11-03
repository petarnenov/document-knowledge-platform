package org.documentknowledge.loggingclient;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.documentknowledge.observability.ExecutionIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileLogSpoolTest {
    @TempDir Path root;
    private LogSpool.Binding binding() {return new LogSpool.Binding(LogWireTest.EXECUTION,"a".repeat(64));}
    private LogSpool.Stored event() {
        var prepared=LogWire.prepare(LogWireTest.event(UUID.randomUUID()),LogWireTest.EXECUTION,LogWireTest.VOCABULARY);
        return new LogSpool.Stored(prepared.id(),prepared.bytes());
    }
    private void privateWrite(Path file,byte[] bytes) throws Exception {
        Files.write(file,bytes);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
    }
    @Test void privateSnapshotRestoresExactBytesAndExclusiveOwnerSurvivesRejectedSecondOpen() throws Exception {
        Path directory=root.resolve("queue");var event=event();
        try(var spool=new FileLogSpool(directory,binding())) {
            assertTrue(spool.load().isEmpty());spool.replace(List.of(event));
            assertThrows(IOException.class,()->new FileLogSpool(directory,binding()));
            assertEquals(event.id(),spool.load().getFirst().id());
            assertEquals("rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("pending.bin"))));
            assertTrue(Files.size(directory.resolve("pending.bin"))<FileLogSpool.DISK_BYTES);
        }
        try(var recovered=new FileLogSpool(directory,binding())) {
            var retained=recovered.load();assertEquals(1,retained.size());assertArrayEquals(event.bytes(),retained.getFirst().bytes());
            byte[] copy=retained.getFirst().bytes();copy[0]=0;assertArrayEquals(event.bytes(),retained.getFirst().bytes());
            recovered.replace(List.of());assertTrue(recovered.load().isEmpty());
        }
    }
    @Test void originalCredentialAndExecutionCannotBeRelabelled() throws Exception {
        Path directory=root.resolve("queue");
        try(var spool=new FileLogSpool(directory,binding())) {spool.load();spool.replace(List.of(event()));}
        byte[] before=Files.readAllBytes(directory.resolve("pending.bin"));
        var changed=new ExecutionIdentity(LogWireTest.EXECUTION.build(),UUID.randomUUID(),LogWireTest.EXECUTION.instanceId());
        for(var binding:List.of(new LogSpool.Binding(LogWireTest.EXECUTION,"b".repeat(64)),new LogSpool.Binding(changed,"a".repeat(64)))) {
            try(var spool=new FileLogSpool(directory,binding)) {assertThrows(IOException.class,spool::load);}
            assertArrayEquals(before,Files.readAllBytes(directory.resolve("pending.bin")));
        }
    }
    @Test void corruptionTruncationTrailingBytesAndInvalidCountsRefuseWithoutDiscard() throws Exception {
        Path directory=root.resolve("queue");byte[] good;
        try(var spool=new FileLogSpool(directory,binding())) {spool.load();spool.replace(List.of());good=Files.readAllBytes(directory.resolve("pending.bin"));}
        var badCount=good.clone();badCount[badCount.length-4]=0x7f;
        for(byte[] bad:List.of(new byte[]{1},Arrays.copyOf(good,good.length-1),Arrays.copyOf(good,good.length+1),badCount)) {
            privateWrite(directory.resolve("pending.bin"),bad);
            try(var spool=new FileLogSpool(directory,binding())) {assertThrows(IOException.class,spool::load);}
            assertArrayEquals(bad,Files.readAllBytes(directory.resolve("pending.bin")));
        }
    }
    @Test void boundsDuplicateIdsClosedStateAndIncompleteOwnedStagingAreExplicit() throws Exception {
        Path directory=root.resolve("queue");var spool=new FileLogSpool(directory,binding());
        assertThrows(IOException.class,()->spool.replace(List.of()));spool.load();var event=event();spool.replace(List.of(event));
        assertThrows(IOException.class,()->spool.replace(List.of(event,event)));
        assertThrows(IOException.class,()->spool.replace(Collections.nCopies(LogProducer.MAX_EVENTS+1,event)));
        var oversized=new ArrayList<LogSpool.Stored>();
        for(int i=0;i<1025;i++)oversized.add(new LogSpool.Stored(UUID.randomUUID(),new byte[8192]));
        assertThrows(IOException.class,()->spool.replace(oversized));spool.close();spool.close();
        assertThrows(IOException.class,spool::load);assertThrows(IOException.class,()->spool.replace(List.of()));
        privateWrite(directory.resolve("pending.next"),new byte[]{1,2});
        try(var recovered=new FileLogSpool(directory,binding())) {
            assertEquals(1,recovered.load().size());recovered.replace(List.of());assertFalse(Files.exists(directory.resolve("pending.next")));
        }
        assertThrows(IllegalArgumentException.class,()->new LogSpool.Stored(new UUID(0,0),new byte[]{1}));
        assertThrows(IllegalArgumentException.class,()->new LogSpool.Stored(UUID.randomUUID(),new byte[8193]));
        assertThrows(IllegalArgumentException.class,()->new LogSpool.Binding(LogWireTest.EXECUTION,"not-a-fingerprint"));
    }
    @Test void symlinksPublicPermissionsUnknownFilesAndOrphanStagingReject() throws Exception {
        Path directory=root.resolve("queue");Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class,()->new FileLogSpool(directory,binding()));
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        privateWrite(directory.resolve("unrelated"),new byte[]{1});
        assertThrows(IOException.class,()->new FileLogSpool(directory,binding()));Files.delete(directory.resolve("unrelated"));
        privateWrite(directory.resolve("pending.next"),new byte[]{1});
        assertThrows(IOException.class,()->new FileLogSpool(directory,binding()));Files.delete(directory.resolve("pending.next"));
        Files.delete(directory.resolve("queue.lock"));
        Files.createSymbolicLink(directory.resolve("queue.lock"),root.resolve("foreign"));
        assertThrows(IOException.class,()->new FileLogSpool(directory,binding()));Files.delete(directory.resolve("queue.lock"));
        try(var spool=new FileLogSpool(directory,binding())) {spool.load();spool.replace(List.of(event()));}
        Files.setPosixFilePermissions(directory.resolve("pending.bin"),PosixFilePermissions.fromString("rw-r--r--"));
        try(var spool=new FileLogSpool(directory,binding())) {assertThrows(IOException.class,spool::load);}
        Files.delete(directory.resolve("pending.bin"));
        Files.createSymbolicLink(directory.resolve("pending.bin"),root.resolve("foreign"));
        try(var spool=new FileLogSpool(directory,binding())) {assertThrows(IOException.class,spool::load);}
        Files.createSymbolicLink(root.resolve("linked"),directory);
        assertThrows(IOException.class,()->new FileLogSpool(root.resolve("linked"),binding()));
        assertThrows(IOException.class,()->new FileLogSpool(Path.of("relative"),binding()));
    }
}
