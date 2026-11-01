package org.documentknowledge.inspector;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import com.sun.security.auth.module.UnixSystem;

final class PrivateFiles {
    private PrivateFiles() { }
    static byte[] read(Path source,int maximum) throws IOException {
        Path path=source.toAbsolutePath();if(!path.normalize().equals(path))throw new IOException("unsafe secret");
        long uid=new UnixSystem().getUid();Path current=path.getRoot();
        for(Path element:path) {
            current=current.resolve(element);
            if(Files.isSymbolicLink(current))throw new IOException("unsafe secret");
            long owner=((Number)Files.getAttribute(current,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();
            var permissions=Files.getPosixFilePermissions(current,LinkOption.NOFOLLOW_LINKS);
            if((owner!=0 && owner!=uid) || permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE))throw new IOException("unsafe secret");
        }
        var permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
        if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("unsafe secret");
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        ByteBuffer buffer=ByteBuffer.allocate(maximum+1);
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) { while(buffer.hasRemaining() && channel.read(buffer)!=-1) { } }
        var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(buffer.position()>maximum || !Objects.equals(before.fileKey(),after.fileKey()) || before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("unsafe secret");
        return Arrays.copyOf(buffer.array(),buffer.position());
    }
}
