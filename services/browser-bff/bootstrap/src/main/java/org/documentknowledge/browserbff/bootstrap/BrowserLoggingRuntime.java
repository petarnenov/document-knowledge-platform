package org.documentknowledge.browserbff.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.browserbff.adapters.inbound.BrowserLogRequests;
import org.documentknowledge.browserbff.adapters.outbound.*;

/** Owns the logging-only composition and eventual cleanup after real dispatcher/HTTPS exit. */
final class BrowserLoggingRuntime implements AutoCloseable {
    record Status(boolean stopping, boolean stopped, boolean incompleteShutdown, DurableBrowserLogGateway.Status queue) { }
    private final BrowserLogRequests requests;
    private final HttpsBrowserLogGateway https;
    private final DurableBrowserLogGateway gateway;
    private final BrowserLogReplayCoordinator replay;
    private final AtomicBoolean closing = new AtomicBoolean();

    BrowserLoggingRuntime(BrowserLoggingFactory.Settings settings, BrowserLogRequests requests) throws IOException {
        this.requests = Objects.requireNonNull(requests);
        https = new HttpsBrowserLogGateway(settings.endpoint(), settings.parent(), settings.delegator(), settings.tls(), Clock.systemUTC(),
            settings.caSha256(), settings.artifacts());
        try {
            String profile = "dkp-browser-spool-v1\n" + settings.endpoint().toASCIIString() + "\n" + settings.parent() + "\n" + settings.caSha256();
            String binding = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(profile.getBytes(StandardCharsets.UTF_8)));
            gateway = new DurableBrowserLogGateway(https, new FileBrowserLogPendingStore(settings.spoolDirectory(), binding));
        } catch (Exception invalid) { https.close(); throw new IOException("INVALID_BROWSER_LOG_CONFIGURATION"); }
        replay = new BrowserLogReplayCoordinator(requests, gateway::replayOne, Thread::sleep, this::cleanup);
    }
    DurableBrowserLogGateway gateway() { return gateway; }
    Status status() {
        boolean stopped = replay.health().stopped();
        boolean stopping = closing.get();
        return new Status(stopping, stopped, stopping && !stopped, gateway.status());
    }
    @Override public void close() {
        if (closing.compareAndSet(false, true)) { replay.close(); requests.close(); gateway.close(); }
        try { awaitClosed(Duration.ofSeconds(5)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    boolean awaitClosed(Duration budget) throws InterruptedException { return replay.awaitClosed(budget); }
    private void cleanup() {
        boolean interrupted = Thread.interrupted();
        try {
            closing.set(true); requests.close(); gateway.close();
            for (;;) try { if (requests.awaitClosed(Duration.ofSeconds(1))) break; }
                catch (InterruptedException signal) { interrupted = true; }
            https.close();
            for (;;) try { if (https.awaitClosed(Duration.ofSeconds(1))) break; }
                catch (InterruptedException signal) { interrupted = true; }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
