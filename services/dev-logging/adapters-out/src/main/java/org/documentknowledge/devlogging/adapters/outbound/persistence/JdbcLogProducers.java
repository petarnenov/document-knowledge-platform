package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.BuildIdentity;

/** Owned scoped registration/replay adapter. Never wired into the read-only assembly. */
public final class JdbcLogProducers implements LogProducerRegistry, LogProducerAuthentication, LogManifestCatalog {
    private final DataSource dataSource;
    private final SecureRandom random = new SecureRandom();

    public JdbcLogProducers(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override
    public Issued register(Registrar registrar, ArtifactRegistration artifact, LogScope.Description scope,
                           Instant acceptUntil, RequestDeadline deadline) {
        Objects.requireNonNull(registrar); Objects.requireNonNull(artifact); Objects.requireNonNull(scope); Objects.requireNonNull(acceptUntil);
        Instant acceptedEnd = acceptUntil.truncatedTo(ChronoUnit.MICROS);
        return transaction(deadline, true, (connection, budget) -> {
            var now = now(connection, budget); registrar.requireActive(now);
            if (!acceptedEnd.isAfter(now) || acceptedEnd.isAfter(now.plus(48, ChronoUnit.HOURS))) invalid();
            manifest(connection, budget, artifact);
            bound(connection, budget, "SELECT count(*) FROM dev_logs.producers", 4096);
            bound(connection, budget, "SELECT count(*) FROM dev_logs.scopes", 131072);
            reserve(connection, budget, 2048);
            var parent = UUID.randomUUID();
            try (var insert = budget.prepare(connection, """
                    INSERT INTO dev_logs.producers(producer_id,manifest_sha,run_id,created_at,accept_until,revoked,reserved_bytes)
                    VALUES (?,?,?,?,?,false,1024)
                    """)) {
                insert.setObject(1,parent); insert.setString(2,artifact.manifestSha256()); insert.setObject(3,artifact.binding().runId());
                insert.setTimestamp(4,Timestamp.from(now)); insert.setTimestamp(5,Timestamp.from(acceptedEnd)); insert.executeUpdate();
            }
            var capture = earlier(now.plus(5,ChronoUnit.MINUTES),acceptedEnd);
            var issued = scope(connection,budget,parent,artifact,scope,UUID.randomUUID(),now,capture,acceptedEnd);
            registrar.requireActive(now(connection,budget)); parent(connection,budget,parent);
            return issued;
        });
    }

    @Override
    public Issued delegate(Delegator delegator, UUID parent, ArtifactRegistration artifact, LogScope.Description description,
                           UUID contextEpoch, Instant captureUntil, RequestDeadline deadline) {
        Objects.requireNonNull(delegator); Objects.requireNonNull(artifact); Objects.requireNonNull(description);
        requireId(parent); requireId(contextEpoch); Objects.requireNonNull(captureUntil);
        delegator.requireScope(parent,description);
        Instant capturedEnd = captureUntil.truncatedTo(ChronoUnit.MICROS);
        return transaction(deadline, true, (connection,budget) -> {
            var now = now(connection,budget); delegator.requireActive(now);
            var original = parent(connection,budget,parent);
            manifest(connection,budget,artifact);
            if (!original.artifact().equals(artifact) && !delegator.bridgeManifests().contains(artifact.manifestSha256()))
                throw new LogFailure(LogFailure.Kind.FORBIDDEN);
            var maximum = earlier(earlier(now.plus(5,ChronoUnit.MINUTES),original.acceptUntil()),delegator.expiresAt());
            if (!capturedEnd.isAfter(now) || capturedEnd.isAfter(maximum)) invalid();
            bound(connection,budget,"SELECT count(*) FROM dev_logs.scopes",131072); reserve(connection,budget,1024);
            var replay = earlier(original.acceptUntil(),now.plus(48,ChronoUnit.HOURS));
            var issued = scope(connection,budget,parent,artifact,description,contextEpoch,now,capturedEnd,replay);
            delegator.requireActive(now(connection,budget)); parent(connection,budget,parent);
            return issued;
        });
    }

    @Override
    public void revoke(Registrar registrar, UUID parent, RequestDeadline deadline) {
        Objects.requireNonNull(registrar); requireId(parent);
        transaction(deadline,true,(connection,budget) -> {
            registrar.requireActive(now(connection,budget));
            try (var update = budget.prepare(connection,"UPDATE dev_logs.producers SET revoked=true WHERE producer_id=?")) {
                update.setObject(1,parent); update.executeUpdate();
            }
            registrar.requireActive(now(connection,budget)); return null;
        });
    }

    @Override
    public ProducerScope authenticate(String token, RequestDeadline deadline) {
        String hash = tokenHash(token);
        return transaction(deadline,false,(connection,budget) -> {
            try (var query = budget.prepare(connection,"""
                    SELECT p.producer_id,p.revoked,p.accept_until,s.scope_id,s.kind,s.issuer,s.subject,s.tenant_id,
                        s.replay_until,s.target_run_id,m.build_id,m.component,m.git_sha,m.source_fingerprint,
                        clock_timestamp() AS server_now
                    FROM dev_logs.scopes s JOIN dev_logs.producers p ON p.producer_id=s.producer_id
                    JOIN dev_logs.manifests m ON m.manifest_sha=s.target_manifest_sha
                    WHERE s.credential_sha256=?
                    """)) {
                query.setString(1,hash);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
                    return authenticated(rows);
                }
            }
        });
    }

