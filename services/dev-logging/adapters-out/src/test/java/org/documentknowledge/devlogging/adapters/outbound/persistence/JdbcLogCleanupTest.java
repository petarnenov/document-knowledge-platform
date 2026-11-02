package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcLogCleanupTest {
    final AtomicLong nanos = new AtomicLong();
    final List<String> calls = new ArrayList<>();
    String failAt;
    boolean consumeTail;
    Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
            calls.add(method.getName());
            if (method.getName().equals("setNetworkTimeout")) assertTrue((Integer) arguments[1] > 0 && (Integer) arguments[1] <= 2000);
            if (method.getName().equals(failAt)) throw new SQLException("PRIVATE_SQL_CONFIGURATION");
            if (method.getName().equals("rollback") && consumeTail) nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
            return null;
        });
    }
    @Test void normalTransactionRollsBackAndClosesWithinOneTailWithoutRetry() {
        var connection = connection(); var cleanup = new JdbcLogCleanup(connection, nanos::get);
        assertSame(connection, cleanup.connection()); cleanup.transactionStarted(); cleanup.close(); cleanup.close();
        assertEquals(List.of("setNetworkTimeout", "rollback", "setNetworkTimeout", "close"), calls);
    }
    @Test void confirmedCommitDoesNotStartAnotherTransaction() {
        var cleanup = new JdbcLogCleanup(connection(), nanos::get); cleanup.transactionStarted(); cleanup.transactionCompleted(); cleanup.close();
        assertEquals(List.of("setNetworkTimeout", "close"), calls);
    }
    @Test void rollbackAndCloseFailuresAreSanitizedAndCannotBecomePositiveAcknowledgements() {
        for (String operation : List.of("rollback", "close", "setNetworkTimeout")) {
            calls.clear(); failAt = operation;
            var cleanup = new JdbcLogCleanup(connection(), nanos::get); cleanup.transactionStarted();
            var failure = assertThrows(LogFailure.class, cleanup::close);
            assertEquals(LogFailure.Kind.UNAVAILABLE, failure.kind()); assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("PRIVATE"));
            int attempts = calls.size(); cleanup.close(); assertEquals(attempts, calls.size());
        }
    }
    @Test void exhaustedTailCannotStartAnotherCleanupCallOrMintAnotherBudget() {
        consumeTail = true; var cleanup = new JdbcLogCleanup(connection(), nanos::get); cleanup.transactionStarted();
        assertThrows(LogFailure.class, cleanup::close);
        assertEquals(List.of("setNetworkTimeout", "rollback"), calls);
        cleanup.close(); assertEquals(2, calls.size());
    }
}
