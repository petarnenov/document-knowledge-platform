package org.documentknowledge.loggingclient;

import com.sun.security.auth.module.UnixSystem;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;


/** One private pending snapshot, staging file and lifetime lock; no credentials. */
public final class FileLogSpool implements LogSpool {
    static final int SNAPSHOT_BYTES=9*1024*1024;
    static final long DISK_BYTES=64L*1024*1024;
    private static final Set<PosixFilePermission> FILE_MODE=PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIRECTORY_MODE=PosixFilePermissions.fromString("rwx------");
    private static final Set<Path> OWNERS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean locallyOwned;
    private final Path directory,pending,staging;
    private final Binding binding;
    private final long uid=new UnixSystem().getUid();
    private FileChannel channel;
    private FileLock lock;
    private boolean loaded,closed;

    public FileLogSpool(Path directory,Binding binding) throws IOException {
        this.directory=Objects.requireNonNull(directory);this.binding=Objects.requireNonNull(binding);
        pending=directory.resolve("pending.bin");staging=directory.resolve("pending.next");
        try {
            require(directory.isAbsolute());
            if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))
                Files.createDirectory(directory,PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            checkDirectory();require(OWNERS.add(directory));locallyOwned=true;
            Path lockFile=directory.resolve("queue.lock");
            if(Files.exists(lockFile,LinkOption.NOFOLLOW_LINKS))checkFile(lockFile,0);
            channel=FileChannel.open(lockFile,Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(FILE_MODE));
            checkFile(lockFile,0);lock=channel.tryLock();require(lock!=null);
            if(Files.exists(staging,LinkOption.NOFOLLOW_LINKS)) {
                checkFile(staging,SNAPSHOT_BYTES);require(Files.exists(pending,LinkOption.NOFOLLOW_LINKS));
            }
        } catch(Exception invalid) {close();throw failure();}
    }

    @Override public Binding binding() {return binding;}

    private void checkDirectory() throws IOException {
        require(directory.toRealPath().equals(directory));
        var attributes=Files.readAttributes(directory,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(attributes.isDirectory() && attributes.permissions().equals(DIRECTORY_MODE)
            && ((Number)Files.getAttribute(directory,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue()==uid);
        try(var entries=Files.newDirectoryStream(directory)) {
            for(var file:entries)require(Set.of("pending.bin","pending.next","queue.lock").contains(file.getFileName().toString()));
        }
    }

    private PosixFileAttributes checkFile(Path file,long limit) throws IOException {
        var attributes=Files.readAttributes(file,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        require(attributes.isRegularFile() && attributes.size()<=limit && attributes.permissions().equals(FILE_MODE)
            && ((Number)Files.getAttribute(file,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue()==uid);
        return attributes;
    }

    private byte[] header() throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var output=new DataOutputStream(bytes)) {
            output.writeLong(0x444b504c4f475131L); // DKPLOGQ1, private snapshot format v1.
            var execution=binding.execution();var build=execution.build();
            for(String field:List.of(build.buildId().toString(),build.component(),build.gitSha(),build.sourceFingerprint(),
                    execution.runId().toString(),execution.instanceId().toString(),binding.credentialFingerprint()))output.writeUTF(field);
        }
        return bytes.toByteArray();
    }

    @Override public List<Stored> load() throws IOException {
        try {
            require(!closed);checkDirectory();
            if(!Files.exists(pending,LinkOption.NOFOLLOW_LINKS)) {loaded=true;return List.of();}
            var before=checkFile(pending,SNAPSHOT_BYTES);byte[] bytes;
            try(var input=Files.newInputStream(pending,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                bytes=input.readNBytes(SNAPSHOT_BYTES+1);
            }
            var after=checkFile(pending,SNAPSHOT_BYTES);
            require(bytes.length>0 && bytes.length<=SNAPSHOT_BYTES && bytes.length==before.size()
                && before.size()==after.size() && Objects.equals(before.fileKey(),after.fileKey())
                && before.lastModifiedTime().equals(after.lastModifiedTime()));
            var events=new ArrayList<Stored>();
            try(var input=new DataInputStream(new ByteArrayInputStream(bytes))) {
                byte[] expected=header();require(Arrays.equals(expected,input.readNBytes(expected.length)));
                int count=input.readInt();require(count>=0 && count<=LogProducer.MAX_EVENTS);long total=0;
                for(int index=0;index<count;index++) {
                    UUID id=new UUID(input.readLong(),input.readLong());
                    int size=input.readInt();require(size>0 && size<=8192 && (total+=size)<=LogProducer.MAX_BYTES);
                    byte[] event=input.readNBytes(size);require(event.length==size);events.add(new Stored(id,event));
                }
                require(input.available()==0);
            }
            validate(events);loaded=true;return List.copyOf(events);
        } catch(Exception invalid) {throw failure();}
    }

    private static void validate(List<Stored> events) throws IOException {
        require(events.size()<=LogProducer.MAX_EVENTS);long bytes=0;var ids=new HashSet<UUID>();
        for(var event:events) {
            require(event!=null && ids.add(event.id()));bytes+=event.bytes().length;
            require(bytes<=LogProducer.MAX_BYTES);
        }
    }

    @Override public void replace(List<Stored> events) throws IOException {
        try {
            require(!closed && loaded);checkDirectory();validate(events);
            var encoded=new ByteArrayOutputStream();
            try(var output=new DataOutputStream(encoded)) {
                output.write(header());output.writeInt(events.size());
                for(var event:events) {
                    byte[] bytes=event.bytes();output.writeLong(event.id().getMostSignificantBits());
                    output.writeLong(event.id().getLeastSignificantBits());output.writeInt(bytes.length);output.write(bytes);
                }
            }
            byte[] bytes=encoded.toByteArray();
            require(bytes.length<=SNAPSHOT_BYTES);
            long retained=Files.exists(pending,LinkOption.NOFOLLOW_LINKS)?checkFile(pending,SNAPSHOT_BYTES).size():0;
            require(retained+bytes.length<=DISK_BYTES);
            if(Files.exists(staging,LinkOption.NOFOLLOW_LINKS)) {checkFile(staging,SNAPSHOT_BYTES);Files.delete(staging);}
            try(var output=FileChannel.open(staging,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(FILE_MODE))) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
            }
            checkFile(staging,SNAPSHOT_BYTES);
            Files.move(staging,pending,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var parent=FileChannel.open(directory,StandardOpenOption.READ)) {parent.force(true);}
        } catch(Exception unavailable) {throw failure();}
    }

    @Override public void close() {
        if(!closed) {
            closed=true;
            try {if(lock!=null)lock.release();}catch(IOException ignored) { /* No recursive logging. */ }
            try {if(channel!=null)channel.close();}catch(IOException ignored) { /* Preserve files. */ }
            if(locallyOwned) {OWNERS.remove(directory);locallyOwned=false;}
        }
    }
    private static void require(boolean condition) throws IOException {if(!condition)throw failure();}
    private static IOException failure() {return new IOException("LOG_SPOOL_UNAVAILABLE");}
}
