package org.documentknowledge.documentmanagement.adapters.outbound.temp;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

/** Caller validates trusted private directories/owner/mode. Path stats are not atomic descriptor identity.
 * A managed overlap/uncertain descriptor is retained, never closed against a live owner's POSIX lock.
 * At most one suspect descriptor poisons new acquisition until ALL managed owners actually close.
 * This coordinates cooperating managers in one service classloader; it is not protection against
 * arbitrary same-UID code opening/closing descriptors, native code, or hostile filesystem providers. */
public final class FixedInodeProcessLock implements AutoCloseable {
    private static final Set<Object> CLAIMS = new HashSet<>();
    private static FileChannel suspect;
    private final Object inode;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;
    private FixedInodeProcessLock(Object inode, FileChannel channel, FileLock lock) {
        this.inode=inode;this.channel=channel;this.lock=lock;
    }
    public static FixedInodeProcessLock acquire(Path path) throws IOException {return acquire(path,()->{},()->{});}
    // Deterministic Java-only races; production supplies neither hook.
    static FixedInodeProcessLock acquire(Path path,Runnable afterLock) throws IOException {return acquire(path,()->{},afterLock);}
    static synchronized FixedInodeProcessLock acquire(Path path,Runnable beforeOpen,Runnable afterLock) throws IOException {
        if(suspect!=null)throw new IOException("Unsettled process lock descriptor");
        Object inode=key(path);
        if(!CLAIMS.add(inode))throw new IOException("Process lock already owned");
        FileChannel channel=null;FileLock lock=null;boolean foreignLock=false;
        try {
            beforeOpen.run();channel=FileChannel.open(path,Set.of(StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS));
            lock=channel.tryLock();foreignLock=lock==null;afterLock.run();
            if(foreignLock || !inode.equals(key(path)))throw new IOException("Process lock unavailable");
            return new FixedInodeProcessLock(inode,channel,lock);
        } catch(Exception failure) {
            try {
                if(lock!=null)lock.release();
                // A second descriptor may be for a DIFFERENT already-owned inode after path replacement.
                // Closing it would release that owner's process-associated lock. Never infer identity from stats.
                if(channel!=null && lock==null && !foreignLock && CLAIMS.size()>1){suspect=channel;channel=null;}
                if(channel!=null)channel.close();
                CLAIMS.remove(inode);drainSuspect();
            }catch(IOException unsettled){failure.addSuppressed(unsettled);}
            if(failure instanceof IOException io)throw io;
            throw new IOException("Process lock unavailable",failure);
        }
    }
    public boolean isValid(){synchronized(FixedInodeProcessLock.class){return !closed && lock.isValid();}}
    @Override public void close() throws IOException {synchronized(FixedInodeProcessLock.class){
        if(closed)return;
        lock.release();channel.close();closed=true;CLAIMS.remove(inode);drainSuspect();
    }}
    private static void drainSuspect()throws IOException {
        if(CLAIMS.isEmpty() && suspect!=null){suspect.close();suspect=null;}
    }
    private static Object key(Path path)throws IOException {
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        Object key=attributes.fileKey();
        if(!attributes.isRegularFile() || key==null)throw new IOException("Invalid process lock inode");
        return key;
    }
}
