package org.documentknowledge.browserbff.adapters.outbound;

import java.io.IOException;
import java.util.*;
import org.documentknowledge.browserbff.application.*;

/** Bounded accepted-work queue. Disk mutations are serialized; forwarding never holds its monitor. */
public final class DurableBrowserLogGateway implements BrowserLogGateway, AutoCloseable {
    public enum Replay { IDLE, COMMITTED, DISCARDED, RETRY }
    public enum Reason { NONE, TRANSIENT_DELIVERY_UNCERTAIN, INVALID_COMMIT_RECEIPT,
        TERMINAL_DELIVERY_UNCERTAIN, EXPIRED_DELIVERY_UNCERTAIN, STORAGE_UNCERTAIN }
    public record Status(int pendingEvents, int pendingBytes, int claimedEvents, long committedEvents,
                         long discardedEvents, Reason lastReason, boolean storageUncertain, boolean closed) { }
    private static final class Pending {
        final BrowserLogPendingStore.Entry entry;
        final Scope scope;
        final List<UUID> ids;
        boolean claimed;
        Pending(BrowserLogPendingStore.Entry entry, Scope scope, List<UUID> ids) {
            this.entry = entry; this.scope = scope; this.ids = List.copyOf(ids);
        }
    }
    private record Attempt(byte[] receipt, boolean discarded) { }
    private final BrowserLogRecoveryGateway gateway;
    private final BrowserLogPendingStore store;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private boolean closed, storeClosed, storageUncertain;
    private long committed, discarded;
    private Reason reason = Reason.NONE;

