package org.documentknowledge.knowledgebase.bootstrap;

import java.io.PrintStream;
import java.net.URI;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;

/** Separate one-shot process: never starts the request server or receives DML credentials. */
public final class KnowledgeMigrationMain {
    private static final Duration MAXIMUM = Duration.ofSeconds(60);
    private static final Set<String> FORBIDDEN = Set.of("DKP_KB_JDBC_PASSWORD", "DKP_KB_RUNTIME_PASSWORD",
            "DKP_KB_DB_ADMIN_PASSWORD", "DKP_KB_ADMIN_PASSWORD", "DKP_DB_ADMIN_PASSWORD", "POSTGRES_PASSWORD", "PGPASSWORD");
    private static final Map<String, String> JDBC = Map.of("connectTimeout", "2", "socketTimeout", "5",
            "ApplicationName", "dkp-kb-migration", "options", "-c statement_timeout=45000 -c lock_timeout=5000");
    private static final String PRECONDITIONS = """
        SELECT current_database()='knowledge_base' AND current_user='dkp_kb_migrator'
          AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user AND NOT rolsuper
            AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit AND NOT rolreplication AND NOT rolbypassrls)
          AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user))
          AND EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='kb' AND nspowner=(SELECT oid FROM pg_roles WHERE rolname=current_user))
          AND NOT has_schema_privilege(current_user,'public','CREATE')
          AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname='dkp_kb_runtime' AND NOT rolsuper
            AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit AND NOT rolreplication AND NOT rolbypassrls)
          AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dkp_kb_runtime'))
          AND NOT has_database_privilege('dkp_kb_runtime',current_database(),'CREATE')
          AND NOT has_database_privilege('dkp_kb_runtime',current_database(),'TEMP')
          AND NOT has_schema_privilege('dkp_kb_runtime','public','CREATE')
          AND NOT has_schema_privilege('dkp_kb_runtime','kb','CREATE')
          AND NOT EXISTS (SELECT 1 FROM pg_database d WHERE d.datallowconn AND d.datname<>current_database()
            AND (has_database_privilege(current_user,d.oid,'CONNECT') OR has_database_privilege('dkp_kb_runtime',d.oid,'CONNECT')))
          AND NOT EXISTS (SELECT 1 FROM pg_database d,LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
            WHERE d.datname=current_database() AND a.grantee=0)
          AND NOT EXISTS (SELECT 1 FROM pg_namespace n,LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
            WHERE n.nspname IN ('public','kb') AND a.grantee=0)
        """;
    private static final String POSTCONDITIONS = """
        SELECT has_database_privilege('dkp_kb_runtime',current_database(),'CONNECT')
          AND has_schema_privilege('dkp_kb_runtime','kb','USAGE')
          AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='kb' AND c.relkind IN ('r','p') AND (
              c.relowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user)
              OR has_table_privilege('dkp_kb_runtime',c.oid,'TRUNCATE')
              OR has_table_privilege('dkp_kb_runtime',c.oid,'REFERENCES')
              OR has_table_privilege('dkp_kb_runtime',c.oid,'TRIGGER')
              OR has_table_privilege('dkp_kb_runtime',c.oid,'UPDATE')<>(c.relname IN ('tenant_policy_fences','knowledge_collections','collection_memberships','document_membership_scopes'))
              OR has_table_privilege('dkp_kb_runtime',c.oid,'DELETE')<>(c.relname='collection_acl_rules')
              OR has_table_privilege('dkp_kb_runtime',c.oid,'SELECT')<>(c.relname<>'flyway_schema_history')
              OR has_table_privilege('dkp_kb_runtime',c.oid,'INSERT')<>(c.relname<>'flyway_schema_history')
              OR EXISTS (SELECT 1 FROM aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                WHERE a.grantee=0 OR a.grantee=(SELECT oid FROM pg_roles WHERE rolname='dkp_kb_runtime') AND a.is_grantable)))
          AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
            WHERE n.nspname='kb' AND has_function_privilege('dkp_kb_runtime',p.oid,'EXECUTE'))
        """;

