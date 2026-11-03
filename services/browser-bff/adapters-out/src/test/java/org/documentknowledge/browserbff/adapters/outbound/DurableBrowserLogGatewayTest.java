package org.documentknowledge.browserbff.adapters.outbound;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.documentknowledge.browserbff.adapters.outbound.BrowserLogPendingFixtures.*;

class DurableBrowserLogGatewayTest {
    @TempDir Path temporary;
    Path directory;
    @BeforeEach void prepareOwnedDirectory() throws Exception {
        directory = temporary.toRealPath().resolve("queue");
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    FileBrowserLogPendingStore file() throws IOException { return new FileBrowserLogPendingStore(directory, "a".repeat(64)); }
    static BffFailure unavailable(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(BffFailure.class, action); assertEquals(503, failure.status());
        assertEquals("LOG_STORE_UNAVAILABLE", failure.getMessage()); assertNull(failure.getCause()); return failure;
    }
    @Test void durableOriginalBytesPrecedeForwardAndOnlyExactCommitRemovesThem() throws Exception {
        var transport = new Transport(); var store = new MemoryStore();
        try (var gateway = new DurableBrowserLogGateway(transport, store)) {
            var grant = grant(); var scope = gateway.delegate(grant, BrowserLogDeadline.start()); UUID id = UUID.randomUUID(); byte[] batch = batch(grant, id);
            var deadline = BrowserLogDeadline.start();
            transport.before = () -> {
                assertEquals(1, store.entries.size()); assertArrayEquals(batch, store.entries.getFirst().batch());
                assertEquals(1, gateway.status().claimedEvents());
                assertEquals(1, store.health.claimedEvents()); assertEquals(1, store.health.pendingEvents());
            };
            BrowserLogPendingJson.committed(gateway.ingest(scope, batch, deadline), List.of(id));
            assertSame(deadline, transport.deadline); assertTrue(store.entries.isEmpty()); assertEquals(0, gateway.status().pendingEvents());
            assertEquals(1, gateway.status().committedEvents()); assertEquals(1, transport.delegations);
            assertEquals(1, store.health.committedEvents()); assertEquals(0, store.health.pendingEvents());
            assertArrayEquals(new byte[]{1}, gateway.receipts(scope, new byte[]{2}, BrowserLogDeadline.start()));
            assertEquals(DurableBrowserLogGateway.Replay.IDLE, gateway.replayOne(BrowserLogDeadline.start()));
        }
        assertTrue(store.closed);
    }
    @Test void unknownCommitSurvivesRealFileRestartWithSameScopeAndIdsWithoutRedelegating() throws Exception {
        var transport = new Transport(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        UUID id = UUID.randomUUID(); byte[] bytes = batch(grant, id), authority = transport.checkpoint(scope);
        transport.mode = Mode.AFTER_COMMIT;
        try (var original = new DurableBrowserLogGateway(transport, file())) {
            unavailable(() -> original.ingest(scope, bytes, BrowserLogDeadline.start()));
            assertEquals(1, original.status().pendingEvents()); assertEquals(0, original.status().committedEvents());
            assertEquals(DurableBrowserLogGateway.Reason.TRANSIENT_DELIVERY_UNCERTAIN, original.status().lastReason());
            unavailable(() -> original.ingest(scope, bytes, BrowserLogDeadline.start()));
            assertEquals(1, original.status().pendingEvents());
        }
        var reopenedTransport = new Transport(); reopenedTransport.committed.addAll(transport.committed);
        try (var recovered = new DurableBrowserLogGateway(reopenedTransport, file())) {
            assertEquals(1, recovered.status().pendingEvents()); assertEquals(DurableBrowserLogGateway.Replay.COMMITTED, recovered.replayOne(BrowserLogDeadline.start()));
            assertEquals(0, reopenedTransport.delegations); assertArrayEquals(bytes, reopenedTransport.lastBatch);
            assertArrayEquals(authority, reopenedTransport.lastAuthority); assertEquals(Set.of(id), reopenedTransport.committed);
            assertEquals(0, recovered.status().pendingEvents());
        }
        try (var store = file()) { assertTrue(store.load().isEmpty()); }
    }
    @Test void invalidCommitReceiptRetainsPendingAndCannotReturnSuccess() throws Exception {
        var transport = new Transport(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        transport.reply = receipts(List.of(UUID.randomUUID()));
        try (var gateway = new DurableBrowserLogGateway(transport, file())) {
            unavailable(() -> gateway.ingest(scope, batch(grant, UUID.randomUUID()), BrowserLogDeadline.start()));
            assertEquals(1, gateway.status().pendingEvents()); assertEquals(0, gateway.status().committedEvents());
            assertEquals(DurableBrowserLogGateway.Reason.INVALID_COMMIT_RECEIPT, gateway.status().lastReason());
            transport.reply = null; assertEquals(DurableBrowserLogGateway.Replay.COMMITTED, gateway.replayOne(BrowserLogDeadline.start()));
        }
    }
    @Test void storageFailureBeforeForwardStopsFurtherAdmissionAndDoesNotLieAboutPersistence() throws Exception {
        var store = new MemoryStore(); var transport = new Transport(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        try (var gateway = new DurableBrowserLogGateway(transport, store)) {
            store.failWrite = 1;
            unavailable(() -> gateway.ingest(scope, batch(grant, UUID.randomUUID()), BrowserLogDeadline.start()));
            assertEquals(0, transport.forwards.get()); assertTrue(gateway.status().storageUncertain());
            assertEquals(DurableBrowserLogGateway.Reason.STORAGE_UNCERTAIN, gateway.status().lastReason());
            store.failWrite = 0; unavailable(() -> gateway.ingest(scope, batch(grant, UUID.randomUUID()), BrowserLogDeadline.start()));
            unavailable(() -> gateway.replayOne(BrowserLogDeadline.start())); assertEquals(1, store.writes);
        }
    }
    @Test void confirmedCommitThenFailedRemovalRemainsPendingAndReplaysIdempotentlyAfterReopen() throws Exception {
        var store = new MemoryStore(); var transport = new Transport(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        UUID id = UUID.randomUUID();
        try (var gateway = new DurableBrowserLogGateway(transport, store)) {
            store.failWrite = 2; unavailable(() -> gateway.ingest(scope, batch(grant, id), BrowserLogDeadline.start()));
            assertEquals(1, gateway.status().pendingEvents()); assertEquals(1, gateway.status().committedEvents());
            assertEquals(0, gateway.status().claimedEvents()); assertTrue(gateway.status().storageUncertain());
        }
        var reopened = new MemoryStore(); reopened.entries = store.entries;
        try (var gateway = new DurableBrowserLogGateway(transport, reopened)) {
            assertEquals(DurableBrowserLogGateway.Replay.COMMITTED, gateway.replayOne(BrowserLogDeadline.start()));
            assertEquals(Set.of(id), transport.committed); assertEquals(2, transport.forwards.get());
        }
    }
    @Test void terminalAndExpiredScopesAreDiscardedLocallyWithoutInventingUpstreamAbsence() throws Exception {
        var transport = new Transport(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        try (var gateway = new DurableBrowserLogGateway(transport, file())) {
            transport.mode = Mode.TERMINAL;
            unavailable(() -> gateway.ingest(scope, batch(grant, UUID.randomUUID()), BrowserLogDeadline.start()));
            assertEquals(1, gateway.status().discardedEvents()); assertEquals(0, gateway.status().committedEvents());
            assertEquals(DurableBrowserLogGateway.Reason.TERMINAL_DELIVERY_UNCERTAIN, gateway.status().lastReason());
            transport.mode = Mode.TRANSIENT;
            unavailable(() -> gateway.ingest(scope, batch(grant, UUID.randomUUID()), BrowserLogDeadline.start()));
            transport.expired = true; assertEquals(DurableBrowserLogGateway.Replay.DISCARDED, gateway.replayOne(BrowserLogDeadline.start()));
            assertEquals(2, transport.forwards.get()); assertEquals(2, gateway.status().discardedEvents());
            assertEquals(DurableBrowserLogGateway.Reason.EXPIRED_DELIVERY_UNCERTAIN, gateway.status().lastReason());
        }
    }
    @Test void liveClaimPreventsDuplicateForwardAndCloseRetainsFileLockUntilActualWriterExits() throws Exception {
        var transport = new Transport(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        byte[] bytes = batch(grant, UUID.randomUUID()); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        transport.before = () -> { entered.countDown(); try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(); } };
        var gateway = new DurableBrowserLogGateway(transport, file()); var worker = Executors.newSingleThreadExecutor();
        try {
            var response = worker.submit(() -> gateway.ingest(scope, bytes, BrowserLogDeadline.start()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var duplicate = assertThrows(BffFailure.class, () -> gateway.ingest(scope, bytes, BrowserLogDeadline.start())); assertEquals(429, duplicate.status());
            assertEquals(DurableBrowserLogGateway.Replay.IDLE, gateway.replayOne(BrowserLogDeadline.start())); assertEquals(1, gateway.status().claimedEvents());
            gateway.close(); assertThrows(IOException.class, this::file);
            unavailable(() -> gateway.delegate(grant, BrowserLogDeadline.start())); unavailable(() -> gateway.receipts(scope, new byte[]{1}, BrowserLogDeadline.start()));
            release.countDown(); assertNotNull(response.get(3, TimeUnit.SECONDS)); assertEquals(1, transport.forwards.get());
            try (var reopened = file()) { assertTrue(reopened.load().isEmpty()); }
        } finally { release.countDown(); gateway.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    @Test void invalidRecoveredRecordFailsClosedPreservingStoreEvidence() throws Exception {
        var transport = new Transport(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        byte[] batch = batch(grant, UUID.randomUUID()), authority = transport.checkpoint(scope);
        var entry = new BrowserLogPendingStore.Entry(UUID.randomUUID(), authority, batch, 1);
        for (var entries : List.of(List.of(new BrowserLogPendingStore.Entry(UUID.randomUUID(), authority, batch, 2)),
                List.of(entry, entry), List.of(entry, new BrowserLogPendingStore.Entry(UUID.randomUUID(), authority, batch, 1)))) {
            var store = new MemoryStore(); store.entries = entries;
            var failure = assertThrows(IOException.class, () -> new DurableBrowserLogGateway(transport, store));
            assertEquals("INVALID_BROWSER_LOG_PENDING", failure.getMessage()); assertTrue(store.closed); assertEquals(entries, store.entries);
        }
    }
    @Test void completePendingEventAndByteBoundsRefuseNewWorkWithoutEvictingAcceptedRecords() throws Exception {
        for (boolean bytesBound : List.of(false, true)) {
            var transport = new Transport(); transport.mode = Mode.TRANSIENT; transport.opaqueBoundTest = true;
            var store = new MemoryStore(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
            try (var gateway = new DurableBrowserLogGateway(transport, store)) {
                int limit = bytesBound ? 256 : 64;
                for (int i = 0; i < limit; i++) {
                    byte[] bytes = new byte[bytesBound ? 32768 : 2]; bytes[0] = (byte)i; bytes[1] = (byte)(i >>> 8);
                    transport.boundEventCount = bytesBound ? 1 : 32;
                    unavailable(() -> gateway.ingest(scope, bytes, BrowserLogDeadline.start()));
                }
                var before = gateway.status(); byte[] excessive = new byte[bytesBound ? 32768 : 2]; excessive[0] = (byte)limit; excessive[1] = (byte)(limit >>> 8);
                var failure = assertThrows(BffFailure.class, () -> gateway.ingest(scope, excessive, BrowserLogDeadline.start()));
                assertEquals(429, failure.status()); assertEquals(before.pendingEvents(), gateway.status().pendingEvents());
                assertEquals(before.pendingBytes(), gateway.status().pendingBytes()); assertEquals(limit, store.entries.size());
            }
        }
    }
    @Test void healthFailureIsVisibleStopsForwardingAndNeverLeaksAnOwnedClaim() throws Exception {
        var transport = new Transport(); var store = new MemoryStore(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        try (var gateway = new DurableBrowserLogGateway(transport, store)) {
            store.failHealth = true;
            unavailable(() -> gateway.ingest(scope, batch(grant, UUID.randomUUID()), BrowserLogDeadline.start()));
            assertTrue(gateway.status().storageUncertain()); assertEquals(1, gateway.status().pendingEvents());
            assertEquals(0, gateway.status().claimedEvents()); assertEquals(0, transport.forwards.get());
            unavailable(() -> gateway.replayOne(BrowserLogDeadline.start()));
            store.failHealth = false;
        }
        assertTrue(store.closed); assertTrue(store.health.closed()); assertTrue(store.health.storageUncertain());
        assertEquals("STORAGE_UNCERTAIN", store.health.lastReason());
        var failedStartup = new MemoryStore(); failedStartup.failHealth = true;
        assertThrows(IOException.class, () -> new DurableBrowserLogGateway(transport, failedStartup)); assertTrue(failedStartup.closed);
    }
    @Test void closeAfterCommittedRemovalKeepsLifetimeLockThroughTheFinalHealthWriter() throws Exception {
        var transport = new Transport(); var grant = grant(); var scope = transport.delegate(grant, BrowserLogDeadline.start());
        var completed = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var gateway = new DurableBrowserLogGateway(transport, file(), () -> {
            completed.countDown(); try { assertTrue(finish.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
        });
        var worker = Executors.newSingleThreadExecutor();
        try {
            var response = worker.submit(() -> gateway.ingest(scope, batch(grant, UUID.randomUUID()), BrowserLogDeadline.start()));
            assertTrue(completed.await(2, TimeUnit.SECONDS)); assertEquals(0, gateway.status().pendingEvents());
            assertEquals(1, gateway.status().committedEvents()); gateway.close();
            assertThrows(IOException.class, () -> { try (var unexpectedContender = file()) { /* Close an unexpected owner even on regression failure. */ } });
            finish.countDown(); assertNotNull(response.get(3, TimeUnit.SECONDS)); assertFalse(gateway.status().storageUncertain());
            try (var recovered = file()) {
                assertTrue(recovered.load().isEmpty());
                var health = JSON.readValue(Files.readAllBytes(directory.resolve("health.json")), Map.class);
                assertEquals(true, health.get("closed")); assertEquals(false, health.get("storageUncertain"));
                assertEquals(1, health.get("committedEvents"));
            }
        } finally { finish.countDown(); gateway.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    enum Mode { NORMAL, TRANSIENT, AFTER_COMMIT, TERMINAL }
    static final class MemoryStore implements BrowserLogPendingStore {
        List<Entry> entries = List.of(); int writes, failWrite; boolean closed;
        boolean failHealth; Health health;
        @Override public List<Entry> load() { return entries; }
        @Override public void replace(List<Entry> next) throws IOException {
            if (++writes == failWrite) throw new IOException("private-evidence-not-exposed"); entries = List.copyOf(next);
        }
        @Override public void health(Health value) throws IOException {
            if (failHealth) throw new IOException("private-health-cause"); health = value;
        }
        @Override public void close() { closed = true; }
    }
    static final class Transport implements BrowserLogRecoveryGateway {
        static final BrowserLogScopeCheckpoint.Binding BINDING = new BrowserLogScopeCheckpoint.Binding(
            URI.create("https://localhost/api/v1/dev-logs"), UUID.randomUUID(), "a".repeat(64), Map.of(ARTIFACT.buildId(), ARTIFACT));
        record Handle(BrowserLogScopeCheckpoint.Recovered original) implements Scope { @Override public String toString() { return "REDACTED"; } }
        final AtomicInteger forwards = new AtomicInteger(); final Set<UUID> committed = new HashSet<>();
        int delegations, boundEventCount = 1; boolean expired, opaqueBoundTest; Mode mode = Mode.NORMAL;
        byte[] reply, lastBatch, lastAuthority; Runnable before = () -> { }; BrowserLogDeadline deadline;
        @Override public Scope delegate(Grant grant, BrowserLogDeadline deadline) {
            delegations++; return new Handle(new BrowserLogScopeCheckpoint.Recovered(grant, UUID.randomUUID(), "p".repeat(64), grant.captureUntil().plusSeconds(60)));
        }
        @Override public byte[] checkpoint(Scope scope) { return BrowserLogScopeCheckpoint.encode(BINDING, ((Handle)scope).original()); }
        @Override public Scope restore(byte[] bytes) { return new Handle(BrowserLogScopeCheckpoint.decode(BINDING, bytes)); }
        @Override public boolean expired(Scope scope) { return expired; }
        @Override public List<UUID> eventIds(Scope scope, byte[] bytes) {
            if (opaqueBoundTest) { var ids = new ArrayList<UUID>(); for (int i = 0; i < boundEventCount; i++) ids.add(UUID.randomUUID()); return ids; }
            return BrowserLogPendingJson.events(bytes, ((Handle)scope).original().grant());
        }
        @Override public byte[] forward(Scope scope, byte[] bytes, BrowserLogDeadline deadline) {
            this.deadline = deadline; before.run(); forwards.incrementAndGet(); lastBatch = bytes.clone(); lastAuthority = checkpoint(scope);
            if (mode == Mode.TERMINAL) throw new DeliveryFailure(true);
            if (mode == Mode.TRANSIENT) throw new DeliveryFailure(false);
            var ids = eventIds(scope, bytes); committed.addAll(ids);
            if (mode == Mode.AFTER_COMMIT) throw new DeliveryFailure(false);
            return reply == null ? BrowserLogPendingFixtures.receipts(ids) : reply;
        }
        @Override public byte[] ingest(Scope scope, byte[] bytes, BrowserLogDeadline deadline) { return forward(scope, bytes, deadline); }
        @Override public byte[] receipts(Scope scope, byte[] ids, BrowserLogDeadline deadline) { return new byte[]{1}; }
    }
}
