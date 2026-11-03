package org.documentknowledge.browserbff.adapters.outbound;

import com.sun.security.auth.module.UnixSystem;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

/** One owner-locked atomic snapshot; authority records and sanitized payload bytes remain distinct. */
public final class FileBrowserLogPendingStore implements BrowserLogPendingStore {
    private static final int SNAPSHOT_BYTES = 25 * 1024 * 1024;
    private static final long DISK_BYTES = 64L * 1024 * 1024;
    private static final long MAGIC = 0x444b504246465131L;
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<Path> OWNERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path directory, pending, staging;
    private final String binding;
    private final long uid = new UnixSystem().getUid();
    private FileChannel channel;
    private FileLock lock;
    private boolean owned, loaded, closed;

    public FileBrowserLogPendingStore(Path directory, String binding) throws IOException {
        this.directory = Objects.requireNonNull(directory); this.binding = Objects.requireNonNull(binding);
        pending = directory.resolve("pending.bin"); staging = directory.resolve("pending.next");
        try {
            require(directory.isAbsolute() && binding.matches("[0-9a-f]{64}"));
            checkDirectory(); require(OWNERS.add(directory)); owned = true;
            Path owner = directory.resolve("queue.lock");
            if (Files.exists(owner, LinkOption.NOFOLLOW_LINKS)) checkFile(owner, 0);
            channel = FileChannel.open(owner, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(FILE_MODE));
            checkFile(owner, 0); lock = channel.tryLock(); require(lock != null);
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                checkFile(staging, SNAPSHOT_BYTES); require(Files.exists(pending, LinkOption.NOFOLLOW_LINKS));
            }
        } catch (Exception invalid) { close(); throw failure(); }
    }
    private void checkDirectory() throws IOException {
        require(directory.toRealPath().equals(directory));
        var info = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(info.isDirectory() && info.permissions().equals(DIRECTORY_MODE)
            && ((Number) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() == uid);
        try (var files = Files.newDirectoryStream(directory)) {
            for (var file : files) require(Set.of("pending.bin", "pending.next", "queue.lock", "health.json", "health.next")
                .contains(file.getFileName().toString()));
        }
        for (String health : List.of("health.json", "health.next")) {
            Path file = directory.resolve(health);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) checkFile(file, 4096);
        }
    }
    private PosixFileAttributes checkFile(Path path, long maximum) throws IOException {
        var info = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(info.isRegularFile() && info.permissions().equals(FILE_MODE) && info.size() <= maximum
            && ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() == uid);
        return info;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void validate(List<Entry> entries) throws IOException {
        require(entries != null && entries.size() <= MAX_EVENTS);
        long bytes = 0, events = 0; var ids = new HashSet<UUID>();
        for (var entry : entries) {
            require(entry != null && ids.add(entry.id())); bytes += entry.batch().length; events += entry.eventCount();
            require(bytes <= MAX_BYTES && events <= MAX_EVENTS);
        }
    }
    @Override public synchronized List<Entry> load() throws IOException {
        try {
            require(!closed); checkDirectory();
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) { loaded = true; return List.of(); }
            var before = checkFile(pending, SNAPSHOT_BYTES); byte[] bytes;
            try (var stream = Files.newInputStream(pending, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(SNAPSHOT_BYTES + 1);
            }
            var after = checkFile(pending, SNAPSHOT_BYTES);
            require(bytes.length == before.size() && bytes.length <= SNAPSHOT_BYTES && before.size() == after.size()
                && Objects.equals(before.fileKey(), after.fileKey()) && before.lastModifiedTime().equals(after.lastModifiedTime()));
            var entries = new ArrayList<Entry>(); var referenced = new HashSet<String>();
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                require(input.readLong() == MAGIC && input.readUTF().equals(binding));
                int authorities = input.readInt(); require(authorities >= 0 && authorities <= MAX_EVENTS);
                var checkpoints = new HashMap<String, byte[]>();
                for (int i = 0; i < authorities; i++) {
                    String digest = input.readUTF(); int size = input.readInt(); require(size > 0 && size <= 8192);
                    byte[] checkpoint = input.readNBytes(size);
                    require(checkpoint.length == size && sha(checkpoint).equals(digest) && checkpoints.putIfAbsent(digest, checkpoint) == null);
                }
                int count = input.readInt(); require(count >= 0 && count <= MAX_EVENTS); long total = 0, events = 0;
                for (int i = 0; i < count; i++) {
                    UUID id = new UUID(input.readLong(), input.readLong()); String authority = input.readUTF(), digest = input.readUTF();
                    int eventCount = input.readInt(), size = input.readInt();
                    require(size > 0 && size <= 32768 && (total += size) <= MAX_BYTES
                        && eventCount > 0 && eventCount <= 32 && (events += eventCount) <= MAX_EVENTS && checkpoints.containsKey(authority));
                    byte[] batch = input.readNBytes(size); require(batch.length == size && sha(batch).equals(digest));
                    entries.add(new Entry(id, checkpoints.get(authority), batch, eventCount)); referenced.add(authority);
                }
                require(input.available() == 0 && referenced.equals(checkpoints.keySet()));
            }
            validate(entries); loaded = true; return List.copyOf(entries);
        } catch (Exception invalid) { throw failure(); }
    }
    @Override public synchronized void replace(List<Entry> entries) throws IOException {
        try {
            require(!closed && loaded); checkDirectory(); validate(entries);
            var authorities = new LinkedHashMap<String, byte[]>();
            for (var entry : entries) authorities.putIfAbsent(sha(entry.authority()), entry.authority());
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeLong(MAGIC); output.writeUTF(binding); output.writeInt(authorities.size());
                for (var row : authorities.entrySet()) {
                    output.writeUTF(row.getKey()); output.writeInt(row.getValue().length); output.write(row.getValue());
                }
                output.writeInt(entries.size());
                for (var entry : entries) {
                    byte[] batch = entry.batch(); output.writeLong(entry.id().getMostSignificantBits()); output.writeLong(entry.id().getLeastSignificantBits());
                    output.writeUTF(sha(entry.authority())); output.writeUTF(sha(batch)); output.writeInt(entry.eventCount()); output.writeInt(batch.length); output.write(batch);
                }
            }
            byte[] snapshot = bytes.toByteArray(); require(snapshot.length <= SNAPSHOT_BYTES);
            long retained = Files.exists(pending, LinkOption.NOFOLLOW_LINKS) ? checkFile(pending, SNAPSHOT_BYTES).size() : 0;
            for (String health : List.of("health.json", "health.next")) {
                Path file = directory.resolve(health); if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) retained += checkFile(file, 4096).size();
            }
            require(retained + snapshot.length <= DISK_BYTES);
            writeSnapshot(staging, pending, snapshot, SNAPSHOT_BYTES);
        } catch (Exception unavailable) { throw failure(); }
    }
    @Override public synchronized void health(Health health) throws IOException {
        try {
            require(!closed && loaded); checkDirectory(); Objects.requireNonNull(health);
            String json = "{\"schemaVersion\":1,\"updatedAt\":\"" + java.time.Instant.now() + "\",\"pendingEvents\":" + health.pendingEvents()
                + ",\"pendingBytes\":" + health.pendingBytes() + ",\"claimedEvents\":" + health.claimedEvents()
                + ",\"committedEvents\":" + health.committedEvents() + ",\"discardedEvents\":" + health.discardedEvents()
                + ",\"lastReason\":\"" + health.lastReason() + "\",\"storageUncertain\":" + health.storageUncertain()
                + ",\"closed\":" + health.closed() + "}";
            byte[] snapshot = json.getBytes(java.nio.charset.StandardCharsets.UTF_8); require(snapshot.length <= 4096);
            long retained = snapshot.length;
            for (String name : List.of("pending.bin", "pending.next", "health.json", "health.next")) {
                Path file = directory.resolve(name);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) retained += checkFile(file, name.startsWith("pending") ? SNAPSHOT_BYTES : 4096).size();
            }
            require(retained <= DISK_BYTES);
            writeSnapshot(directory.resolve("health.next"), directory.resolve("health.json"), snapshot, 4096);
        } catch (Exception unavailable) { throw failure(); }
    }
    private void writeSnapshot(Path next, Path stable, byte[] snapshot, int maximum) throws IOException {
        if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) { checkFile(next, maximum); Files.delete(next); }
        try (var stream = FileChannel.open(next, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(FILE_MODE))) {
            var data = ByteBuffer.wrap(snapshot); while (data.hasRemaining()) stream.write(data); stream.force(true);
        }
        checkFile(next, maximum); Files.move(next, stable, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
    }
    @Override public synchronized void close() {
        if (!closed) {
            closed = true;
            try { if (lock != null) lock.release(); } catch (IOException ignored) { /* Preserve files; no recursive logging. */ }
            try { if (channel != null) channel.close(); } catch (IOException ignored) { /* Preserve files. */ }
            if (owned) { OWNERS.remove(directory); owned = false; }
        }
    }
    private static void require(boolean condition) throws IOException { if (!condition) throw failure(); }
    private static IOException failure() { return new IOException("INVALID_BROWSER_LOG_SPOOL"); }
}