    @Override
    public VerifiedLogManifest load(ProducerScope authority, RequestDeadline deadline) {
        Objects.requireNonNull(authority);
        return transaction(deadline,false,(connection,budget)->{
            try(var query=budget.prepare(connection,"""
                    SELECT p.producer_id,p.revoked,p.accept_until,s.scope_id,s.kind,s.issuer,s.subject,s.tenant_id,
                        s.replay_until,s.target_run_id,m.build_id,m.component,m.git_sha,m.source_fingerprint,
                        m.manifest_sha,m.vocabulary_sha,m.manifest_bytes,clock_timestamp() AS server_now
                    FROM dev_logs.scopes s JOIN dev_logs.producers p ON p.producer_id=s.producer_id
                    JOIN dev_logs.manifests m ON m.manifest_sha=s.target_manifest_sha
                    WHERE s.scope_id=? AND p.producer_id=?
                    """)) {
                query.setObject(1,authority.scope().id());query.setObject(2,authority.producerId());
                try(var rows=query.executeQuery()) {
                    if(!rows.next() || !authority.equals(authenticated(rows))) throw new LogFailure(LogFailure.Kind.FORBIDDEN);
                    try {
                        return new VerifiedLogManifest(authority.binding().build(),rows.getString("manifest_sha"),
                            rows.getString("vocabulary_sha"),rows.getBytes("manifest_bytes"));
                    } catch(LogFailure corruptCatalog) { throw new LogFailure(LogFailure.Kind.UNAVAILABLE); }
                }
            }
        });
    }

    private static ProducerScope authenticated(ResultSet rows) throws SQLException {
        if (rows.getBoolean("revoked")) throw new LogFailure(LogFailure.Kind.FORBIDDEN);
        var replay = earlier(rows.getTimestamp("accept_until").toInstant(),rows.getTimestamp("replay_until").toInstant());
        if (!rows.getTimestamp("server_now").toInstant().isBefore(replay)) throw new LogFailure(LogFailure.Kind.EXPIRED);
        var scope = new LogScope(rows.getObject("scope_id",UUID.class),LogScope.Kind.valueOf(rows.getString("kind")),
            rows.getString("issuer"),rows.getString("subject"),rows.getString("tenant_id"));
        return new ProducerScope(rows.getObject("producer_id",UUID.class),scope,
            new ProducerBinding(build(rows),rows.getObject("target_run_id",UUID.class)),replay);
    }

