package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.devlogging.application.LogPersistence.ReceiptState;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Requires an explicitly owned real PostgreSQL fixture. Missing configuration fails, never skips. */
class JdbcLogPersistenceTest {
    static PGSimpleDataSource owner, runtime;
    static JdbcLogPersistence store;
    static final AtomicBoolean writable = new AtomicBoolean(true);
    static final String PASSWORD = UUID.randomUUID().toString();
    static final String SHA = "a".repeat(64);
    static final BuildIdentity BUILD = new BuildIdentity(UUID.randomUUID(), "browser-bff", "b".repeat(40), "c".repeat(64));
    static final UUID RUN = UUID.randomUUID();
    static final UUID PRODUCER = UUID.randomUUID();

    static PGSimpleDataSource source(String user, String password) {
        var result = new PGSimpleDataSource();
        result.setURL(java.util.Objects.requireNonNull(System.getenv("DKP_LOG_TEST_JDBC"), "Owned real PostgreSQL fixture required"));
        result.setUser(user); result.setPassword(password);
        result.setConnectTimeout(1); result.setLoginTimeout(1); result.setSocketTimeout(5);
        return result;
    }

    @BeforeAll static void migrate() throws Exception {
        owner = source("postgres", System.getenv("DKP_LOG_TEST_PASSWORD"));
        try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
            String migration;
            try (var input = JdbcLogPersistenceTest.class.getResourceAsStream("/db/logging-migration/V1__owned_dev_logging.sql")) {
                migration = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
            }
            statement.execute(migration);
            statement.execute("CREATE ROLE log_writer LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '" + PASSWORD + "'");
            statement.execute("REVOKE ALL ON DATABASE dkp_dev_logs FROM PUBLIC");
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT CONNECT ON DATABASE dkp_dev_logs TO log_writer");
            statement.execute("GRANT USAGE ON SCHEMA dev_logs TO log_writer");
            statement.execute("GRANT SELECT ON dev_logs.producers,dev_logs.scopes,dev_logs.manifests,dev_logs.capacity,dev_logs.events TO log_writer");
            // PostgreSQL locking SELECT requires UPDATE on at least one column of a locked table.
            statement.execute("GRANT UPDATE(revoked) ON dev_logs.producers TO log_writer");
            statement.execute("GRANT UPDATE(reserved_bytes) ON dev_logs.scopes,dev_logs.capacity TO log_writer");
            statement.execute("GRANT INSERT ON dev_logs.events TO log_writer");
            statement.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA dev_logs TO log_writer");
        }
        runtime = source("log_writer", PASSWORD);
        store = new JdbcLogPersistence(runtime, () -> {
            if (!writable.get()) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
        });
    }

    @BeforeEach void seed() throws Exception {
        writable.set(true);
        try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
            statement.execute("TRUNCATE dev_logs.events,dev_logs.scopes,dev_logs.producers,dev_logs.manifests,dev_logs.capacity");
            statement.execute("INSERT INTO dev_logs.capacity(singleton,reserved_bytes) VALUES (true,0)");
            try (var insert = connection.prepareStatement("INSERT INTO dev_logs.manifests VALUES (?,?,?,?,?,?,?,1026,clock_timestamp())")) {
                insert.setString(1, SHA); insert.setObject(2, BUILD.buildId()); insert.setString(3, BUILD.component());
                insert.setString(4, BUILD.gitSha()); insert.setString(5, BUILD.sourceFingerprint()); insert.setString(6, SHA);
                insert.setBytes(7, "{}".getBytes(StandardCharsets.UTF_8)); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO dev_logs.producers VALUES (?,?,?,clock_timestamp(),clock_timestamp()+interval '1 hour',false,1024)")) {
                insert.setObject(1, PRODUCER); insert.setString(2, SHA); insert.setObject(3, RUN); insert.executeUpdate();
            }
            statement.execute("UPDATE dev_logs.capacity SET reserved_bytes=2050");
        }
    }

    ProducerScope scope(String tenant) throws SQLException {
        var id = UUID.randomUUID();
        var until = Instant.now().plusSeconds(1800).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        try (var connection = owner.getConnection(); var insert = connection.prepareStatement("""
            INSERT INTO dev_logs.scopes VALUES (?,?,?,?,?,'TENANT','https://issuer.invalid','actor',?,
                statement_timestamp(),statement_timestamp()+interval '5 minutes',?,1024)
            """)) {
            insert.setObject(1, id); insert.setObject(2, PRODUCER); insert.setString(3, SHA); insert.setObject(4, RUN);
            insert.setObject(5, UUID.randomUUID()); insert.setString(6, tenant); insert.setTimestamp(7, Timestamp.from(until)); insert.executeUpdate();
        }
        sql("UPDATE dev_logs.capacity SET reserved_bytes=reserved_bytes+1024");
        return new ProducerScope(PRODUCER, new LogScope(id, LogScope.Kind.TENANT, "https://issuer.invalid", "actor", tenant),
            new ProducerBinding(BUILD, RUN), until);
    }

    CanonicalLogEvent event(UUID id, String payload) {
        var metadata = new EventMetadata(id, new ExecutionIdentity(BUILD, RUN, UUID.randomUUID()), Instant.now(),
            Severity.DEBUG, EventCode.OPERATION_STARTED, UUID.randomUUID(), new TraceContext("1".repeat(32), "2".repeat(16)));
        return new CanonicalLogEvent(metadata, payload.getBytes(StandardCharsets.UTF_8));
    }
    LogBatch batch(CanonicalLogEvent... events) { return new LogBatch(List.of(events)); }
    void sql(String query) throws SQLException { try (var connection = owner.getConnection(); var statement = connection.createStatement()) { statement.execute(query); } }
    long count(String query) throws SQLException { try (var connection = owner.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(query)) { rows.next(); return rows.getLong(1); } }
    void fails(LogFailure.Kind kind, org.junit.jupiter.api.function.Executable call) { assertEquals(kind, assertThrows(LogFailure.class, call).kind()); }

    @Test void actualCommitReplayAndReceiptsAreScopeBound() throws Exception {
        var a = scope("tenant-a"); var b = scope("tenant-b"); var event = event(UUID.randomUUID(), "{}");
        var first = store.commit(a, batch(event), Instant.now());
        assertEquals(first, store.commit(a, batch(event), Instant.now()));
        assertEquals(1, count("SELECT count(*) FROM dev_logs.events"));
        assertEquals(ReceiptState.COMMITTED, store.receipts(a, List.of(event.metadata().eventId()), Instant.now()).getFirst().state());
        assertEquals(ReceiptState.UNKNOWN, store.receipts(b, List.of(event.metadata().eventId()), Instant.now()).getFirst().state());
        store.commit(b, batch(event), Instant.now());
        assertEquals(2, count("SELECT count(*) FROM dev_logs.events"));
        assertEquals(2, count("SELECT count(DISTINCT canonical_hash) FROM dev_logs.events"));
        assertEquals(2, count("SELECT count(DISTINCT tenant_id) FROM dev_logs.events"));
        assertEquals(2050 + 2048 + 2 * 1026, count("SELECT reserved_bytes FROM dev_logs.capacity"));
    }

    @Test void changedPayloadRollsBackTheEntireBatchAndCapacity() throws Exception {
        var authority = scope("tenant-a"); var id = UUID.randomUUID();
        store.commit(authority, batch(event(id, "{}")), Instant.now());
        long before = count("SELECT reserved_bytes FROM dev_logs.capacity");
        fails(LogFailure.Kind.CONFLICT, () -> store.commit(authority, batch(event(UUID.randomUUID(), "{}"), event(id, "{\"changed\":true}")), Instant.now()));
        assertEquals(1, count("SELECT count(*) FROM dev_logs.events"));
        assertEquals(before, count("SELECT reserved_bytes FROM dev_logs.capacity"));
    }

    @Test void tombstoneNeverReturnsFalseCommitOrRecreatesPayload() throws Exception {
        var authority = scope("tenant-a"); var event = event(UUID.randomUUID(), "{}");
        store.commit(authority, batch(event), Instant.now());
        sql("UPDATE dev_logs.events SET canonical_payload=NULL");
        var receipt = store.receipts(authority, List.of(event.metadata().eventId()), Instant.now()).getFirst();
        assertEquals(ReceiptState.EXPIRED, receipt.state()); assertNull(receipt.receivedAt());
        fails(LogFailure.Kind.EXPIRED, () -> store.commit(authority, batch(event), Instant.now()));
        assertEquals(0, count("SELECT count(canonical_payload) FROM dev_logs.events"));
    }

    @Test void capacityAndFailedVolumeAdmissionFailClosed() throws Exception {
        var authority = scope("tenant-a"); var event = event(UUID.randomUUID(), "{}");
        sql("UPDATE dev_logs.capacity SET reserved_bytes=1073741824");
        fails(LogFailure.Kind.CAPACITY, () -> store.commit(authority, batch(event), Instant.now()));
        assertEquals(0, count("SELECT count(*) FROM dev_logs.events"));
        writable.set(false);
        fails(LogFailure.Kind.UNAVAILABLE, () -> store.commit(authority, batch(event), Instant.now()));
        assertEquals(0, count("SELECT count(*) FROM dev_logs.events"));
    }

    @Test void revokedMissingExpiredAndForgedAuthorityCannotReadOrWrite() throws Exception {
        var authority = scope("tenant-a"); var event = event(UUID.randomUUID(), "{}");
        var forged = new ProducerScope(PRODUCER, new LogScope(authority.scope().id(), LogScope.Kind.TENANT,
            "https://issuer.invalid", "actor", "tenant-b"), authority.binding(), authority.replayUntil());
        fails(LogFailure.Kind.FORBIDDEN, () -> store.commit(forged, batch(event), Instant.now()));
        fails(LogFailure.Kind.FORBIDDEN, () -> store.receipts(forged, List.of(event.metadata().eventId()), Instant.now()));
        sql("UPDATE dev_logs.producers SET revoked=true");
        fails(LogFailure.Kind.FORBIDDEN, () -> store.commit(authority, batch(event), Instant.now()));
        sql("UPDATE dev_logs.producers SET revoked=false,created_at=clock_timestamp()-interval '2 hours',accept_until=clock_timestamp()-interval '1 hour'");
        fails(LogFailure.Kind.EXPIRED, () -> store.commit(authority, batch(event), Instant.now()));
        sql("DELETE FROM dev_logs.scopes");
        fails(LogFailure.Kind.FORBIDDEN, () -> store.receipts(authority, List.of(event.metadata().eventId()), Instant.now()));
    }

    @Test void simultaneousReplayCommitsOnlyOnce() throws Exception {
        var authority = scope("tenant-a"); var event = event(UUID.randomUUID(), "{}");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Object> call = () -> { start.await(); return store.commit(authority, batch(event), Instant.now()); };
            var first = executor.submit(call); var second = executor.submit(call); start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("SELECT count(*) FROM dev_logs.events"));
    }

    @Test void writerCannotMutateEvidenceOrPerformDdl() throws Exception {
        try (var connection = runtime.getConnection(); var statement = connection.createStatement()) {
            for (var forbidden : List.of("DELETE FROM dev_logs.events", "UPDATE dev_logs.events SET tenant_id='forged'",
                    "CREATE TABLE dev_logs.bad(id int)", "CREATE TABLE public.bad(id int)", "CREATE DATABASE bad",
                    "DELETE FROM dev_logs.manifests", "INSERT INTO dev_logs.manifests DEFAULT VALUES")) {
                assertThrows(SQLException.class, () -> statement.execute(forbidden));
            }
        }
    }

    @Test void invalidReceiptsAndForeignBuildFailBeforeEvidenceLookup() throws Exception {
        var authority = scope("tenant-a");
        fails(LogFailure.Kind.INVALID, () -> store.receipts(authority, List.of(), Instant.now()));
        fails(LogFailure.Kind.INVALID, () -> store.receipts(authority, List.of(new UUID(0, 0)), Instant.now()));
        fails(LogFailure.Kind.INVALID, () -> store.receipts(authority, java.util.Collections.nCopies(33, UUID.randomUUID()), Instant.now()));
        var duplicate = UUID.randomUUID();
        fails(LogFailure.Kind.INVALID, () -> store.receipts(authority, List.of(duplicate, duplicate), Instant.now()));
        var other = new ProducerScope(PRODUCER, authority.scope(), new ProducerBinding(BUILD, UUID.randomUUID()), authority.replayUntil());
        fails(LogFailure.Kind.FORBIDDEN, () -> store.commit(other, batch(event(UUID.randomUUID(), "{}")), Instant.now()));
    }

    @Test void everyImmutableGrantFieldIsRecheckedAgainstTheRegistry() throws Exception {
        var authority = scope("tenant-a"); var ids = List.of(UUID.randomUUID());
        var original = authority.scope();
        for (var changed : List.of(
                new LogScope(original.id(), LogScope.Kind.AUTHENTICATED_UNSELECTED, original.issuer(), original.subject(), null),
                new LogScope(original.id(), original.kind(), "https://different.invalid", original.subject(), original.tenantId()),
                new LogScope(original.id(), original.kind(), original.issuer(), "different", original.tenantId()),
                new LogScope(original.id(), original.kind(), original.issuer(), original.subject(), "different"))) {
            var forged = new ProducerScope(PRODUCER, changed, authority.binding(), authority.replayUntil());
            fails(LogFailure.Kind.FORBIDDEN, () -> store.receipts(forged, ids, Instant.now()));
        }
        for (var changed : List.of(
                new BuildIdentity(UUID.randomUUID(), BUILD.component(), BUILD.gitSha(), BUILD.sourceFingerprint()),
                new BuildIdentity(BUILD.buildId(), "different", BUILD.gitSha(), BUILD.sourceFingerprint()),
                new BuildIdentity(BUILD.buildId(), BUILD.component(), "d".repeat(40), BUILD.sourceFingerprint()),
                new BuildIdentity(BUILD.buildId(), BUILD.component(), BUILD.gitSha(), "e".repeat(64)))) {
            var forged = new ProducerScope(PRODUCER, original, new ProducerBinding(changed, RUN), authority.replayUntil());
            fails(LogFailure.Kind.FORBIDDEN, () -> store.receipts(forged, ids, Instant.now()));
        }
        var changedRun = new ProducerScope(PRODUCER, original, new ProducerBinding(BUILD, UUID.randomUUID()), authority.replayUntil());
        fails(LogFailure.Kind.FORBIDDEN, () -> store.receipts(changedRun, ids, Instant.now()));
        var changedHorizon = new ProducerScope(PRODUCER, original, authority.binding(), authority.replayUntil().plusSeconds(1));
        fails(LogFailure.Kind.FORBIDDEN, () -> store.receipts(changedHorizon, ids, Instant.now()));
        sql("UPDATE dev_logs.scopes SET replay_until=created_at+interval '24 hours'");
        var beyondParent = new ProducerScope(PRODUCER, original, authority.binding(), Instant.now().plusSeconds(7200));
        fails(LogFailure.Kind.FORBIDDEN, () -> store.receipts(beyondParent, ids, Instant.now()));
    }

    @Test void expiredScopeMissingCapacityAndDatabaseErrorsFailClosed() throws Exception {
        var authority = scope("tenant-a"); var event = event(UUID.randomUUID(), "{}");
        sql("UPDATE dev_logs.scopes SET created_at=statement_timestamp()-interval '1 hour',capture_until=statement_timestamp()-interval '55 minutes',replay_until=statement_timestamp()-interval '30 minutes'");
        fails(LogFailure.Kind.EXPIRED, () -> store.receipts(authority, List.of(event.metadata().eventId()), Instant.now()));
        var fresh = scope("tenant-b");
        sql("DELETE FROM dev_logs.capacity");
        fails(LogFailure.Kind.UNAVAILABLE, () -> store.commit(fresh, batch(event), Instant.now()));
        sql("REVOKE SELECT ON dev_logs.events FROM log_writer");
        try {
            fails(LogFailure.Kind.UNAVAILABLE, () -> store.receipts(fresh, List.of(event.metadata().eventId()), Instant.now()));
        } finally { sql("GRANT SELECT ON dev_logs.events TO log_writer"); }
    }

    @Test void volumeFailureBeforeCommitRollsBackAlreadyInsertedEvents() throws Exception {
        var authority = scope("tenant-a"); var event = event(UUID.randomUUID(), "{}");
        long before = count("SELECT reserved_bytes FROM dev_logs.capacity");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var failing = new JdbcLogPersistence(runtime, () -> {
            if (calls.incrementAndGet() == 3) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
        });
        fails(LogFailure.Kind.UNAVAILABLE, () -> failing.commit(authority, batch(event), Instant.now()));
        assertEquals(0, count("SELECT count(*) FROM dev_logs.events"));
        assertEquals(before, count("SELECT reserved_bytes FROM dev_logs.capacity"));
    }

    @Test void heldCapacityLockUsesOneFiniteStatementBudget() throws Exception {
        var authority = scope("tenant-a"); var event = event(UUID.randomUUID(), "{}");
        try (var connection = owner.getConnection(); var lock = connection.createStatement()) {
            connection.setAutoCommit(false);
            lock.execute("SELECT reserved_bytes FROM dev_logs.capacity FOR UPDATE");
            long started = System.nanoTime();
            fails(LogFailure.Kind.UNAVAILABLE, () -> store.commit(authority, batch(event), Instant.now()));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(7), "SQL lock timeout is finite; this does not prove the HTTP total deadline");
            connection.rollback();
        }
        assertEquals(0, count("SELECT count(*) FROM dev_logs.events"));
    }
}
