package org.documentknowledge.knowledgebase.adapters.outbound.persistence;

import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol-only failure tests: no provider, SQL-state or successful persistence is fabricated. */
class JdbcDeadlineTest {
    @Test void cancelledAndExpiredRequestsNeverBorrowConnection(){
        var source=new ProtocolSource();var store=new JdbcCollectionStore(source);var ticks=new AtomicLong();
        var cancelled=new Deadline(ticks::get,Duration.ofSeconds(8));cancelled.cancel();
        assertThrows(KbFailure.class,()->store.read(cancelled,r->null));
        var expired=new Deadline(ticks::get,Duration.ofSeconds(8));ticks.set(Duration.ofSeconds(8).toNanos());
        assertThrows(KbFailure.class,()->store.read(expired,r->null));assertEquals(0,source.borrows);
    }
    @Test void readUsesCoherentReadOnlySnapshotAndRemainingNetworkBudget(){
        var source=new ProtocolSource();var store=new JdbcCollectionStore(source);
        assertEquals("result",store.read(Deadline.request(),r->"result"));
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ,source.isolation);assertTrue(source.readOnly);
        assertEquals(1,source.commits);assertEquals(0,source.rollbacks);assertTrue(source.closed);assertTrue(source.networkTimeout>0&&source.networkTimeout<=500);
    }
    @Test void callbackCancellationRollsBackAndCannotCommitLate(){
        var source=new ProtocolSource();var store=new JdbcCollectionStore(source);var request=Deadline.request();
        assertEquals(503,assertThrows(KbFailure.class,()->store.read(request,r->{request.cancel();return "late";})).status());
        // Child deadlines share the parent's ownership; cancellation must reach the actual transaction.
        assertEquals(0,source.commits);assertEquals(1,source.rollbacks);
    }
    @Test void elapsedTransactionIsRejectedBeforeCommit(){
        var ticks=new AtomicLong();var request=new Deadline(ticks::get,Duration.ofSeconds(8));var source=new ProtocolSource();var store=new JdbcCollectionStore(source);
        assertThrows(KbFailure.class,()->store.read(request,r->{ticks.set(Duration.ofMillis(500).toNanos());return "late";}));
        assertEquals(0,source.commits);assertEquals(1,source.rollbacks);
    }
    @Test void unknownCommitIsSafeUnavailableEvenWhenRollbackAlsoFails(){
        var source=new ProtocolSource();source.commitFailure=true;source.rollbackFailure=true;
        var error=assertThrows(KbFailure.class,()->new JdbcCollectionStore(source).read(Deadline.request(),r->"staged"));
        assertEquals("UNAVAILABLE",error.code());assertEquals(503,error.status());assertEquals(1,source.commits);assertEquals(1,source.rollbacks);assertTrue(source.closed);
        assertFalse(error.getMessage().contains("private"));
    }
    @Test void acknowledgmentAfterDeadlineWithholdsSuccessWithoutPretendingCommitAbsent(){
        var ticks=new AtomicLong();var request=new Deadline(ticks::get,Duration.ofSeconds(8));var source=new ProtocolSource();
        source.onCommit=()->ticks.set(Duration.ofMillis(501).toNanos());
        assertEquals(503,assertThrows(KbFailure.class,()->new JdbcCollectionStore(source).read(request,r->"staged")).status());
        assertEquals(1,source.commits);assertTrue(source.closed);
    }
    @Test void acquisitionAndNonDomainFailureAreSanitized(){
        var source=new ProtocolSource();source.acquisitionFailure=true;
        assertEquals("UNAVAILABLE",assertThrows(KbFailure.class,()->new JdbcCollectionStore(source).read(Deadline.request(),r->null)).code());
        source.acquisitionFailure=false;
        assertEquals("UNAVAILABLE",assertThrows(KbFailure.class,()->new JdbcCollectionStore(source).read(Deadline.request(),r->{throw new IllegalStateException("private");})).code());
        assertEquals(1,source.rollbacks);
    }
    @Test void readonlyRepositoryRefusesWritesAndPreservesSafeDomainFailure(){
        var source=new ProtocolSource();var store=new JdbcCollectionStore(source);
        assertEquals(503,assertThrows(KbFailure.class,()->store.read(Deadline.request(),r->{r.advanceTenantFence("tenant",0);return null;})).status());
        assertEquals(404,assertThrows(KbFailure.class,()->store.read(Deadline.request(),r->{throw KbFailure.hidden();})).status());
    }
    @Test void oversizedOrCrossTenantLockPlansFailBeforeSqlOrCallback(){
        var source=new ProtocolSource();var store=new JdbcCollectionStore(source);var ids=new ArrayList<UUID>();for(int i=0;i<101;i++)ids.add(new UUID(0,i));
        var key=new CommandKey(new Context(new Principal("https://issuer.example","actor"),"foreign"),"operation","resource","key");
        for(var plan:List.of(new KBUnitOfWork.LockPlan("selected",ids,List.of(),null),new KBUnitOfWork.LockPlan("selected",List.of(),ids,null),new KBUnitOfWork.LockPlan("selected",List.of(),List.of(),key))){
            assertEquals(503,assertThrows(KbFailure.class,()->store.execute(plan,Deadline.request(),r->{fail("Invalid lock plan cannot enter callback");return null;})).status());
        }
        assertEquals(Connection.TRANSACTION_READ_COMMITTED,source.isolation);assertFalse(source.readOnly);assertEquals(0,source.commits);assertEquals(3,source.rollbacks);
    }
    static final class ProtocolSource extends PGSimpleDataSource {
        int borrows,commits,rollbacks,isolation,networkTimeout;boolean readOnly,closed,commitFailure,rollbackFailure,acquisitionFailure;Runnable onCommit=()->{};
        @Override public Connection getConnection()throws SQLException{
            borrows++;if(acquisitionFailure)throw new SQLException("private credentials");
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->switch(m.getName()){
                case "setTransactionIsolation"->{isolation=(int)a[0];yield null;}
                case "setReadOnly"->{readOnly=(boolean)a[0];yield null;}
                case "setNetworkTimeout"->{networkTimeout=(int)a[1];yield null;}
                case "prepareStatement"->Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(q,n,b)->defaultValue(n.getReturnType()));
                case "commit"->{commits++;onCommit.run();if(commitFailure)throw new SQLException("private commit");yield null;}
                case "rollback"->{rollbacks++;if(rollbackFailure)throw new SQLException("private rollback");yield null;}
                case "close"->{closed=true;yield null;}
                default->defaultValue(m.getReturnType());
            });
        }
        static Object defaultValue(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    }
}
