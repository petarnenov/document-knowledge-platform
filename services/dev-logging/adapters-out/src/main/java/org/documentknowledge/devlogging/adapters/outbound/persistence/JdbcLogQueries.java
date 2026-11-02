package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.documentknowledge.devlogging.application.LogQueries;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.Severity;

/** Logging-owned SQL only; use a SELECT-only database credential supplied by bootstrap. */
public final class JdbcLogQueries implements LogQueries.Store {
    private final DataSource dataSource;

    public JdbcLogQueries(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); }

    @Override
    public List<LogQueries.Row> read(LogReader reader, LogQuery query, LogQueries.Position after, RequestDeadline deadline) {
        deadline.requireTime();
        try (var connection = dataSource.getConnection()) {
            network(connection, deadline);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                limit(connection, deadline);
                network(connection, deadline);
                try (var statement = connection.prepareStatement("SELECT clock_timestamp()" ); var rows = statement.executeQuery()) {
                    rows.next(); reader.requireActive(rows.getTimestamp(1).toInstant());
                }
                var sql = new StringBuilder("""
                    SELECT producer_id,scope_id,event_id,received_at,sequence,scope_kind,
                           actor_issuer,actor_subject,tenant_id,canonical_payload
                    FROM dev_logs.events WHERE canonical_payload IS NOT NULL
                      AND retain_until > clock_timestamp() AND received_at >= ? AND received_at < ?
                    """);
                var arguments = new ArrayList<Object>();
                arguments.add(Timestamp.from(query.from())); arguments.add(Timestamp.from(query.to()));
                if (!reader.operator()) {
                    sql.append(" AND scope_kind='TENANT' AND actor_issuer=? AND tenant_id=?");
                    arguments.add(reader.issuer()); arguments.add(reader.tenantId());
                }
                filter(sql, arguments, "build_id", query.buildId());
                filter(sql, arguments, "run_id", query.runId());
                filter(sql, arguments, "component", query.component());
                filter(sql, arguments, "correlation_id", query.correlationId());
                filter(sql, arguments, "trace_id", query.traceId());
                if (query.minSeverity() != null) {
                    sql.append(" AND severity IN (");
                    for (int index = query.minSeverity().ordinal(); index < Severity.values().length; index++) {
                        if (index > query.minSeverity().ordinal()) sql.append(',');
                        sql.append('?'); arguments.add(Severity.values()[index].name());
                    }
                    sql.append(')');
                }
                if (after != null) {
                    sql.append(" AND (received_at,sequence) < (?,?)");
                    arguments.add(Timestamp.from(after.receivedAt())); arguments.add(after.sequence());
                }
                sql.append(" ORDER BY received_at DESC,sequence DESC LIMIT ?"); arguments.add(query.limit() + 1);
                limit(connection, deadline);
                network(connection, deadline);
                var result = new ArrayList<LogQueries.Row>();
                try (var statement = connection.prepareStatement(sql.toString())) {
                    for (int index = 0; index < arguments.size(); index++) statement.setObject(index + 1, arguments.get(index));
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            deadline.requireTime();
                            var scope = new LogScope(rows.getObject("scope_id", UUID.class),
                                LogScope.Kind.valueOf(rows.getString("scope_kind")), rows.getString("actor_issuer"),
                                rows.getString("actor_subject"), rows.getString("tenant_id"));
                            var event = new LogQueries.Stored(rows.getObject("producer_id", UUID.class), scope,
                                rows.getObject("event_id", UUID.class), rows.getTimestamp("received_at").toInstant(),
                                rows.getBytes("canonical_payload"));
                            result.add(new LogQueries.Row(event, rows.getLong("sequence")));
                        }
                    }
                }
                deadline.requireTime();
                connection.rollback(); // Read-only transaction, never an ingestion/registration commit.
                return List.copyOf(result);
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { /* Fixed safe error below. */ }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new LogFailure(LogFailure.Kind.UNAVAILABLE);
        }
    }

    private static void filter(StringBuilder sql, List<Object> arguments, String column, Object value) {
        if (value != null) { sql.append(" AND ").append(column).append("=?"); arguments.add(value); }
    }

    private static void limit(Connection connection, RequestDeadline deadline) throws SQLException {
        network(connection, deadline);
        deadline.requireTime();
        long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos()));
        try (var statement = connection.prepareStatement("SELECT set_config('statement_timeout',?,true),set_config('lock_timeout',?,true)")) {
            statement.setString(1, millis + "ms"); statement.setString(2, millis + "ms"); statement.execute();
        }
    }

    private static void network(Connection connection, RequestDeadline deadline) throws SQLException {
        deadline.requireTime();
        connection.setNetworkTimeout(Runnable::run,
            Math.toIntExact(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline.remainingNanos()))));
    }
}