    public DurableBrowserLogGateway(BrowserLogRecoveryGateway gateway, BrowserLogPendingStore store) throws IOException {
        this.gateway = Objects.requireNonNull(gateway); this.store = Objects.requireNonNull(store);
        try {
            for (var entry : store.load()) {
                var scope = gateway.restore(entry.authority()); var ids = gateway.eventIds(scope, entry.batch());
                if (ids.size() != entry.eventCount() || pending.containsKey(entry.id()) || same(entry.authority(), entry.batch()) != null)
                    throw unavailable();
                pending.put(entry.id(), new Pending(entry, scope, ids)); requireBounds();
            }
        } catch (Exception invalid) { store.close(); throw new IOException("INVALID_BROWSER_LOG_PENDING"); }
    }
    @Override public Scope delegate(Grant grant, BrowserLogDeadline deadline) {
        synchronized (this) { available(); }
        return gateway.delegate(grant, deadline);
    }
    @Override public byte[] receipts(Scope scope, byte[] eventIds, BrowserLogDeadline deadline) {
        synchronized (this) { if (closed) throw unavailable(); }
        return gateway.receipts(scope, eventIds, deadline);
    }
    @Override public byte[] ingest(Scope scope, byte[] sanitizedBatch, BrowserLogDeadline deadline) {
        Pending work;
        try {
            deadline.requireTime(); byte[] bytes = sanitizedBatch.clone();
            byte[] authority = gateway.checkpoint(scope); List<UUID> ids = gateway.eventIds(scope, bytes);
            synchronized (this) {
                available(); work = same(authority, bytes);
                if (work == null) {
                    var entry = new BrowserLogPendingStore.Entry(UUID.randomUUID(), authority, bytes, ids.size());
                    work = new Pending(entry, scope, ids);
                    var status = status();
                    if (entry.eventCount() > BrowserLogPendingStore.MAX_EVENTS - status.pendingEvents()
                            || bytes.length > BrowserLogPendingStore.MAX_BYTES - status.pendingBytes()) throw limited();
                    var snapshot = entries(); snapshot.add(entry); replace(snapshot); pending.put(entry.id(), work);
                }
                if (work.claimed) throw limited(); work.claimed = true;
            }
        } catch (BffFailure fixed) { throw fixed; }
        catch (Exception failure) { throw unavailable(); }
        try {
            var result = attempt(work, deadline);
            if (result.discarded()) throw unavailable();
            return result.receipt();
        } finally { release(work); }
    }
    /** Bootstrap submits this operation through the existing bounded logging dispatcher. */
    public Replay replayOne(BrowserLogDeadline deadline) {
        Pending work;
        synchronized (this) {
            available(); work = pending.values().stream().filter(row -> !row.claimed).findFirst().orElse(null);
            if (work == null) return Replay.IDLE; work.claimed = true;
        }
        try { return attempt(work, deadline).discarded() ? Replay.DISCARDED : Replay.COMMITTED; }
        catch (BffFailure unavailable) { return Replay.RETRY; }
        finally { release(work); }
    }
    private Attempt attempt(Pending work, BrowserLogDeadline deadline) {
        byte[] receipt;
        try {
            deadline.requireTime();
            if (gateway.expired(work.scope)) {
                remove(work, Reason.EXPIRED_DELIVERY_UNCERTAIN, false); return new Attempt(null, true);
            }
            receipt = gateway.forward(work.scope, work.entry.batch(), deadline);
        } catch (BrowserLogRecoveryGateway.DeliveryFailure failure) {
            if (failure.terminal()) {
                remove(work, Reason.TERMINAL_DELIVERY_UNCERTAIN, false); return new Attempt(null, true);
            }
            degraded(Reason.TRANSIENT_DELIVERY_UNCERTAIN); throw unavailable();
        } catch (Exception failure) { degraded(Reason.TRANSIENT_DELIVERY_UNCERTAIN); throw unavailable(); }
        try { BrowserLogPendingJson.committed(receipt, work.ids); }
        catch (Exception invalid) { degraded(Reason.INVALID_COMMIT_RECEIPT); throw unavailable(); }
        remove(work, Reason.NONE, true); return new Attempt(receipt.clone(), false);
    }
    private synchronized void remove(Pending work, Reason result, boolean committedUpstream) {
        availableStorage();
        if (committedUpstream) committed = add(committed, work.entry.eventCount());
        var snapshot = entries(); snapshot.removeIf(row -> row.id().equals(work.entry.id()));
        replace(snapshot); pending.remove(work.entry.id());
        if (!committedUpstream) discarded = add(discarded, work.entry.eventCount());
        reason = result;
    }
    private void replace(List<BrowserLogPendingStore.Entry> entries) {
        try { store.replace(entries); }
        catch (Exception failure) { storageUncertain = true; reason = Reason.STORAGE_UNCERTAIN; throw unavailable(); }
    }
    private synchronized void release(Pending work) { work.claimed = false; closeStoreWhenIdle(); }
    private Pending same(byte[] authority, byte[] bytes) {
        for (var row : pending.values())
            if (Arrays.equals(authority, row.entry.authority()) && Arrays.equals(bytes, row.entry.batch())) return row;
        return null;
    }
    private ArrayList<BrowserLogPendingStore.Entry> entries() {
        return new ArrayList<>(pending.values().stream().map(row -> row.entry).toList());
    }
    public synchronized Status status() {
        int events = 0, bytes = 0, claimed = 0;
        for (var row : pending.values()) {
            events += row.entry.eventCount(); bytes += row.entry.batch().length;
            if (row.claimed) claimed += row.entry.eventCount();
        }
        return new Status(events, bytes, claimed, committed, discarded, reason, storageUncertain, closed);
    }
    private void requireBounds() {
        var status = status();
        if (status.pendingEvents() > BrowserLogPendingStore.MAX_EVENTS || status.pendingBytes() > BrowserLogPendingStore.MAX_BYTES) throw limited();
    }
    private synchronized void degraded(Reason value) { if (!storageUncertain) reason = value; }
    private void availableStorage() { if (storageUncertain || storeClosed) throw unavailable(); }
    private void available() { availableStorage(); if (closed) throw unavailable(); }
    @Override public synchronized void close() { closed = true; closeStoreWhenIdle(); }
    private void closeStoreWhenIdle() {
        if (closed && !storeClosed && pending.values().stream().noneMatch(row -> row.claimed)) { storeClosed = true; store.close(); }
    }
    private static long add(long current, int count) { return current > Long.MAX_VALUE - count ? Long.MAX_VALUE : current + count; }
    private static BffFailure unavailable() { return new BffFailure(503, "LOG_STORE_UNAVAILABLE"); }
    private static BffFailure limited() { return new BffFailure(429, "LOG_RATE_LIMITED"); }
}
