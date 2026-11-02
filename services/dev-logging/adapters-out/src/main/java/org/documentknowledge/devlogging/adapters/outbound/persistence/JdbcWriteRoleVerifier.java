package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.LogFailure;

/** Exact effective logging DML privileges, including column grants required for row locks. */
public final class JdbcWriteRoleVerifier {
    private final DataSource source;
    public JdbcWriteRoleVerifier(DataSource source) { this.source = Objects.requireNonNull(source); }

    public void verify(RequestDeadline deadline) {
        deadline.requireTime();
        try (var owned = new JdbcLogCleanup(source.getConnection())) {
            deadline.requireTime();
            var connection = owned.connection();
            connection.setNetworkTimeout(Runnable::run,
                Math.toIntExact(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos()))));
            try (var statement = connection.prepareStatement("""
                WITH tables AS (
                    SELECT c.*,n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='dev_logs' AND c.relkind IN ('r','p','v','m','f')
                ), privileges(privilege) AS (
                    VALUES ('SELECT'),('INSERT'),('UPDATE'),('DELETE'),('TRUNCATE'),('REFERENCES'),('TRIGGER'),('MAINTAIN')
                ), column_privileges(privilege) AS (VALUES ('SELECT'),('INSERT'),('UPDATE'),('REFERENCES'))
                SELECT current_database()='dkp_dev_logs' AND current_user='dkp_log_writer'
                  AND NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolinherit
                  AND NOT r.rolreplication AND NOT r.rolbypassrls
                  AND has_database_privilege(current_user,current_database(),'CONNECT')
                  AND NOT has_database_privilege(current_user,current_database(),'CONNECT WITH GRANT OPTION')
                  AND NOT has_database_privilege(current_user,current_database(),'CREATE,TEMPORARY')
                  AND has_schema_privilege(current_user,'dev_logs','USAGE')
                  AND NOT has_schema_privilege(current_user,'dev_logs','USAGE WITH GRANT OPTION')
                  AND NOT has_schema_privilege(current_user,'dev_logs','CREATE')
                  AND NOT has_schema_privilege(current_user,'public','CREATE')
                  AND NOT EXISTS (SELECT 1 FROM pg_roles other WHERE other.oid<>r.oid
                      AND pg_has_role(current_user,other.oid,'MEMBER'))
                  AND NOT EXISTS (SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba=r.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='dev_logs' AND nspowner=r.oid)
                  AND (SELECT count(*) FROM tables WHERE relname IN ('capacity','manifests','producers','scopes','events')
                      AND relkind='r')=5
                  AND NOT EXISTS (SELECT 1 FROM tables c CROSS JOIN privileges p WHERE
                      c.relowner=r.oid OR
                      has_table_privilege(current_user,c.oid,p.privilege) <>
                        ((p.privilege='SELECT' AND c.relname IN ('capacity','manifests','producers','scopes','events'))
                          OR (p.privilege='INSERT' AND c.relname IN ('producers','scopes','events')))
                      OR has_table_privilege(current_user,c.oid,p.privilege || ' WITH GRANT OPTION'))
                  AND NOT EXISTS (SELECT 1 FROM tables c JOIN pg_attribute a ON a.attrelid=c.oid
                      CROSS JOIN column_privileges p WHERE a.attnum>0 AND NOT a.attisdropped AND (
                      has_column_privilege(current_user,c.oid,a.attnum,p.privilege) <>
                        ((p.privilege='SELECT' AND c.relname IN ('capacity','manifests','producers','scopes','events'))
                          OR (p.privilege='INSERT' AND c.relname IN ('producers','scopes','events'))
                          OR (p.privilege='UPDATE' AND ((c.relname IN ('capacity','scopes') AND a.attname='reserved_bytes')
                            OR (c.relname='producers' AND a.attname='revoked'))))
                      OR has_column_privilege(current_user,c.oid,a.attnum,p.privilege || ' WITH GRANT OPTION')))
                  AND EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                      WHERE n.nspname='dev_logs' AND c.relkind='S' AND c.relname='events_sequence_seq'
                        AND has_sequence_privilege(current_user,c.oid,'USAGE'))
                  AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                      WHERE n.nspname='dev_logs' AND c.relkind='S' AND (
                        c.relowner=r.oid OR has_sequence_privilege(current_user,c.oid,'SELECT,UPDATE')
                        OR (c.relname<>'events_sequence_seq' AND has_sequence_privilege(current_user,c.oid,'USAGE'))
                        OR has_sequence_privilege(current_user,c.oid,'USAGE WITH GRANT OPTION')))
                FROM pg_roles r WHERE r.rolname=current_user
                """)) {
                statement.setQueryTimeout(Math.max(1, (int) TimeUnit.NANOSECONDS.toSeconds(deadline.remainingNanos())));
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1)) throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
                }
            }
            deadline.requireTime();
        } catch (SQLException failure) { throw new LogFailure(LogFailure.Kind.UNAVAILABLE); }
    }
}