    private record Parent(ArtifactRegistration artifact, Instant acceptUntil) { }
    private Parent parent(Connection connection, Budget budget, UUID parent) throws SQLException {
        try (var query = budget.prepare(connection,"""
                SELECT p.revoked,p.accept_until,p.run_id,p.manifest_sha,m.vocabulary_sha,
                    m.build_id,m.component,m.git_sha,m.source_fingerprint,clock_timestamp() AS server_now
                FROM dev_logs.producers p JOIN dev_logs.manifests m ON m.manifest_sha=p.manifest_sha
                WHERE p.producer_id=? FOR SHARE OF p
                """)) {
            query.setObject(1,parent);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || rows.getBoolean("revoked")) throw new LogFailure(LogFailure.Kind.FORBIDDEN);
                var end = rows.getTimestamp("accept_until").toInstant();
                if (!rows.getTimestamp("server_now").toInstant().isBefore(end)) throw new LogFailure(LogFailure.Kind.EXPIRED);
                return new Parent(new ArtifactRegistration(new ProducerBinding(build(rows),rows.getObject("run_id",UUID.class)),
                    rows.getString("manifest_sha"),rows.getString("vocabulary_sha")),end);
            }
        }
    }

    private static void manifest(Connection connection, Budget budget, ArtifactRegistration artifact) throws SQLException {
        try (var query = budget.prepare(connection,"SELECT build_id,component,git_sha,source_fingerprint,vocabulary_sha FROM dev_logs.manifests WHERE manifest_sha=?")) {
            query.setString(1,artifact.manifestSha256());
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !artifact.binding().build().equals(build(rows))
                        || !artifact.vocabularySha256().equals(rows.getString("vocabulary_sha")))
                    throw new LogFailure(LogFailure.Kind.FORBIDDEN);
            }
        }
    }

    private Issued scope(Connection connection, Budget budget, UUID parent, ArtifactRegistration artifact, LogScope.Description description,
                         UUID epoch, Instant created, Instant capture, Instant replay) throws SQLException {
        byte[] secret = new byte[32]; random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var scope = description.identified(UUID.randomUUID());
        try (var insert = budget.prepare(connection,"""
                INSERT INTO dev_logs.scopes(scope_id,producer_id,target_manifest_sha,target_run_id,context_epoch,
                    kind,issuer,subject,tenant_id,created_at,capture_until,replay_until,reserved_bytes,credential_sha256)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1024,?)
                """)) {
            insert.setObject(1,scope.id()); insert.setObject(2,parent); insert.setString(3,artifact.manifestSha256());
            insert.setObject(4,artifact.binding().runId()); insert.setObject(5,epoch); insert.setString(6,scope.kind().name());
            insert.setString(7,scope.issuer()); insert.setString(8,scope.subject()); insert.setString(9,scope.tenantId());
            insert.setTimestamp(10,Timestamp.from(created)); insert.setTimestamp(11,Timestamp.from(capture));
            insert.setTimestamp(12,Timestamp.from(replay)); insert.setString(13,tokenHash(token)); insert.executeUpdate();
        }
        return new Issued(new ProducerScope(parent,scope,artifact.binding(),replay),capture,token);
    }

    private static String tokenHash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token))
            throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        return VerifiedLogManifest.sha256(token.getBytes(StandardCharsets.US_ASCII));
    }
    private static BuildIdentity build(ResultSet rows) throws SQLException {
        return new BuildIdentity(rows.getObject("build_id",UUID.class),rows.getString("component"),rows.getString("git_sha"),rows.getString("source_fingerprint"));
    }
    private static Instant now(Connection connection, Budget budget) throws SQLException {
        try (var query = budget.prepare(connection,"SELECT clock_timestamp()"); var rows = query.executeQuery()) {
            if (!rows.next()) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
            return rows.getTimestamp(1).toInstant();
        }
    }
    private static void bound(Connection connection, Budget budget, String sql, long maximum) throws SQLException {
        try (var query = budget.prepare(connection,sql); var rows = query.executeQuery()) {
            if (!rows.next()) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
            if (rows.getLong(1) >= maximum) throw new LogFailure(LogFailure.Kind.CAPACITY);
        }
    }
    private static void reserve(Connection connection, Budget budget, long bytes) throws SQLException {
        try (var update = budget.prepare(connection,"UPDATE dev_logs.capacity SET reserved_bytes=reserved_bytes+? WHERE singleton AND reserved_bytes<=1073741824-?")) {
            update.setLong(1,bytes); update.setLong(2,bytes);
            if (update.executeUpdate() != 1) throw new LogFailure(LogFailure.Kind.CAPACITY);
        }
    }
    private <T> T transaction(RequestDeadline deadline, boolean capacityLock, Work<T> work) {
        Objects.requireNonNull(deadline).requireTime(); var budget = new Budget(deadline);
        try (var owned = new JdbcLogCleanup(dataSource.getConnection())) {
            var connection = owned.connection(); budget.network(connection); connection.setAutoCommit(false); owned.transactionStarted();
            if (capacityLock) {
                try (var query = budget.prepare(connection,"SELECT reserved_bytes FROM dev_logs.capacity WHERE singleton FOR UPDATE");
                     var rows = query.executeQuery()) { if (!rows.next()) throw new LogFailure(LogFailure.Kind.UNAVAILABLE); }
            }
            T result = work.apply(connection,budget);
            budget.limits(connection); budget.network(connection); deadline.requireTime();
            connection.commit(); owned.transactionCompleted(); deadline.requireTime(); return result;
        } catch (SQLException failure) {
            throw new LogFailure("23505".equals(failure.getSQLState()) ? LogFailure.Kind.CONFLICT : LogFailure.Kind.UNAVAILABLE);
        }
    }
    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
    private static void requireId(UUID id) { if (id == null || id.equals(new UUID(0,0))) invalid(); }
    private static void invalid() { throw new LogFailure(LogFailure.Kind.INVALID); }
    @FunctionalInterface private interface Work<T> { T apply(Connection connection, Budget budget) throws SQLException; }
    private static final class Budget {
        private final RequestDeadline deadline;
        Budget(RequestDeadline deadline) { this.deadline = deadline; }
        int millis() { deadline.requireTime(); return Math.toIntExact(Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos()))); }
        void network(Connection connection) throws SQLException { connection.setNetworkTimeout(Runnable::run,millis()); }
        void limits(Connection connection) throws SQLException {
            network(connection);
            try (var settings = connection.prepareStatement("SELECT set_config('statement_timeout',?,true),set_config('lock_timeout',?,true)")) {
                String limit = millis()+"ms"; settings.setString(1,limit); settings.setString(2,limit); settings.execute();
            }
        }
        PreparedStatement prepare(Connection connection, String sql) throws SQLException {
            limits(connection); network(connection); return connection.prepareStatement(sql);
        }
    }
}
