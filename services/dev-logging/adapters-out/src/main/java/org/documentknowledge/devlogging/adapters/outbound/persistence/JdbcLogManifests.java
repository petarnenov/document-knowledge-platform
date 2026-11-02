package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.documentknowledge.devlogging.application.LogManifestRegistry;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.documentknowledge.devlogging.domain.VerifiedLogManifest;

/** Owned registration storage, never supplied to the read-only query assembly. */
public final class JdbcLogManifests implements LogManifestRegistry {
    private final DataSource dataSource;

    public JdbcLogManifests(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override
    public Installed install(VerifiedLogManifest manifest, RequestDeadline deadline) {
        Objects.requireNonNull(manifest); Objects.requireNonNull(deadline).requireTime();
        try (var owned = new JdbcLogCleanup(dataSource.getConnection())) {
            var connection = owned.connection();
            network(connection, deadline);
            connection.setAutoCommit(false); owned.transactionStarted();
            try (var lock = prepare(connection, deadline,
                    "SELECT reserved_bytes FROM dev_logs.capacity WHERE singleton FOR UPDATE");
                 var rows = lock.executeQuery()) {
                if (!rows.next()) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
            }
            if (!existing(connection, deadline, manifest)) {
                try (var count = prepare(connection, deadline, "SELECT count(*) FROM dev_logs.manifests");
                     var rows = count.executeQuery()) {
                    if (!rows.next()) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
                    if (rows.getLong(1) >= 4096) throw new LogFailure(LogFailure.Kind.CAPACITY);
                }
                try (var reserve = prepare(connection, deadline,
                        "UPDATE dev_logs.capacity SET reserved_bytes=reserved_bytes+? WHERE singleton AND reserved_bytes<=1073741824-?")) {
                    reserve.setLong(1, manifest.reservedBytes()); reserve.setLong(2, manifest.reservedBytes());
                    if (reserve.executeUpdate() != 1) throw new LogFailure(LogFailure.Kind.CAPACITY);
                }
                insert(connection, deadline, manifest);
            }
            limits(connection, deadline); network(connection, deadline); deadline.requireTime();
            connection.commit(); owned.transactionCompleted();
            deadline.requireTime();
            return new Installed(manifest.digest(), manifest.build());
        } catch (SQLException failure) { throw new LogFailure(LogFailure.Kind.UNAVAILABLE); }
    }

    private boolean existing(Connection connection, RequestDeadline deadline, VerifiedLogManifest manifest) throws SQLException {
        try (var query = prepare(connection, deadline,
                "SELECT build_id,component,git_sha,source_fingerprint,vocabulary_sha,manifest_bytes FROM dev_logs.manifests WHERE manifest_sha=?")) {
            query.setString(1, manifest.digest());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return false;
                var build = manifest.build();
                if (!build.buildId().equals(rows.getObject("build_id", java.util.UUID.class))
                        || !build.component().equals(rows.getString("component"))
                        || !build.gitSha().equals(rows.getString("git_sha"))
                        || !build.sourceFingerprint().equals(rows.getString("source_fingerprint"))
                        || !manifest.vocabularyDigest().equals(rows.getString("vocabulary_sha"))
                        || !Arrays.equals(manifest.bytes(), rows.getBytes("manifest_bytes"))) {
                    throw new LogFailure(LogFailure.Kind.CONFLICT);
                }
                return true;
            }
        }
    }

    private void insert(Connection connection, RequestDeadline deadline, VerifiedLogManifest manifest) throws SQLException {
        try (var insert = prepare(connection, deadline, """
                INSERT INTO dev_logs.manifests(manifest_sha,build_id,component,git_sha,source_fingerprint,
                    vocabulary_sha,manifest_bytes,reserved_bytes,installed_at)
                VALUES (?,?,?,?,?,?,?,?,clock_timestamp())
                """)) {
            insert.setString(1, manifest.digest()); insert.setObject(2, manifest.build().buildId());
            insert.setString(3, manifest.build().component()); insert.setString(4, manifest.build().gitSha());
            insert.setString(5, manifest.build().sourceFingerprint()); insert.setString(6, manifest.vocabularyDigest());
            insert.setBytes(7, manifest.bytes()); insert.setLong(8, manifest.reservedBytes()); insert.executeUpdate();
        }
    }

    private static int millis(RequestDeadline deadline) {
        deadline.requireTime();
        return Math.toIntExact(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos())));
    }
    private static void network(Connection connection, RequestDeadline deadline) throws SQLException {
        connection.setNetworkTimeout(Runnable::run, millis(deadline));
    }
    private static void limits(Connection connection, RequestDeadline deadline) throws SQLException {
        network(connection, deadline);
        try (var settings = connection.prepareStatement("SELECT set_config('statement_timeout',?,true),set_config('lock_timeout',?,true)")) {
            String value = millis(deadline) + "ms";
            settings.setString(1, value); settings.setString(2, value); settings.execute();
        }
    }
    private static PreparedStatement prepare(Connection connection, RequestDeadline deadline, String sql) throws SQLException {
        limits(connection, deadline); network(connection, deadline); return connection.prepareStatement(sql);
    }
}
