package org.documentknowledge.knowledgebase.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Mandatory isolated PostgreSQL migration process contracts; no mocked database or skips. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeMigrationMainTest {
    private static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("knowledge_base").withUsername("postgres").withPassword(UUID.randomUUID().toString())
            .withLabel("dkp.issue", "206")
            .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on", "-c", "full_page_writes=on");
    private static final String MIGRATOR = UUID.randomUUID().toString(), RUNTIME = UUID.randomUUID().toString();
    private static Map<String, String> env;

    @BeforeAll static void provision() throws Exception {
        PG.start();
        try (Connection connection = admin(); var sql = connection.createStatement()) {
            sql.execute("CREATE ROLE dkp_kb_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '" + MIGRATOR + "'");
            sql.execute("CREATE ROLE dkp_kb_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '" + RUNTIME + "'");
            sql.execute("REVOKE ALL ON DATABASE knowledge_base FROM PUBLIC");
            sql.execute("REVOKE CONNECT ON DATABASE postgres,template1 FROM PUBLIC");
            sql.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            sql.execute("GRANT CONNECT,CREATE ON DATABASE knowledge_base TO dkp_kb_migrator");
            sql.execute("GRANT CONNECT ON DATABASE knowledge_base TO dkp_kb_runtime");
            sql.execute("CREATE SCHEMA kb AUTHORIZATION dkp_kb_migrator");
        }
        env = Map.of("DKP_KB_MIGRATION_URL", PG.getJdbcUrl().split("\\?", 2)[0] + "?currentSchema=kb",
                "DKP_KB_MIGRATION_USERNAME", "dkp_kb_migrator", "DKP_KB_MIGRATION_PASSWORD", MIGRATOR);
    }

    @AfterAll static void stop() { PG.stop(); }
    private static Connection admin() throws Exception { return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); }
    private static long scalar(String query) throws Exception {
        try (var connection = admin(); var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }
    private static String durableFacts() throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        String[] tables = {"tenant_policy_fences", "knowledge_collections", "collection_acl_rules", "audience_policies",
                "audience_rules", "collection_memberships", "membership_history", "policy_revisions", "document_membership_scopes",
                "policy_snapshots", "command_outcomes", "audit_facts", "outbox_events", "flyway_schema_history"};
        try (var connection = admin(); var statement = connection.createStatement()) {
            for (String table : tables) try (var result = statement.executeQuery("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE \"C\"),'[]'::jsonb)::text FROM kb." + table + " t")) {
                assertTrue(result.next());
                digest.update(table.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(result.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
    private static int run(Map<String, String> values, Duration budget) {
        var bytes = new ByteArrayOutputStream();
        int status = KnowledgeMigrationMain.run(values, budget, new PrintStream(bytes));
        String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(status == 0 ? "KB migrations complete\n" : "KB migrations failed; request startup is not admitted\n", output);
        assertFalse(output.contains(MIGRATOR)); assertFalse(output.contains(RUNTIME));
        return status;
    }
    private static int run(Map<String, String> values) { return run(values, Duration.ofSeconds(10)); }

    @Test @Order(1) void newAndRepeatedMigrationPreserveOwnedSchemaAndRuntimeRestrictions() throws Exception {
        assertEquals(0, run(env));
        assertEquals(14, scalar("SELECT count(*) FROM pg_tables WHERE schemaname='kb'"));
        assertEquals(1, scalar("SELECT count(*) FROM kb.flyway_schema_history WHERE success"));
        assertEquals(0, scalar("SELECT count(*) FROM kb.tenant_policy_fences"));
        assertEquals(0, run(env));
        assertEquals(1, scalar("SELECT count(*) FROM kb.flyway_schema_history WHERE success"));
        assertEquals(0, scalar("SELECT count(*) FROM pg_tables WHERE schemaname='public'"));
        try (var connection = DriverManager.getConnection(PG.getJdbcUrl(), "dkp_kb_runtime", RUNTIME); var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.execute("ALTER TABLE kb.knowledge_collections ADD COLUMN forbidden boolean"));
            assertThrows(java.sql.SQLException.class, () -> statement.execute("SELECT * FROM kb.flyway_schema_history"));
            try (var result = statement.executeQuery("SELECT count(*) FROM kb.knowledge_collections")) { assertTrue(result.next()); assertEquals(0, result.getLong(1)); }
        }
    }

    @Test @Order(2) void configurationCannotSelectAnotherRoleDatabaseLocationOrCredentialSource() throws Exception {
        for (String key : env.keySet()) {
            var missing = new HashMap<>(env); missing.remove(key); assertEquals(1, run(missing));
        }
        for (String role : new String[] {"postgres", "dkp_kb_runtime", "dkp_kb_migrator "}) {
            var values = new HashMap<>(env); values.put("DKP_KB_MIGRATION_USERNAME", role); assertEquals(1, run(values));
        }
        for (String url : new String[] {"", "jdbc:h2:mem:test", "jdbc:postgresql://localhost/postgres", "jdbc:postgresql://user:secret@localhost/knowledge_base",
                "jdbc:postgresql://localhost/knowledge_base?password=secret", "jdbc:postgresql://localhost/knowledge_base?currentSchema=public",
                "jdbc:postgresql://localhost/knowledge_base?currentSchema=kb&socketTimeout=99999", "jdbc:postgresql://localhost:0/knowledge_base",
                "jdbc:postgresql://localhost:65536/knowledge_base", "jdbc:postgresql:///knowledge_base", "jdbc:postgresql://localhost/knowledge_base#fragment"}) {
            var values = new HashMap<>(env); values.put("DKP_KB_MIGRATION_URL", url); assertEquals(1, run(values));
        }
        for (String key : new String[] {"FLYWAY_LOCATIONS", "FLYWAY_CLEAN_DISABLED", "DKP_KB_JDBC_PASSWORD", "DKP_KB_RUNTIME_PASSWORD",
                "DKP_KB_DB_ADMIN_PASSWORD", "DKP_KB_ADMIN_PASSWORD", "DKP_DB_ADMIN_PASSWORD", "POSTGRES_PASSWORD", "PGPASSWORD"}) {
            var values = new HashMap<>(env); values.put(key, "untrusted-extra-config"); assertEquals(1, run(values));
        }
        for (String password : new String[] {"", " ", "secret\n", "x".repeat(1025)}) {
            var values = new HashMap<>(env); values.put("DKP_KB_MIGRATION_PASSWORD", password); assertEquals(1, run(values));
        }
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(61)}) assertEquals(1, run(env, invalid));
        assertEquals(1, scalar("SELECT count(*) FROM kb.flyway_schema_history WHERE success"));
    }

    @Test @Order(3) void realAuthenticationFailureCannotAdmitStartupOrExposeProviderError() throws Exception {
        var wrong = new HashMap<>(env); wrong.put("DKP_KB_MIGRATION_PASSWORD", RUNTIME);
        assertEquals(1, run(wrong));
        assertEquals(1, scalar("SELECT count(*) FROM kb.flyway_schema_history WHERE success"));
    }

    @Test @Order(4) void removedSchemaAndExpandedRoleAreRejectedWithoutRecreatingOrChangingHistory() throws Exception {
        try (var connection = admin(); var sql = connection.createStatement()) {
            sql.execute("ALTER SCHEMA kb RENAME TO kb_retained");
            try { assertEquals(1, run(env)); assertEquals(0, scalar("SELECT count(*) FROM pg_namespace WHERE nspname='kb'")); }
            finally { sql.execute("ALTER SCHEMA kb_retained RENAME TO kb"); }
            sql.execute("ALTER ROLE dkp_kb_migrator CREATEDB");
            try { assertEquals(1, run(env)); }
            finally { sql.execute("ALTER ROLE dkp_kb_migrator NOCREATEDB"); }
        }
        assertEquals(0, run(env));
        assertEquals(1, scalar("SELECT count(*) FROM kb.flyway_schema_history WHERE success"));
    }

    @Test @Order(5) void repeatedStartupRejectsCurrentRuntimeAndPublicPrivilegeExpansionWithoutRepairingIt() throws Exception {
        String originalFacts = durableFacts();
        String[][] changes = {
            {"ALTER ROLE dkp_kb_runtime CREATEDB", "ALTER ROLE dkp_kb_runtime NOCREATEDB"},
            {"GRANT CREATE ON SCHEMA kb TO dkp_kb_runtime", "REVOKE CREATE ON SCHEMA kb FROM dkp_kb_runtime"},
            {"GRANT CREATE ON DATABASE knowledge_base TO dkp_kb_runtime", "REVOKE CREATE ON DATABASE knowledge_base FROM dkp_kb_runtime"},
            {"GRANT TEMP ON DATABASE knowledge_base TO dkp_kb_runtime", "REVOKE TEMP ON DATABASE knowledge_base FROM dkp_kb_runtime"},
            {"GRANT CREATE ON SCHEMA public TO dkp_kb_runtime", "REVOKE CREATE ON SCHEMA public FROM dkp_kb_runtime"},
            {"GRANT CONNECT ON DATABASE postgres TO dkp_kb_runtime", "REVOKE CONNECT ON DATABASE postgres FROM dkp_kb_runtime"},
            {"GRANT CONNECT ON DATABASE knowledge_base TO PUBLIC", "REVOKE CONNECT ON DATABASE knowledge_base FROM PUBLIC"},
            {"GRANT USAGE ON SCHEMA public TO PUBLIC", "REVOKE USAGE ON SCHEMA public FROM PUBLIC"},
            {"GRANT UPDATE ON kb.audience_rules TO dkp_kb_runtime", "REVOKE UPDATE ON kb.audience_rules FROM dkp_kb_runtime"},
            {"GRANT DELETE ON kb.audit_facts TO dkp_kb_runtime", "REVOKE DELETE ON kb.audit_facts FROM dkp_kb_runtime"},
            {"GRANT TRUNCATE ON kb.knowledge_collections TO dkp_kb_runtime", "REVOKE TRUNCATE ON kb.knowledge_collections FROM dkp_kb_runtime"},
            {"GRANT SELECT ON kb.flyway_schema_history TO dkp_kb_runtime", "REVOKE SELECT ON kb.flyway_schema_history FROM dkp_kb_runtime"},
            {"GRANT SELECT ON kb.knowledge_collections TO dkp_kb_runtime WITH GRANT OPTION", "REVOKE GRANT OPTION FOR SELECT ON kb.knowledge_collections FROM dkp_kb_runtime"},
            {"GRANT EXECUTE ON FUNCTION kb.seal_audience_policy() TO dkp_kb_runtime", "REVOKE EXECUTE ON FUNCTION kb.seal_audience_policy() FROM dkp_kb_runtime"}
        };
        try (var connection = admin(); var sql = connection.createStatement()) {
            for (String[] change : changes) {
                sql.execute(change[0]);
                try {
                    assertEquals(1, run(env), change[0]);
                    assertEquals(1, run(env), "No auto-repair may make the next unsafe startup pass");
                    assertEquals(1, scalar("SELECT count(*) FROM kb.flyway_schema_history WHERE success"));
                    assertEquals(0, scalar("SELECT count(*) FROM kb.tenant_policy_fences"));
                    assertEquals(originalFacts, durableFacts());
                } finally { sql.execute(change[1]); }
                assertEquals(0, run(env));
                assertEquals(originalFacts, durableFacts());
            }
        }
    }

    @Test @Order(90) void timeoutRemainsFailureAndAnOriginalMigrationRetryReconcilesDurableState() throws Exception {
        try (var connection = admin(); var sql = connection.createStatement()) {
            connection.setAutoCommit(false); sql.execute("LOCK TABLE kb.flyway_schema_history IN ACCESS EXCLUSIVE MODE");
            long start = System.nanoTime();
            assertEquals(1, run(env, Duration.ofMillis(250)));
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos());
            connection.rollback();
        }
        // Cancellation is not proof of rollback. Observe the old worker settling before reconciliation.
        long until = System.nanoTime() + Duration.ofSeconds(7).toNanos();
        while (scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='dkp-kb-migration'") != 0 && System.nanoTime() < until) Thread.sleep(20);
        assertEquals(0, scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='dkp-kb-migration'"));
        assertEquals(0, run(env));
        assertEquals(1, scalar("SELECT count(*) FROM kb.flyway_schema_history WHERE success"));
    }

    @Test @Order(99) void interruptedCallerFailsClosedAndKeepsItsInterruptFlag() {
        Thread.currentThread().interrupt();
        try { assertEquals(1, run(env)); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
}
