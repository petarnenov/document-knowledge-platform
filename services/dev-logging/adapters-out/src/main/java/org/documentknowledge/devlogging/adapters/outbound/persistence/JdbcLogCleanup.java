package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.documentknowledge.devlogging.domain.LogFailure;

/** One owning worker, one two-second cleanup-only tail; never dispatches replacement cleanup work. */
final class JdbcLogCleanup implements AutoCloseable {
    private final Connection connection;
    private final LongSupplier nanos;
    private boolean transaction;
    private boolean finished;

    JdbcLogCleanup(Connection connection) { this(connection, System::nanoTime); }
    JdbcLogCleanup(Connection connection, LongSupplier nanos) {
        this.connection = Objects.requireNonNull(connection);
        this.nanos = Objects.requireNonNull(nanos);
    }
    Connection connection() { return connection; }
    void transactionStarted() { transaction = true; }
    void transactionCompleted() { transaction = false; }

    @Override
    public void close() {
        if (finished) return;
        finished = true; // No retries or fresh cleanup budget on a second close invocation.
        long started = nanos.getAsLong();
        boolean failed = false;
        if (transaction) {
            try { limit(started); connection.rollback(); }
            catch (SQLException | LogFailure failure) { failed = true; }
        }
        try { limit(started); connection.close(); }
        catch (SQLException | LogFailure failure) { failed = true; }
        if (failed || nanos.getAsLong() - started >= TimeUnit.SECONDS.toNanos(2)) unavailable();
        // If a driver/OS call does not return, this worker remains its actual owner until it does.
    }

    private void limit(long started) throws SQLException {
        long remaining = TimeUnit.SECONDS.toNanos(2) - (nanos.getAsLong() - started);
        if (remaining <= 0) unavailable();
        connection.setNetworkTimeout(Runnable::run, Math.toIntExact(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
    }
    private static void unavailable() { throw new LogFailure(LogFailure.Kind.UNAVAILABLE); }
}
