package org.documentknowledge.documentmanagement.application.upload;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.MAX_BYTES;
import org.documentknowledge.documentmanagement.application.DmFailure;

/** Two durable private content reservations. A failed unlink never creates free capacity. */
final class ContentSpoolStore implements AutoCloseable {
    private static final String MARKER="dkp-content-gateway-v1\n",META="dkp-content-slot-v1\n";
    private static final Set<PosixFilePermission> DIRECTORY=PosixFilePermissions.fromString("rwx------"),FILE=PosixFilePermissions.fromString("rw-------");
    private final Path directory;private final UserPrincipal owner;private final Slot[] slots=new Slot[2];
    private FileChannel lockChannel;private FileLock lock;private boolean closed;
    private static final class Slot {final int index;String actor;UUID token;boolean active;Slot(int index){this.index=index;}}
    ContentSpoolStore(Path configured){
        try{
            if(Files.isSymbolicLink(configured))throw bad();Path root=configured.toRealPath();
            Path probe=Files.createTempFile(root,".content-owner-",".probe",PosixFilePermissions.asFileAttribute(FILE));
            try{owner=Files.getOwner(probe,LinkOption.NOFOLLOW_LINKS);}finally{Files.delete(probe);}
            require(root,true);Path lockPath=root.resolve(".content-gateway.lock");
            try{Files.createFile(lockPath,PosixFilePermissions.asFileAttribute(FILE));sync(root);}catch(FileAlreadyExistsException existing){/* Validate the persistent lock before opening it. */}
            require(lockPath,false);lockChannel=FileChannel.open(lockPath,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            lock=lockChannel.tryLock();if(lock==null)throw bad();
            directory=root.resolve("content-gateway");
            if(!exists(directory))initialize(root);
            require(directory,true);validateNamespace();
            for(int i=0;i<2;i++){
                Slot slot=slots[i]=new Slot(i);Path meta=meta(slot),bytes=bytes(slot);
                if(exists(meta)){require(meta,false);String value=read(meta,128);if(!value.matches(META+"[a-f0-9]{64}\\n"))throw bad();slot.actor=value.substring(META.length(),META.length()+64);slot.token=UUID.randomUUID();}
                else if(exists(bytes))throw bad();
                if(exists(bytes))checkBytes(bytes);
            }
            recover();
        }catch(IOException|RuntimeException failure){releaseLock();throw bad();}
    }
    private void initialize(Path root)throws IOException{
        // Pre-upgrade shared spool remnants have no trustworthy content ownership. Never delete/adopt them.
        try(var entries=Files.newDirectoryStream(root,"dkp-bytes-*.tmp")){if(entries.iterator().hasNext())throw bad();}
        Path fresh=Files.createTempDirectory(root,".content-initialize-",PosixFilePermissions.asFileAttribute(DIRECTORY));
        try{
            writeNew(fresh.resolve(".owner"),MARKER);sync(fresh);
            Files.move(fresh,directory,StandardCopyOption.ATOMIC_MOVE);sync(root);
        }finally{if(Files.exists(fresh,LinkOption.NOFOLLOW_LINKS)){Files.deleteIfExists(fresh.resolve(".owner"));Files.delete(fresh);}}
    }
    private static boolean exists(Path path)throws IOException{try{Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}catch(NoSuchFileException absent){return false;}}
    private void require(Path path,boolean dir)throws IOException{
        PosixFileAttributes a=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(a.isSymbolicLink()||!(dir?a.isDirectory():a.isRegularFile())||!a.owner().equals(owner)||!a.permissions().equals(dir?DIRECTORY:FILE))throw bad();
    }
    private static void sync(Path dir)throws IOException{try(var channel=FileChannel.open(dir,StandardOpenOption.READ)){channel.force(true);}}
    private static void writeNew(Path file,String value)throws IOException{
        try(var channel=FileChannel.open(file,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(FILE))){ByteBuffer b=StandardCharsets.US_ASCII.encode(value);while(b.hasRemaining())channel.write(b);channel.force(true);}
    }
    private static String read(Path file,int maximum)throws IOException{
        try(var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){if(channel.size()>maximum)throw bad();ByteBuffer b=ByteBuffer.allocate(maximum+1);while(channel.read(b)>0){}if(b.position()>maximum)throw bad();b.flip();return StandardCharsets.US_ASCII.decode(b).toString();}
    }
    private Path bytes(Slot slot){return directory.resolve("slot-"+slot.index+".bytes");}
    private Path meta(Slot slot){return directory.resolve("slot-"+slot.index+".meta");}
    private void checkBytes(Path file)throws IOException{require(file,false);if(Files.size(file)>MAX_BYTES)throw bad();}
    private void validateNamespace()throws IOException{
        require(directory,true);Path marker=directory.resolve(".owner");require(marker,false);if(!read(marker,64).equals(MARKER))throw bad();
        Set<String> names=Set.of(".owner","slot-0.bytes","slot-0.meta","slot-1.bytes","slot-1.meta");
        try(var entries=Files.newDirectoryStream(directory)){for(Path entry:entries)if(!names.contains(entry.getFileName().toString()))throw bad();}
    }
    private void validateSlot(Slot slot)throws IOException{
        boolean metadata=exists(meta(slot)),data=exists(bytes(slot));
        if(slot.actor==null){if(metadata||data)throw bad();return;}
        if(metadata){require(meta(slot),false);if(!read(meta(slot),128).equals(META+slot.actor+"\n"))throw bad();}
        else if(data)throw bad();
        if(data)checkBytes(bytes(slot));
    }
    private boolean clean(Slot slot){
        if(slot.active||slot.actor==null)return false;
        // Invalid ownership is uncertainty, never permission to remove an unknown file.
        try{validateSlot(slot);}catch(IOException failure){throw bad();}
        try{
            Path data=bytes(slot);if(exists(data))Files.delete(data);if(exists(data))return false;
            Path metadata=meta(slot);if(exists(metadata))Files.delete(metadata);if(exists(metadata))return false;
            sync(directory);slot.actor=null;slot.token=null;return true;
        }catch(IOException unavailable){return false;}
    }
    private void recover(){for(Slot slot:slots)if(slot!=null&&!slot.active&&slot.actor!=null)clean(slot);}
    synchronized Lease reserve(String actor){
        if(closed||actor==null||!actor.matches("[a-f0-9]{64}"))throw bad();
        try{validateNamespace();for(Slot slot:slots)validateSlot(slot);}catch(IOException failure){throw bad();}
        recover();for(Slot slot:slots)if(actor.equals(slot.actor))throw quota();
        Slot free=Arrays.stream(slots).filter(s->s.actor==null).findFirst().orElseThrow(ContentSpoolStore::quota);
        free.actor=actor;free.token=UUID.randomUUID();boolean created=false;
        try{
            // Durable actor reservation precedes the first private source byte.
            writeNew(meta(free),META+actor+"\n");created=true;sync(directory);writeNew(bytes(free),"");sync(directory);
            free.active=true;return new Lease(free,free.token);
        }catch(IOException|RuntimeException failure){if(created)clean(free);throw bad();}
    }
    private synchronized void release(Slot slot,UUID token){
        if(!token.equals(slot.token))return;if(lock==null||!lock.isValid())throw bad();slot.active=false;try{if(!clean(slot))throw bad();}finally{if(closed&&Arrays.stream(slots).noneMatch(s->s.active))releaseLock();}
    }
    private void releaseLock(){try{if(lock!=null)lock.release();}catch(IOException ignored){/* Closing the owning channel is the second release path. */}finally{try{if(lockChannel!=null)lockChannel.close();}catch(IOException ignored){/* Durable slots remain for the next exclusive owner. */}lock=null;lockChannel=null;}}
    @Override public synchronized void close(){if(closed)return;closed=true;try{recover();}finally{if(Arrays.stream(slots).filter(Objects::nonNull).noneMatch(s->s.active))releaseLock();}}
    final class Lease implements AutoCloseable {
        private final Slot slot;private final UUID token;
        Lease(Slot slot,UUID token){this.slot=slot;this.token=token;}
        String write(InputStream source,long expected,Clock clock)throws IOException{
            try{if(expected<1||expected>MAX_BYTES)throw bad();var digest=MessageDigest.getInstance("SHA-256");long count=0;var until=clock.instant().plusSeconds(300);
                try(var output=FileChannel.open(bytes(slot),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    byte[] buffer=new byte[65536];int n;
                    while((n=source.read(buffer))!=-1){if(clock.instant().isAfter(until))throw bad();count=Math.addExact(count,n);if(count>MAX_BYTES)throw new DmFailure(413,"PAYLOAD_TOO_LARGE");if(count>expected)throw new DmFailure(422,"SIZE_MISMATCH");digest.update(buffer,0,n);ByteBuffer b=ByteBuffer.wrap(buffer,0,n);while(b.hasRemaining())output.write(b);}
                    if(count!=expected)throw new DmFailure(422,"SIZE_MISMATCH");output.force(true);
                }return HexFormat.of().formatHex(digest.digest());
            }catch(java.security.NoSuchAlgorithmException impossible){throw bad();}
        }
        FileChannel open()throws IOException{synchronized(ContentSpoolStore.this){if(closed||!slot.active||!token.equals(slot.token))throw bad();checkBytes(bytes(slot));return FileChannel.open(bytes(slot),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);}}
        @Override public void close(){release(slot,token);}
    }
    private static DmFailure bad(){return UploadPolicy.unavailable();}
    private static DmFailure quota(){return new DmFailure(429,"QUOTA_EXCEEDED");}
}
