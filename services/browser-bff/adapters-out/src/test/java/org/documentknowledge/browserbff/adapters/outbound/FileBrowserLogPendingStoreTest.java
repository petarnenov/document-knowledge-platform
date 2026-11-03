package org.documentknowledge.browserbff.adapters.outbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileBrowserLogPendingStoreTest {
    @TempDir Path temporary;
    @BeforeEach void canonicalTemporaryDirectory() throws IOException {
        temporary = temporary.toRealPath();
        Files.createDirectory(directory(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    static final String BINDING = "a".repeat(64);
    Path directory() { return temporary.resolve("queue"); }
    BrowserLogPendingStore.Entry entry(int count) {
        return new BrowserLogPendingStore.Entry(UUID.randomUUID(), "private-original-scope".getBytes(), "sanitized-original-batch".getBytes(), count);
    }
    void privateWrite(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    @Test void exactOriginalBytesAndIdsSurviveRestartAndRemovalIsDurable() throws Exception {
        var first = entry(2); var second = entry(1);
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) {
            assertTrue(store.load().isEmpty()); store.replace(List.of(first, second));
            assertEquals(2, store.load().size());
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory()));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory().resolve("pending.bin")));
        }
        try (var recovered = new FileBrowserLogPendingStore(directory(), BINDING)) {
            var records = recovered.load(); assertEquals(first.id(), records.getFirst().id());
            assertArrayEquals(first.authority(), records.getFirst().authority()); assertArrayEquals(first.batch(), records.getFirst().batch());
            assertEquals(first.eventCount(), records.getFirst().eventCount()); recovered.replace(List.of(second));
        }
        try (var recovered = new FileBrowserLogPendingStore(directory(), BINDING)) {
            assertEquals(second.id(), recovered.load().getFirst().id()); recovered.replace(List.of());
        }
        try (var recovered = new FileBrowserLogPendingStore(directory(), BINDING)) { assertTrue(recovered.load().isEmpty()); }
    }
    @Test void opaqueEntryDefensivelyCopiesAndNeverPrintsAuthority() {
        byte[] authority = "secret".getBytes(), batch = "original".getBytes(); var value = new BrowserLogPendingStore.Entry(UUID.randomUUID(), authority, batch, 1);
        authority[0] = 0; batch[0] = 0; value.authority()[0] = 0; value.batch()[0] = 0;
        assertEquals("secret", new String(value.authority())); assertEquals("original", new String(value.batch()));
        assertFalse(value.toString().contains("secret"));
        for (int count : List.of(0, 33)) assertThrows(IllegalArgumentException.class, () -> entry(count));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogPendingStore.Entry(new UUID(0, 0), authority, batch, 1));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogPendingStore.Entry(UUID.randomUUID(), new byte[8193], batch, 1));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogPendingStore.Entry(UUID.randomUUID(), authority, new byte[32769], 1));
    }
    @Test void originalBindingAndLifetimeOwnerCannotBeReplaced() throws Exception {
        try (var owner = new FileBrowserLogPendingStore(directory(), BINDING)) {
            owner.load(); owner.replace(List.of(entry(1)));
            assertThrows(IOException.class, () -> new FileBrowserLogPendingStore(directory(), BINDING));
            owner.replace(List.of(entry(1)));
        }
        byte[] before = Files.readAllBytes(directory().resolve("pending.bin"));
        try (var wrong = new FileBrowserLogPendingStore(directory(), "b".repeat(64))) { assertThrows(IOException.class, wrong::load); }
        assertArrayEquals(before, Files.readAllBytes(directory().resolve("pending.bin")));
        try (var right = new FileBrowserLogPendingStore(directory(), BINDING)) { assertEquals(1, right.load().size()); }
    }
    @Test void admissionBoundsAndDuplicateIdsPreservePreviousSnapshot() throws Exception {
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) {
            assertThrows(IOException.class, () -> store.replace(List.of(entry(1)))); store.load(); var original = entry(1); store.replace(List.of(original));
            assertThrows(IOException.class, () -> store.replace(List.of(original, original)));
            var excessiveEvents = new ArrayList<BrowserLogPendingStore.Entry>(); for (int i = 0; i < 65; i++) excessiveEvents.add(entry(32));
            assertThrows(IOException.class, () -> store.replace(excessiveEvents));
            var excessiveBytes = new ArrayList<BrowserLogPendingStore.Entry>(); for (int i = 0; i < 257; i++) excessiveBytes.add(new BrowserLogPendingStore.Entry(UUID.randomUUID(), new byte[]{1}, new byte[32768], 1));
            assertThrows(IOException.class, () -> store.replace(excessiveBytes)); assertEquals(original.id(), store.load().getFirst().id());
            store.close(); assertThrows(IOException.class, store::load); assertThrows(IOException.class, () -> store.replace(List.of()));
        }
    }
    @Test void corruptionTruncationPermissionAndUnknownFileFailClosedWithoutDeletingEvidence() throws Exception {
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) { store.load(); store.replace(List.of(entry(1))); }
        Path pending = directory().resolve("pending.bin"); byte[] original = Files.readAllBytes(pending);
        for (byte[] invalid : List.of(Arrays.copyOf(original, original.length - 1), new byte[]{1, 2}, Arrays.copyOf(original, original.length + 1))) {
            privateWrite(pending, invalid);
            try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) { assertThrows(IOException.class, store::load); }
            assertArrayEquals(invalid, Files.readAllBytes(pending));
        }
        byte[] changed = original.clone(); changed[changed.length - 1] ^= 1; privateWrite(pending, changed);
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) { assertThrows(IOException.class, store::load); }
        privateWrite(pending, original); Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-r--r--"));
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) { assertThrows(IOException.class, store::load); }
        privateWrite(pending, original); privateWrite(directory().resolve("foreign"), new byte[]{1});
        assertThrows(IOException.class, () -> new FileBrowserLogPendingStore(directory(), BINDING)); assertArrayEquals(original, Files.readAllBytes(pending));
    }
    @Test void canonicalPathsAndStagingOwnershipAreRequired() throws Exception {
        assertThrows(IOException.class, () -> new FileBrowserLogPendingStore(Path.of("relative"), BINDING));
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, directory()); assertThrows(IOException.class, () -> new FileBrowserLogPendingStore(alias, BINDING));
        privateWrite(directory().resolve("pending.next"), new byte[]{1}); assertThrows(IOException.class, () -> new FileBrowserLogPendingStore(directory(), BINDING));
        Files.delete(directory().resolve("pending.next"));
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) { store.load(); store.replace(List.of(entry(1))); }
        privateWrite(directory().resolve("pending.next"), new byte[]{1}); privateWrite(directory().resolve("health.json"), "{}".getBytes());
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) { assertEquals(1, store.load().size()); store.replace(List.of()); assertFalse(Files.exists(directory().resolve("pending.next"))); }
    }
    @Test void exposedDirectoryAndLinkedPendingFileCannotBeAdoptedOrOverwritten() throws Exception {
        Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> new FileBrowserLogPendingStore(directory(), BINDING));
        Files.setPosixFilePermissions(directory(), PosixFilePermissions.fromString("rwx------"));
        Path original = temporary.resolve("original-private-evidence"); privateWrite(original, new byte[]{1, 2, 3});
        Files.createSymbolicLink(directory().resolve("pending.bin"), original);
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) { assertThrows(IOException.class, store::load); }
        assertTrue(Files.isSymbolicLink(directory().resolve("pending.bin"))); assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(original));
    }
    @Test void missingConfiguredStorageIsNeverSilentlyRecreated() throws Exception {
        Files.delete(directory());
        assertThrows(IOException.class, () -> new FileBrowserLogPendingStore(directory(), BINDING));
        assertFalse(Files.exists(directory(), LinkOption.NOFOLLOW_LINKS));
    }
    @Test void privateBoundedHealthSnapshotIsAtomicContainsOnlyCountersAndPreservesQueuedBytes() throws Exception {
        var health = new BrowserLogPendingStore.Health(2, 1234, 1, 7, 3, "TRANSIENT_DELIVERY_UNCERTAIN", false, false);
        Path file = directory().resolve("health.json");
        try (var store = new FileBrowserLogPendingStore(directory(), BINDING)) {
            assertThrows(IOException.class, () -> store.health(health)); store.load(); store.replace(List.of(entry(2)));
            byte[] before = Files.readAllBytes(directory().resolve("pending.bin")); store.health(health);
            var json = BrowserLogPendingFixtures.JSON.readValue(Files.readAllBytes(file), Map.class);
            assertEquals(Set.of("schemaVersion", "updatedAt", "pendingEvents", "pendingBytes", "claimedEvents", "committedEvents",
                "discardedEvents", "lastReason", "storageUncertain", "closed"), json.keySet());
            assertEquals(1, json.get("schemaVersion")); assertEquals(2, json.get("pendingEvents")); assertEquals(1, json.get("claimedEvents"));
            assertEquals(7, json.get("committedEvents")); assertEquals(3, json.get("discardedEvents"));
            assertEquals(health.lastReason(), json.get("lastReason")); java.time.Instant.parse((String)json.get("updatedAt"));
            assertTrue(Files.size(file) <= 4096); assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertFalse(Files.readString(file).contains("private-original-scope"));
            privateWrite(directory().resolve("health.next"), new byte[]{1});
            store.health(new BrowserLogPendingStore.Health(0, 0, 0, 9, 3, "NONE", true, true));
            assertFalse(Files.exists(directory().resolve("health.next"))); assertArrayEquals(before, Files.readAllBytes(directory().resolve("pending.bin")));
            var updated = BrowserLogPendingFixtures.JSON.readValue(Files.readAllBytes(file), Map.class);
            assertEquals(9, updated.get("committedEvents")); assertEquals(true, updated.get("closed")); assertEquals(true, updated.get("storageUncertain"));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--")); byte[] evidence = Files.readAllBytes(file);
            assertThrows(IOException.class, () -> store.health(health)); assertArrayEquals(evidence, Files.readAllBytes(file));
        }
    }
    @Test void healthRejectsInvalidCountsAndArbitraryOrSecretTextBeforeFilesystemWrites() {
        for (int[] counts : List.of(new int[]{-1, 0, 0}, new int[]{2049, 0, 0}, new int[]{1, -1, 0},
                new int[]{1, BrowserLogPendingStore.MAX_BYTES + 1, 0}, new int[]{1, 0, -1}, new int[]{1, 0, 2}))
            assertThrows(IllegalArgumentException.class, () -> new BrowserLogPendingStore.Health(counts[0], counts[1], counts[2], 0, 0, "NONE", false, false));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogPendingStore.Health(0, 0, 0, -1, 0, "NONE", false, false));
        assertThrows(IllegalArgumentException.class, () -> new BrowserLogPendingStore.Health(0, 0, 0, 0, -1, "NONE", false, false));
        for (String reason : Arrays.asList(null, "", "secret arbitrary text", "A".repeat(49)))
            assertThrows(IllegalArgumentException.class, () -> new BrowserLogPendingStore.Health(0, 0, 0, 0, 0, reason, false, false));
    }
}
