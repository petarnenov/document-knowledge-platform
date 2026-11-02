package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.documentknowledge.devlogging.application.LogPersistence;
import org.documentknowledge.devlogging.application.StorageAdmission;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.*;

/** Dedicated logging DB only. Bootstrap must also bound connection acquisition and socket I/O. */
public final class JdbcLogPersistence implements LogPersistence {
    private final DataSource dataSource;
    private final StorageAdmission storage;

    public JdbcLogPersistence(DataSource dataSource, StorageAdmission storage) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.storage = Objects.requireNonNull(storage);
    }

    /** Standalone owned operator/test operation only; HTTP uses the explicit deadline application port. */
    public List<Committed> commit(ProducerScope authority, LogBatch batch, Instant now) {
        return commit(authority, batch, now, RequestDeadline.start());
    }

    /** Standalone owned operator/test operation only; HTTP cannot renew its budget through the port. */
    public List<Receipt> receipts(ProducerScope authority, List<UUID> eventIds, Instant now) {
        return receipts(authority, eventIds, now, RequestDeadline.start());
    }

    @Override
    public List<Committed> commit(ProducerScope authority, LogBatch batch, Instant now, RequestDeadline deadline) {
        deadline.requireTime();
        authority.requireActive(now);
        batch.requireBinding(authority); // Check the whole envelope before any deduplication lookup.
        storage.requireWritable();
        return transaction(deadline, (connection, budget) -> {
            // All registry/retention writers must acquire this same first lock.
            try (var statement = budget.prepare(connection,
                    "SELECT reserved_bytes FROM dev_logs.capacity WHERE singleton FOR UPDATE")) {
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
                }
            }
            Instant receivedAt = verifyAuthority(connection, budget, authority);
            storage.requireWritable();
            var committed = new ArrayList<Committed>();
            for (var event : batch.events()) {
                var existing = existing(connection, budget, authority, event);
                if (existing != null) {
                    committed.add(existing);
                    continue;
                }
                reserve(connection, budget, 1024L + event.byteCount());
                insert(connection, budget, authority, event, receivedAt);
                committed.add(new Committed(event.metadata().eventId(), receivedAt));
            }
            // Check authority again after work/waits and before the COMMIT attempt.
            verifyAuthority(connection, budget, authority);
            storage.requireWritable();
            return List.copyOf(committed);
        });
    }

    @Override
    public List<Receipt> receipts(ProducerScope authority, List<UUID> eventIds, Instant now, RequestDeadline deadline) {
        deadline.requireTime();
        authority.requireActive(now);
        var ids = List.copyOf(eventIds);
        if (ids.isEmpty() || ids.size() > 32 || new java.util.HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id.equals(new UUID(0, 0)))) {
            throw new LogFailure(LogFailure.Kind.INVALID);
        }
        return transaction(deadline, (connection, budget) -> {
            verifyAuthority(connection, budget, authority);
            var result = new ArrayList<Receipt>();
            for (var id : ids) {
                try (var statement = lookup(connection, budget, authority, id)) {
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) result.add(new Receipt(id, ReceiptState.UNKNOWN, null));
                        else if (rows.getBytes("canonical_payload") == null) result.add(new Receipt(id, ReceiptState.EXPIRED, null));
                        else result.add(new Receipt(id, ReceiptState.COMMITTED, rows.getTimestamp("received_at").toInstant()));
                    }
                }
            }
            verifyAuthority(connection, budget, authority);
            return List.copyOf(result);
        });
    }

    private Instant verifyAuthority(Connection connection, Budget budget, ProducerScope authority) throws SQLException {
        String sql = """
            SELECT clock_timestamp() AS server_now, p.accept_until, p.revoked, s.replay_until,
                   s.kind,s.issuer,s.subject,s.tenant_id,s.target_run_id,
                   m.build_id,m.component,m.git_sha,m.source_fingerprint
            FROM dev_logs.producers p JOIN dev_logs.scopes s ON s.producer_id=p.producer_id
            JOIN dev_logs.manifests m ON m.manifest_sha=s.target_manifest_sha
            WHERE p.producer_id=? AND s.scope_id=? FOR SHARE OF p,s
            """;
        try (var statement = budget.prepare(connection, sql)) {
            statement.setObject(1, authority.producerId());
            statement.setObject(2, authority.scope().id());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getBoolean("revoked")) throw new LogFailure(LogFailure.Kind.FORBIDDEN);
                Instant serverNow = rows.getTimestamp("server_now").toInstant();
                Instant parentEnd = rows.getTimestamp("accept_until").toInstant();
                Instant scopeEnd = rows.getTimestamp("replay_until").toInstant();
                authority.requireActive(serverNow);
                if (!serverNow.isBefore(parentEnd) || !serverNow.isBefore(scopeEnd)) throw new LogFailure(LogFailure.Kind.EXPIRED);
                var scope = authority.scope();
                var build = authority.binding().build();
                boolean matches = scope.kind().name().equals(rows.getString("kind"))
                    && Objects.equals(scope.issuer(), rows.getString("issuer"))
                    && Objects.equals(scope.subject(), rows.getString("subject"))
                    && Objects.equals(scope.tenantId(), rows.getString("tenant_id"))
                    && authority.binding().runId().equals(rows.getObject("target_run_id", UUID.class))
                    && build.buildId().equals(rows.getObject("build_id", UUID.class))
                    && build.component().equals(rows.getString("component"))
                    && build.gitSha().equals(rows.getString("git_sha"))
                    && build.sourceFingerprint().equals(rows.getString("source_fingerprint"))
                    && !authority.replayUntil().isAfter(parentEnd) && !authority.replayUntil().isAfter(scopeEnd);
                if (!matches) throw new LogFailure(LogFailure.Kind.FORBIDDEN);
                return serverNow;
            }
        }
    }

    private PreparedStatement lookup(Connection connection, Budget budget, ProducerScope authority, UUID id) throws SQLException {
        var statement = budget.prepare(connection,
            "SELECT canonical_hash,canonical_payload,received_at FROM dev_logs.events WHERE producer_id=? AND scope_id=? AND event_id=?");
        statement.setObject(1, authority.producerId());
        statement.setObject(2, authority.scope().id());
        statement.setObject(3, id);
        return statement;
    }

    private Committed existing(Connection connection, Budget budget, ProducerScope authority, CanonicalLogEvent event) throws SQLException {
        try (var statement = lookup(connection, budget, authority, event.metadata().eventId()); var rows = statement.executeQuery()) {
            if (!rows.next()) return null;
            if (rows.getBytes("canonical_payload") == null) throw new LogFailure(LogFailure.Kind.EXPIRED);
            if (!event.scopeBoundHash(authority.scope().id()).equals(rows.getString("canonical_hash"))) {
                throw new LogFailure(LogFailure.Kind.CONFLICT);
            }
            return new Committed(event.metadata().eventId(), rows.getTimestamp("received_at").toInstant());
        }
    }

    private void reserve(Connection connection, Budget budget, long bytes) throws SQLException {
        try (var statement = budget.prepare(connection,
                "UPDATE dev_logs.capacity SET reserved_bytes=reserved_bytes+? WHERE singleton AND reserved_bytes<=1073741824-?")) {
            statement.setLong(1, bytes);
            statement.setLong(2, bytes);
            if (statement.executeUpdate() != 1) throw new LogFailure(LogFailure.Kind.CAPACITY);
        }
    }

    private void insert(Connection connection, Budget budget, ProducerScope authority, CanonicalLogEvent event, Instant receivedAt) throws SQLException {
        String sql = """
            INSERT INTO dev_logs.events(producer_id,scope_id,event_id,build_id,run_id,instance_id,component,git_sha,
                source_fingerprint,event_time,received_at,severity,event_name,correlation_id,trace_id,scope_kind,
                actor_issuer,actor_subject,tenant_id,canonical_hash,canonical_payload,retain_until,reserved_bytes)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        var metadata = event.metadata();
        var execution = metadata.execution();
        var build = execution.build();
        var scope = authority.scope();
        try (var statement = budget.prepare(connection, sql)) {
            statement.setObject(1, authority.producerId());
            statement.setObject(2, scope.id());
            statement.setObject(3, metadata.eventId());
            statement.setObject(4, build.buildId());
            statement.setObject(5, execution.runId());
            statement.setObject(6, execution.instanceId());
            statement.setString(7, build.component());
            statement.setString(8, build.gitSha());
            statement.setString(9, build.sourceFingerprint());
            statement.setTimestamp(10, Timestamp.from(metadata.timestamp()));
            statement.setTimestamp(11, Timestamp.from(receivedAt));
            statement.setString(12, metadata.severity().name());
            statement.setString(13, metadata.eventCode().wireName());
            statement.setObject(14, metadata.correlationId());
            statement.setString(15, metadata.trace().traceId());
            statement.setString(16, scope.kind().name());
            statement.setString(17, scope.issuer());
            statement.setString(18, scope.subject());
            statement.setString(19, scope.tenantId());
            statement.setString(20, event.scopeBoundHash(scope.id()));
            statement.setBytes(21, event.payload());
            statement.setTimestamp(22, Timestamp.from(receivedAt.plusSeconds(7 * 86400L)));
            statement.setLong(23, 1024L + event.byteCount());
            statement.executeUpdate();
        }
    }

    private <T> T transaction(RequestDeadline deadline, Work<T> work) {
        var budget = new Budget(deadline);
        try (var owned = new JdbcLogCleanup(dataSource.getConnection())) {
            var connection = owned.connection();
            budget.network(connection);
            connection.setAutoCommit(false);
            owned.transactionStarted();
            T result = work.apply(connection, budget);
            budget.limit(connection);
            budget.network(connection);
            budget.remaining();
            connection.commit();
            owned.transactionCompleted();
            budget.remaining(); // A lost/late commit response is UNAVAILABLE, never an invented receipt.
            return result;
        } catch (SQLException failure) {
            throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
        }
    }

    @FunctionalInterface
    private interface Work<T> { T apply(Connection connection, Budget budget) throws SQLException; }

    /** One monotonic budget across lock/statement waits, rather than five seconds per statement. */
    private static final class Budget {
        private final RequestDeadline deadline;
        Budget(RequestDeadline deadline) { this.deadline = Objects.requireNonNull(deadline); }
        long remaining() {
            deadline.requireTime();
            return Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos()));
        }
        void network(Connection connection) throws SQLException {
            connection.setNetworkTimeout(Runnable::run, Math.toIntExact(remaining()));
        }
        void limit(Connection connection) throws SQLException {
            network(connection);
            try (var statement = connection.prepareStatement("SELECT set_config('statement_timeout',?,true),set_config('lock_timeout',?,true)")) {
                String limit = remaining() + "ms";
                statement.setString(1, limit);
                statement.setString(2, limit);
                statement.execute();
            }
        }
        PreparedStatement prepare(Connection connection, String sql) throws SQLException {
            limit(connection);
            network(connection);
            return connection.prepareStatement(sql);
        }
    }
}
