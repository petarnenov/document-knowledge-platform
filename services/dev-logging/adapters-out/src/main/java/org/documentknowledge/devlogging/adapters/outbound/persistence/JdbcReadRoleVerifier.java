package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.LogFailure;

/** Owned startup SQL; PostgreSQL effective privilege functions include inherited and PUBLIC grants. */
public final class JdbcReadRoleVerifier {
    private final DataSource source;
    public JdbcReadRoleVerifier(DataSource source) { this.source = Objects.requireNonNull(source); }

    public void verify(RequestDeadline deadline) {
        deadline.requireTime();
        try (var owned = new JdbcLogCleanup(source.getConnection())) {
            deadline.requireTime();
            var connection = owned.connection();
            connection.setNetworkTimeout(Runnable::run,
                Math.toIntExact(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos()))));
            try (var statement = connection.prepareStatement("""
                SELECT current_database()='dkp_dev_logs' AND current_user='dkp_log_reader'
                  AND NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole
                  AND NOT r.rolreplication AND NOT r.rolbypassrls
                  AND has_database_privilege(current_user,current_database(),'CONNECT')
                  AND NOT has_database_privilege(current_user,current_database(),'CREATE')
                  AND NOT has_database_privilege(current_user,current_database(),'TEMPORARY')
                  AND has_schema_privilege(current_user,'dev_logs','USAGE')
                  AND NOT has_schema_privilege(current_user,'dev_logs','CREATE')
                  AND NOT has_schema_privilege(current_user,'public','CREATE')
                  AND has_table_privilege(current_user,'dev_logs.events','SELECT')
                  AND NOT has_table_privilege(current_user,'dev_logs.events','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                  AND NOT has_any_column_privilege(current_user,'dev_logs.events','INSERT,UPDATE,REFERENCES')
                  AND NOT EXISTS (SELECT 1 FROM pg_roles other
                      WHERE other.oid<>r.oid AND pg_has_role(current_user,other.oid,'MEMBER'))
                  AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                      WHERE n.nspname='dev_logs' AND c.relkind IN ('r','p','v','m','f') AND c.relname<>'events'
                        AND (has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                          OR has_any_column_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,REFERENCES')))
                  AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                      WHERE n.nspname='dev_logs' AND c.relkind='S'
                        AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE'))
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
