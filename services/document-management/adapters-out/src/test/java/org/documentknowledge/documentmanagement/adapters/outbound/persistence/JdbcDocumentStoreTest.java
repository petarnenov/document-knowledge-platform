package org.documentknowledge.documentmanagement.adapters.outbound.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

class JdbcDocumentStoreTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("dm").withUsername("postgres").withPassword(UUID.randomUUID().toString())
        .withCommand("postgres","-c","fsync=on","-c","synchronous_commit=on","-c","full_page_writes=on");
    static final String MIGRATOR_PASSWORD=UUID.randomUUID().toString(), RUNTIME_PASSWORD=UUID.randomUUID().toString();
    static final Principal ALICE=new Principal("https://issuer.example","alice");
    static final Instant NOW=Instant.parse("2026-10-31T12:00:00Z");
    static PGSimpleDataSource admin,runtime,migrator;
    static String jdbcUrl;
    static JdbcDocumentStore store;
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
        store=new JdbcDocumentStore(runtime);
    }
    @AfterAll static void cleanup() { POSTGRES.stop(); }
    DocumentAggregate document(String tenant,Set<AclRule> rules) {return new DocumentAggregate(UUID.randomUUID(),tenant,ALICE,NOW,0,new Metadata("title","description"),rules,List.of(),List.of());}
    Set<AclRule> visible() {return Set.of(new AclRule(ALICE,Action.INSPECT_DRAFT,AclRule.Effect.ALLOW));}
    DocumentAggregate create(String tenant) {var d=document(tenant,visible());store.execute(r->{r.insert(d);return null;});return d;}
    CommandKey key(String tenant,UUID id,String key) {return new CommandKey(new Actor(ALICE.issuer(),ALICE.subject()),tenant,"document.update-metadata",id.toString(),key);}
    StoredCommand outcome(CommandKey key,UUID id,long revision) {return new StoredCommand(key,"a".repeat(64),200,new CommandReceipt(id,revision));}
    AuditFact audit(DocumentAggregate d) {return new AuditFact(UUID.randomUUID(),d.documentId(),d.tenant(),new Actor(ALICE.issuer(),ALICE.subject()),"document.update-metadata",d.revision(),NOW);}
    long count(String table,UUID id) throws SQLException {try(Connection c=admin.getConnection();PreparedStatement s=c.prepareStatement("SELECT count(*) FROM dm."+table+" WHERE document_id=?")){s.setObject(1,id);try(ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}}}
    @Test void migrationsAndRuntimePrivileges() throws Exception {
        assertEquals(0,flyway.migrate().migrationsExecuted);flyway.validate();
        try(Connection c=runtime.getConnection();Statement s=c.createStatement()) {
            try(ResultSet r=s.executeQuery("SHOW fsync")){assertTrue(r.next());assertEquals("on",r.getString(1));}
            try(ResultSet r=s.executeQuery("SHOW search_path")){assertTrue(r.next());assertEquals("dm",r.getString(1));}
            for(String forbidden:List.of("CREATE TABLE dm.bad(id int)","CREATE TABLE public.bad(id int)","CREATE SCHEMA sibling","CREATE DATABASE sibling","ALTER TABLE dm.documents ADD bad int","DROP TABLE dm.documents","UPDATE dm.versions SET producer='changed'","DELETE FROM dm.versions","UPDATE dm.audit_facts SET operation='changed'","DELETE FROM dm.audit_facts","DELETE FROM dm.flyway_schema_history","SET ROLE dkp_dm_migrator")) assertThrows(SQLException.class,()->s.execute(forbidden),forbidden);
            try(ResultSet r=s.executeQuery("SELECT rolsuper,rolcreatedb,rolcreaterole,rolinherit FROM pg_roles WHERE rolname=current_user")){assertTrue(r.next());for(int i=1;i<=4;i++)assertFalse(r.getBoolean(i));}
        }
        try(Connection c=admin.getConnection();Statement s=c.createStatement()) {
            s.executeUpdate("UPDATE dm.flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
            try {assertThrows(org.flywaydb.core.api.FlywayException.class,flyway::validate);} finally {flyway.repair();}
            flyway.validate();
        }
        PGSimpleDataSource sibling=source("dkp_dm_runtime",RUNTIME_PASSWORD);sibling.setDatabaseName("postgres");sibling.setURL(jdbcUrl.replace("/dm","/postgres"));assertThrows(SQLException.class,sibling::getConnection);
    }
    @Test void aggregateRoundtripCasAndImmutableChildren() {
        var d=create("roundtrip");
        var hold=new LegalHold(UUID.randomUUID(),"preserve",ALICE,NOW);
        var version=new DocumentVersion(UUID.randomUUID(),d.documentId(),1,"b".repeat(64),"text/plain","trusted/receipt","test-authority",ALICE,NOW);
        var updated=d.next(new Metadata("updated",""),visible(),List.of(hold),List.of(version));
        store.execute(r->{r.update(updated,0);return null;});
        assertEquals(updated,store.execute(r->r.find(d.tenant(),d.documentId()).orElseThrow()));
        assertTrue(store.<Boolean>execute(r->r.find("other",d.documentId()).isEmpty()));
        assertEquals(409,assertThrows(DmFailure.class,()->store.execute(r->{r.update(updated,0);return null;})).status());
        assertEquals(404,assertThrows(DmFailure.class,()->store.execute(r->{r.update(document("missing",visible()),0);return null;})).status());
        assertThrows(IllegalArgumentException.class,()->store.execute(r->{r.update(updated.next(updated.metadata(),visible(),List.of(),List.of()),1);return null;}));
        // An exact semantic no-op remains a revisioned command.
        store.execute(r->{r.update(updated.next(updated.metadata(),updated.rules(),updated.holds(),updated.versions()),1);return null;});
        assertEquals(2,store.execute(r->r.find(d.tenant(),d.documentId()).orElseThrow()).revision());
    }
    @Test void sqlAclFilteringOccursBeforeLimitAndCursor() {
        String tenant="page";var hidden=document(tenant,Set.of());var denied=document(tenant,Set.of(new AclRule(ALICE,Action.INSPECT_DRAFT,AclRule.Effect.ALLOW),new AclRule(ALICE,Action.INSPECT_DRAFT,AclRule.Effect.DENY)));
        var first=document(tenant,visible());var second=document(tenant,visible());
        store.execute(r->{for(var d:List.of(hidden,denied,first,second,document("other-page",visible())))r.insert(d);return null;});
        var page=store.execute(r->r.listVisible(tenant,ALICE,Action.INSPECT_DRAFT,null,1));assertEquals(1,page.size());
        var next=store.execute(r->r.listVisible(tenant,ALICE,Action.INSPECT_DRAFT,new PagePosition(page.getFirst().createdAt(),page.getFirst().documentId()),1));assertEquals(1,next.size());assertNotEquals(page.getFirst().documentId(),next.getFirst().documentId());
        assertTrue(store.execute(r->r.listVisible(tenant,ALICE,Action.INSPECT_DRAFT,new PagePosition(next.getFirst().createdAt(),next.getFirst().documentId()),1)).isEmpty());
        assertTrue(store.execute(r->r.listVisible(tenant,ALICE,Action.READ,null,50)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->store.execute(r->r.listVisible(tenant,ALICE,Action.INSPECT_DRAFT,null,0)));
        assertThrows(IllegalArgumentException.class,()->store.execute(r->r.listVisible(tenant,ALICE,Action.INSPECT_DRAFT,null,51)));
    }
    @Test void rollbackAfterAggregateAndAuditWritesLeavesNoPartialOutcome() throws Exception {
        var d=create("rollback");var key=key(d.tenant(),d.documentId(),"failpoint");
        assertThrows(IllegalStateException.class,()->store.execute(r->{r.lockCommand(key);var updated=d.next(new Metadata("not committed",""),visible(),List.of(),List.of());r.update(updated,0);r.record(outcome(key,d.documentId(),1),audit(updated));throw new IllegalStateException("injected before commit");}));
        assertEquals(d,store.execute(r->r.find(d.tenant(),d.documentId()).orElseThrow()));assertTrue(store.execute(r->r.command(key)).isEmpty());assertEquals(0,count("audit_facts",d.documentId()));
        assertThrows(IllegalStateException.class,()->store.execute(r->{r.insert(document("rollback-create",visible()));throw new IllegalStateException("injected after create");}));
    }
    @Test void sameKeyConcurrencyProducesExactlyOneOutcome() throws Exception {
        var d=create("same-key");var key=key(d.tenant(),d.documentId(),"same");
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<StoredCommand> work=()->store.execute(r->{r.lockCommand(key);var existing=r.command(key);if(existing.isPresent())return existing.get();var current=r.find(d.tenant(),d.documentId()).orElseThrow();var next=current.next(current.metadata(),current.rules(),current.holds(),current.versions());r.update(next,0);var result=outcome(key,d.documentId(),1);r.record(result,audit(next));return result;});
            var results=executor.invokeAll(List.of(work,work));assertEquals(results.get(0).get(),results.get(1).get());
        }
        assertEquals(1,count("command_outcomes",d.documentId()));assertEquals(1,count("audit_facts",d.documentId()));assertEquals(1,store.execute(r->r.find(d.tenant(),d.documentId()).orElseThrow()).revision());
    }
    @Test void distinctKeysSameExpectedRevisionHaveOneWinner() throws Exception {
        var d=create("cas");
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> work=()->{try{return store.execute(r->{var current=r.find(d.tenant(),d.documentId()).orElseThrow();r.update(current.next(current.metadata(),current.rules(),current.holds(),current.versions()),0);var key=key(d.tenant(),d.documentId(),UUID.randomUUID().toString());r.record(outcome(key,d.documentId(),1),audit(current.next(current.metadata(),current.rules(),current.holds(),current.versions())));return 200;});}catch(DmFailure f){return f.status();}};
            var futures=executor.invokeAll(List.of(work,work));assertEquals(Set.of(200,409),Set.of(futures.get(0).get(),futures.get(1).get()));
        }
        assertEquals(1,count("audit_facts",d.documentId()));assertEquals(1,count("command_outcomes",d.documentId()));
    }
    @Test void relationalConstraintsRejectCrossDocumentPointerAndDuplicateOrdinal() throws Exception {
        var d=create("constraints");var other=create("constraints");var version=new DocumentVersion(UUID.randomUUID(),d.documentId(),1,"c".repeat(64),"text/plain","receipt","producer",ALICE,NOW);
        store.execute(r->{r.update(d.next(d.metadata(),visible(),List.of(),List.of(version)),0);return null;});
        try(Connection c=admin.getConnection();PreparedStatement s=c.prepareStatement("UPDATE dm.documents SET active_version_id=? WHERE tenant_id=? AND document_id=?")){s.setObject(1,version.versionId());s.setString(2,other.tenant());s.setObject(3,other.documentId());assertThrows(SQLException.class,s::executeUpdate);}
        try(Connection c=runtime.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO dm.versions SELECT tenant_id,document_id,?,version_number,sha256,mime_type,artifact_reference,producer,creator_issuer,creator_subject,created_at,classification,review_state FROM dm.versions WHERE tenant_id=? AND document_id=?")){s.setObject(1,UUID.randomUUID());s.setString(2,d.tenant());s.setObject(3,d.documentId());assertThrows(SQLException.class,s::executeUpdate);}
        try(Connection c=runtime.getConnection();Statement s=c.createStatement()){assertThrows(SQLException.class,()->s.execute("INSERT INTO dm.retention VALUES ('wrong', '"+d.documentId()+"','development-unresolved',1,'UNRESOLVED',NULL,false)"));}
    }
    @Test void appendPreservesExistingActivePointerAndSubmittedClassification() {
        var seed=document("active-pointer",visible());
        var first=new DocumentVersion(UUID.randomUUID(),seed.documentId(),1,"d".repeat(64),"text/plain","receipt/first","trusted",ALICE,NOW,"development-restricted");
        var current=new DocumentAggregate(seed.documentId(),seed.tenant(),seed.creator(),seed.createdAt(),0,seed.metadata(),first.versionId(),visible(),List.of(),List.of(first));
        store.execute(r->{r.insert(current);return null;});
        var second=new DocumentVersion(UUID.randomUUID(),seed.documentId(),2,"e".repeat(64),"text/plain","receipt/second","trusted",ALICE,NOW,"development-restricted");
        store.execute(r->{var stored=r.find(current.tenant(),current.documentId()).orElseThrow();r.update(stored.next(stored.metadata(),stored.rules(),stored.holds(),List.of(first,second)),0);return null;});
        var actual=store.execute(r->r.find(current.tenant(),current.documentId()).orElseThrow());
        assertEquals(first.versionId(),actual.activeVersionId());assertEquals(List.of(first,second),actual.versions());assertEquals("UNREVIEWED",actual.versions().getLast().initialReviewState());
        var illegallyChanged=new DocumentAggregate(actual.documentId(),actual.tenant(),actual.creator(),actual.createdAt(),2,actual.metadata(),second.versionId(),actual.rules(),actual.holds(),actual.versions());
        assertThrows(IllegalArgumentException.class,()->store.execute(r->{r.update(illegallyChanged,1);return null;}));
    }
    @Test void realApplicationCommandsPreserveReplayConflictsRevocationAndNoopAudit() throws Exception {
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        var delegation=new DelegationPolicyPort() {
            public AuthorityDecision targetGrant(Actor actor,String tenant,Action action){return AuthorityDecision.ALLOW;}
            public AuthorityDecision ceiling(Actor actor,String tenant,Action action){return AuthorityDecision.ALLOW;}
        };
        var cursors=new DocumentCursorPort() {
            public PagePosition decode(String cursor,Actor actor,String tenant){throw new UnsupportedOperationException();}
            public String encode(PagePosition page,Actor actor,String tenant){throw new UnsupportedOperationException();}
        };
        var service=new DocumentService(store,(actor,tenant,action,resource)->allowed.get()?AuthorityDecision.ALLOW:AuthorityDecision.DENY,delegation,VerifiedArtifactAuthorityPort.unavailable(),cursors,java.time.Clock.fixed(NOW,java.time.ZoneOffset.UTC));
        var caller=new DocumentCaller(new Actor(ALICE.issuer(),ALICE.subject()),"application",()->AuthorityDecision.ALLOW);
        var created=service.create(caller,"create",new Metadata("title","description"));
        assertEquals(created,service.create(caller,"create",new Metadata("title","description")));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(DmFailure.class,()->service.create(caller,"create",new Metadata("different","description"))).code());
        var updated=service.updateMetadata(caller,created.documentId(),0,"metadata",new Metadata("updated","description"));
        assertEquals(updated,service.updateMetadata(caller,created.documentId(),0,"metadata",new Metadata("updated","description")));
        assertEquals(409,assertThrows(DmFailure.class,()->service.updateMetadata(caller,created.documentId(),0,"metadata",new Metadata("conflict","description"))).status());
        assertEquals(409,assertThrows(DmFailure.class,()->service.updateMetadata(caller,created.documentId(),0,"stale",new Metadata("updated","description"))).status());
        // ADD of an existing ALLOW is still a revisioned, audited, idempotent success.
        var noop=new AclChange(AclChange.Mode.DELEGATE,List.of(new AclChange.Entry(AclChange.Operation.ADD_ALLOW,ALICE,Action.UPDATE_METADATA)));
        assertEquals(2,service.changeAcl(caller,created.documentId(),1,"noop",noop).revision());
        var restrict=new AclChange(AclChange.Mode.RESTRICT,List.of(new AclChange.Entry(AclChange.Operation.REMOVE_ALLOW,ALICE,Action.ACL_RESTRICT)));
        assertEquals(3,service.changeAcl(caller,created.documentId(),2,"self-restrict",restrict).revision());
        assertEquals(404,assertThrows(DmFailure.class,()->service.changeAcl(caller,created.documentId(),2,"self-restrict",restrict)).status());
        var hold=service.placeHold(caller,created.documentId(),3,"hold","preserve for review");
        assertEquals(4,hold.revision());assertEquals(hold,service.placeHold(caller,created.documentId(),3,"hold","preserve for review"));
        assertEquals(404,assertThrows(DmFailure.class,()->service.appendVersion(caller,created.documentId(),4,"unavailable-artifact","opaque-receipt")).status());
        allowed.set(false);
        assertEquals(403,assertThrows(DmFailure.class,()->service.updateMetadata(caller,created.documentId(),0,"metadata",new Metadata("updated","description"))).status());
        assertEquals(5,count("command_outcomes",created.documentId()));assertEquals(5,count("audit_facts",created.documentId()));
        assertEquals(1,count("legal_holds",created.documentId()));assertEquals(0,count("versions",created.documentId()));
        assertEquals(4,store.execute(r->r.find(caller.tenant(),created.documentId()).orElseThrow()).revision());
    }
    @Test void databaseFailureIsSafeAndRolledBack() {
        var d=create("sql-error");assertEquals("UNAVAILABLE",assertThrows(DmFailure.class,()->store.execute(r->{r.insert(d);return null;})).code());
        var broken=source("no_such_role",UUID.randomUUID().toString());assertEquals(503,assertThrows(DmFailure.class,()->new JdbcDocumentStore(broken).execute(r->null)).status());
        assertThrows(NullPointerException.class,()->new JdbcDocumentStore(null));
    }
    @Test void durableCommittedStateSurvivesRealServerRestart() throws Exception {
        var d=create("durability");String id=POSTGRES.getContainerId();POSTGRES.getDockerClient().stopContainerCmd(id).withTimeout(10).exec();POSTGRES.getDockerClient().startContainerCmd(id).exec();
        var binding=POSTGRES.getDockerClient().inspectContainerCmd(id).exec().getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0];
        jdbcUrl="jdbc:postgresql://"+POSTGRES.getHost()+":"+binding.getHostPortSpec()+"/dm";
        for(var datasource:List.of(admin,runtime,migrator))datasource.setURL(jdbcUrl);
        // JDBC readiness retries are bounded; the same persistent container data directory is retained.
        SQLException last=null;for(int attempt=0;attempt<100;attempt++){try(Connection ignored=admin.getConnection()){last=null;break;}catch(SQLException failure){last=failure;Thread.sleep(100);}}
        if(last!=null)throw last;
        assertEquals(d,store.execute(r->r.find(d.tenant(),d.documentId()).orElseThrow()));assertEquals(0,flyway.migrate().migrationsExecuted);
    }
}
