package org.documentknowledge.documentmanagement.adapters.outbound.sharing;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.sharing.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
class JdbcDocumentShareStoreTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("dm").withUsername("postgres").withPassword(UUID.randomUUID().toString())
        .withCommand("postgres","-c","fsync=on","-c","synchronous_commit=on","-c","full_page_writes=on");
    static final String MIGRATOR_PASSWORD=UUID.randomUUID().toString(), RUNTIME_PASSWORD=UUID.randomUUID().toString();
    static final Principal ALICE=new Principal("https://issuer.example","alice");
    static final Instant NOW=Instant.parse("2026-10-31T12:00:00Z");
    static PGSimpleDataSource admin,runtime,migrator;
    static String jdbcUrl;
    static org.documentknowledge.documentmanagement.adapters.outbound.persistence.JdbcDocumentStore store;
    static Flyway flyway;
    static PGSimpleDataSource source(String user,String password) {
        PGSimpleDataSource result=new PGSimpleDataSource(); result.setURL(jdbcUrl==null?POSTGRES.getJdbcUrl():jdbcUrl);result.setUser(user);result.setPassword(password);return result;
    }
    @BeforeAll static void setup() throws Exception {
        POSTGRES.start(); jdbcUrl=POSTGRES.getJdbcUrl(); admin=source(POSTGRES.getUsername(),POSTGRES.getPassword());
        try(Connection c=admin.getConnection();Statement s=c.createStatement()) {
            s.execute("CREATE ROLE dkp_dm_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+MIGRATOR_PASSWORD+"'");
            s.execute("CREATE ROLE dkp_dm_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+RUNTIME_PASSWORD+"'");
            s.execute("REVOKE ALL ON DATABASE dm FROM PUBLIC");
            s.execute("REVOKE CONNECT ON DATABASE postgres,template1 FROM PUBLIC");
            s.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            s.execute("GRANT CONNECT,CREATE ON DATABASE dm TO dkp_dm_migrator");
            s.execute("GRANT CONNECT ON DATABASE dm TO dkp_dm_runtime");
            s.execute("ALTER ROLE dkp_dm_runtime SET search_path=dm");
        }
        migrator=source("dkp_dm_migrator",MIGRATOR_PASSWORD);runtime=source("dkp_dm_runtime",RUNTIME_PASSWORD);
        flyway=Flyway.configure().dataSource(migrator).defaultSchema("dm").schemas("dm").locations("classpath:db/migration").load();
        assertEquals(2,flyway.migrate().migrationsExecuted);
        store=new org.documentknowledge.documentmanagement.adapters.outbound.persistence.JdbcDocumentStore(runtime);
    }
    @AfterAll static void cleanup() { POSTGRES.stop(); }

    JdbcDocumentShareStore shares(){return new JdbcDocumentShareStore(runtime);}
    DocumentAggregate create(String tenant){var d=new DocumentAggregate(UUID.randomUUID(),tenant,ALICE,NOW,4,new Metadata("private",""),Set.of(new AclRule(ALICE,Action.SHARE_TO_COLLECTION,AclRule.Effect.ALLOW)),List.of(),List.of());store.execute(r->{r.insert(d);return null;});return d;}
    @Test void readsPersistedMetadataScopedAndCompleteWithoutHistory(){var a=create("share-a");var b=create("share-b");var result=shares().locked("share-a",List.of(b.documentId(),a.documentId()),SharingDeadline.request(),List::copyOf);assertEquals(1,result.size());var projected=result.getFirst();assertEquals(a.documentId(),projected.documentId());assertEquals(4,projected.revision());assertEquals("development-restricted",projected.classification());assertEquals(1,projected.classificationPolicyRevision());assertEquals(a.rules(),projected.rules());assertTrue(shares().<Boolean>locked("missing",List.of(a.documentId()),SharingDeadline.request(),List::isEmpty));}
    @Test void maximumHundredSortedLocksWithoutCrossTenant(){List<UUID> ids=new ArrayList<>();for(int i=0;i<100;i++)ids.add(create("share-hundred").documentId());Collections.reverse(ids);var result=shares().locked("share-hundred",ids,SharingDeadline.request(),List::copyOf);assertEquals(100,result.size());assertEquals(ids.stream().sorted(Comparator.comparing(UUID::toString)).toList(),result.stream().map(org.documentknowledge.documentmanagement.application.sharing.SharingModels.Document::documentId).toList());}
    @Test void owningAggregateWriterBlocksUntilFinalAuthorityCallbackFinishes()throws Exception{var d=create("share-lock");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);try(var executor=Executors.newFixedThreadPool(2)){var assessment=executor.submit(()->shares().locked(d.tenant(),List.of(d.documentId()),SharingDeadline.request(),rows->{entered.countDown();try{assertTrue(release.await(300,TimeUnit.MILLISECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return rows.getFirst();}));assertTrue(entered.await(2,TimeUnit.SECONDS));var write=executor.submit(()->store.execute(r->{var current=r.find(d.tenant(),d.documentId()).orElseThrow();r.update(current.next(current.metadata(),Set.of(),current.holds(),current.versions()),current.revision());return true;}));assertThrows(TimeoutException.class,()->write.get(30,TimeUnit.MILLISECONDS));release.countDown();assertEquals(d.rules(),assessment.get(2,TimeUnit.SECONDS).rules());assertTrue(write.get(2,TimeUnit.SECONDS));assertTrue(shares().<Boolean>locked(d.tenant(),List.of(d.documentId()),SharingDeadline.request(),rows->rows.getFirst().rules().isEmpty()));}}
    @Test void contendedAggregateLockTimesOutBeforeLocalTail()throws Exception{var d=create("share-timeout");try(Connection c=runtime.getConnection();PreparedStatement lock=c.prepareStatement("SELECT document_id FROM dm.documents WHERE tenant_id=? AND document_id=? FOR UPDATE")){c.setAutoCommit(false);lock.setString(1,d.tenant());lock.setObject(2,d.documentId());lock.executeQuery().close();long started=System.nanoTime();var failure=assertThrows(DmFailure.class,()->shares().locked(d.tenant(),List.of(d.documentId()),SharingDeadline.request(),rows->{throw new AssertionError("No late callback");}));assertEquals(503,failure.status());assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<900);c.rollback();}assertEquals(1,shares().<Integer>locked(d.tenant(),List.of(d.documentId()),SharingDeadline.request(),List::size));}
    @Test void cancellationAndApplicationFailureRollbackReleaseLocks(){var d=create("share-cancel");SharingDeadline deadline=SharingDeadline.request();assertEquals(503,assertThrows(DmFailure.class,()->shares().locked(d.tenant(),List.of(d.documentId()),deadline,rows->{deadline.cancel();return rows;})).status());assertEquals(404,assertThrows(DmFailure.class,()->shares().locked(d.tenant(),List.of(d.documentId()),SharingDeadline.request(),rows->{throw new DmFailure(404,"NOT_FOUND");})).status());assertEquals(503,assertThrows(DmFailure.class,()->shares().locked(d.tenant(),List.of(d.documentId()),SharingDeadline.request(),rows->{throw new IllegalStateException("Never exposed");})).status());assertEquals(4,store.execute(r->r.find(d.tenant(),d.documentId()).orElseThrow()).revision());}
    @Test void cancelledRequestAndInvalidSetCannotAcquire(){SharingDeadline deadline=SharingDeadline.request();deadline.cancel();assertEquals(503,assertThrows(DmFailure.class,()->shares().locked("x",List.of(UUID.randomUUID()),deadline,List::size)).status());for(List<UUID> ids:List.of(List.<UUID>of(),Collections.nCopies(101,UUID.randomUUID()),List.of(ALICE_ID,ALICE_ID)))assertEquals(503,assertThrows(DmFailure.class,()->shares().locked("x",ids,SharingDeadline.request(),List::size)).status());}
    static final UUID ALICE_ID=UUID.randomUUID();
    @Test void cannotMutateAnAuditOrCommandByProjection()throws Exception{var d=create("share-facts");shares().locked(d.tenant(),List.of(d.documentId()),SharingDeadline.request(),List::size);try(Connection c=runtime.getConnection();PreparedStatement query=c.prepareStatement("SELECT (SELECT count(*) FROM dm.audit_facts WHERE tenant_id=?)+(SELECT count(*) FROM dm.command_outcomes WHERE tenant_id=?)")){query.setString(1,d.tenant());query.setString(2,d.tenant());try(ResultSet rows=query.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getLong(1));}}}
}