    private KnowledgeMigrationMain() { }

    public static void main(String[] args) {
        System.exit(run(System.getenv(), MAXIMUM, System.out));
    }

    /** The process exits after timeout; cancellation alone makes no rollback claim. */
    static int run(Map<String, String> environment, Duration budget, PrintStream output) {
        Thread worker = null;
        try {
            if (budget.isNegative() || budget.isZero() || budget.compareTo(MAXIMUM) > 0) throw new IllegalArgumentException();
            Settings settings = settings(environment);
            // A one-shot migration process emits only finite outcomes, never provider exceptions or SQL.
            LogFactory.setLogCreator(type -> new QuietLog());
            CompletableFuture<Void> completion = new CompletableFuture<>();
            worker = Thread.ofVirtual().name("kb-one-shot-migration").start(() -> {
                try { migrate(settings); completion.complete(null); }
                catch (Exception failure) { completion.completeExceptionally(failure); }
            });
            completion.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            output.println("KB migrations complete");
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            output.println("KB migrations failed; request startup is not admitted");
            return 1;
        } catch (Exception failure) {
            output.println("KB migrations failed; request startup is not admitted");
            return 1;
        } finally {
            if (worker != null) worker.interrupt();
        }
    }

    private static Settings settings(Map<String, String> env) {
        if (env.keySet().stream().anyMatch(key -> key.startsWith("FLYWAY_") || FORBIDDEN.contains(key))) throw new IllegalArgumentException();
        String url = env.get("DKP_KB_MIGRATION_URL"), user = env.get("DKP_KB_MIGRATION_USERNAME"), password = env.get("DKP_KB_MIGRATION_PASSWORD");
        if (url == null || !url.startsWith("jdbc:postgresql://") || !"dkp_kb_migrator".equals(user)
                || password == null || password.isBlank() || password.length() > 1024 || password.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        URI parsed = URI.create(url.substring(5));
        if (parsed.getHost() == null || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null
                || !"/knowledge_base".equals(parsed.getRawPath()) || parsed.getPort() < -1 || parsed.getPort() == 0
                || parsed.getPort() > 65535 || parsed.getRawQuery() != null && !parsed.getRawQuery().equals("currentSchema=kb")) throw new IllegalArgumentException();
        return new Settings(url, user, password);
    }

    private static void migrate(Settings settings) throws Exception {
        verify(settings, false);
        Flyway.configure().dataSource(settings.url(), settings.user(), settings.password()).jdbcProperties(JDBC)
                .defaultSchema("kb").schemas("kb").createSchemas(false).cleanDisabled(true)
                .locations("classpath:db/migration").failOnMissingLocations(true).validateMigrationNaming(true)
                .validateOnMigrate(true).baselineOnMigrate(false).outOfOrder(false).connectRetries(0).lockRetryCount(0)
                .load().migrate();
        verify(settings, true);
    }

    private static void verify(Settings settings, boolean migrated) throws Exception {
        Properties properties = new Properties(); properties.putAll(JDBC);
        properties.setProperty("user", settings.user()); properties.setProperty("password", settings.password());
        try (var connection = DriverManager.getConnection(settings.url(), properties); var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery(PRECONDITIONS)) {
                if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException();
            }
            if (migrated) try (var result = statement.executeQuery(POSTCONDITIONS)) {
                if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException();
            }
        }
    }

    private record Settings(String url, String user, String password) {
        @Override public String toString() { return "KB migration settings"; }
    }

    private static final class QuietLog implements Log {
        public void debug(String message) { }
        public void info(String message) { }
        public void warn(String message) { }
        public void error(String message) { }
        public void error(String message, Exception failure) { }
        public void notice(String message) { }
    }
}
