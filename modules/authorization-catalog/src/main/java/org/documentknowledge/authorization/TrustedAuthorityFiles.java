package org.documentknowledge.authorization;

import com.sun.security.auth.module.UnixSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.Set;

/** Bounded reads protected against replacement through ANY untrusted ancestor. */
public final class TrustedAuthorityFiles {
    /** Java constructor seam for deterministic tests; never selected through runtime configuration. */
    public record Platform(String osName, boolean supportsAclView, long currentUid) { }
    private final Platform platform;
    public TrustedAuthorityFiles(Platform platform) { this.platform = java.util.Objects.requireNonNull(platform); }
    public static TrustedAuthorityFiles system() {
        return new TrustedAuthorityFiles(new Platform(System.getProperty("os.name", ""),
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("acl"), new UnixSystem().getUid()));
    }
    private static final Set<AclEntryPermission> MUTATION = Set.of(AclEntryPermission.WRITE_DATA,
        AclEntryPermission.APPEND_DATA, AclEntryPermission.WRITE_NAMED_ATTRS,
        AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.DELETE,
        AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
    public static byte[] read(Path path, int maximum, boolean privateRead) throws IOException {
        return system().readBounded(path, maximum, privateRead);
    }
    public byte[] readBounded(Path path, int maximum, boolean privateRead) throws IOException {
        if (maximum < 1) throw new IOException("Invalid authority file bound");
        validate(path, privateRead);
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.min(maximum, 4096));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (channel.read(buffer) != -1) {
                buffer.flip();
                if (output.size() + buffer.remaining() > maximum) throw new IOException("Authority file exceeds bound");
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            bytes = output.toByteArray();
        }
        validate(path, privateRead);
        return bytes;
    }
    private void validate(Path path, boolean privateRead) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize()) || path.getParent() == null) unsafe();
        Path current = path.getRoot();
        check(current, false, false);
        for (Path name : path) {
            current = current.resolve(name);
            boolean file = current.equals(path);
            check(current, file, file && privateRead);
        }
    }
    private void check(Path path, boolean file, boolean privateRead) throws IOException {
        var attributes = Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
        long uid = ((Number) attributes.get("uid")).longValue();
        int mode = ((Number) attributes.get("mode")).intValue();
        int expectedType = file ? 0100000 : 0040000;
        if ((mode & 0170000) != expectedType || (uid != 0 && uid != platform.currentUid())
            || (mode & 01022) != 0 || (privateRead && (mode & 0077) != 0)) unsafe();
        extendedPermissions(path);
    }
    private void extendedPermissions(Path path) throws IOException {
        if ("linux".equalsIgnoreCase(platform.osName())) return;
        if (!platform.supportsAclView()) unsafe();
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) unsafe();
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        var root = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("root");
        validateAcl(view.getAcl(), owner, root);
    }
    static void validateAcl(java.util.List<java.nio.file.attribute.AclEntry> entries,
            java.nio.file.attribute.UserPrincipal owner, java.nio.file.attribute.UserPrincipal root) throws IOException {
        for (var entry : entries) {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                && !entry.principal().equals(root) && entry.permissions().stream().anyMatch(MUTATION::contains)) unsafe();
        }
    }
    private static void unsafe() throws IOException { throw new IOException("Unsafe authority file path"); }
}
