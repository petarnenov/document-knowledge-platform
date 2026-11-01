package org.documentknowledge.knowledgebase.adapters.outbound.persistence;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.domain.KbModels.Collection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Mandatory physical PostgreSQL suite. No disabled-if-Docker-missing path. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcCollectionStoreTest {
    static final PostgreSQLContainer PG=new PostgreSQLContainer(DockerImageName.parse("postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("knowledge_base").withUsername("postgres").withPassword(UUID.randomUUID().toString())
        .withCommand("postgres","-c","fsync=on","-c","synchronous_commit=on","-c","full_page_writes=on");
    static final String MIGRATION_PASSWORD=UUID.randomUUID().toString(),RUNTIME_PASSWORD=UUID.randomUUID().toString();
    static final Principal ALICE=new Principal("https://issuer.example","alice"),BOB=new Principal("https://issuer.example","bob");
    static final Instant NOW=Instant.parse("2026-11-01T01:00:00Z");
    static PGSimpleDataSource admin,runtime,migrator;static JdbcCollectionStore store;static Flyway flyway;
    static PGSimpleDataSource source(String user,String password){var s=new PGSimpleDataSource();s.setURL(PG.getJdbcUrl());s.setUser(user);s.setPassword(password);s.setConnectTimeout(2);s.setSocketTimeout(2);return s;}
    @BeforeAll static void setup()throws Exception{
        PG.start();admin=source(PG.getUsername(),PG.getPassword());
        sql(admin,"CREATE ROLE dkp_kb_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+MIGRATION_PASSWORD+"'");
        sql(admin,"CREATE ROLE dkp_kb_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+RUNTIME_PASSWORD+"'");
        sql(admin,"REVOKE ALL ON DATABASE knowledge_base FROM PUBLIC");sql(admin,"REVOKE CONNECT ON DATABASE postgres,template1 FROM PUBLIC");sql(admin,"REVOKE ALL ON SCHEMA public FROM PUBLIC");
        sql(admin,"GRANT CONNECT,CREATE ON DATABASE knowledge_base TO dkp_kb_migrator");sql(admin,"GRANT CONNECT ON DATABASE knowledge_base TO dkp_kb_runtime");
        sql(admin,"CREATE SCHEMA kb AUTHORIZATION dkp_kb_migrator");sql(admin,"ALTER ROLE dkp_kb_runtime SET search_path=kb");
        runtime=source("dkp_kb_runtime",RUNTIME_PASSWORD);migrator=source("dkp_kb_migrator",MIGRATION_PASSWORD);
        flyway=Flyway.configure().dataSource(migrator).defaultSchema("kb").schemas("kb").createSchemas(false).locations("classpath:db/migration").load();
        assertEquals(1,flyway.migrate().migrationsExecuted);store=new JdbcCollectionStore(runtime);
    }
    @AfterAll static void stop(){PG.stop();}
    static void sql(PGSimpleDataSource source,String text)throws SQLException{try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);}}
    static long scalar(String text,Object...args)throws SQLException{try(var c=admin.getConnection();var s=c.prepareStatement(text)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);try(var r=s.executeQuery()){assertTrue(r.next());return r.getLong(1);}}}
    static <T>T read(java.util.function.Function<CollectionRepository,T> work){return store.read(Deadline.request(),work);}
    static KBUnitOfWork.LockPlan plan(String tenant,List<UUID> collections,List<UUID> docs,CommandKey key){return new KBUnitOfWork.LockPlan(tenant,collections,docs,key);}
    static <T>T execute(String tenant,UUID collection,java.util.function.Function<CollectionRepository,T> work){return store.execute(plan(tenant,List.of(collection),List.of(),null),Deadline.request(),work);}
    static Set<AclRule> visible(){return Set.of(new AclRule(ALICE,Action.INSPECT,Effect.ALLOW));}
    static Collection collection(String tenant,UUID id,Set<AclRule> rules){return new Collection(id,tenant,0,new Metadata("collection","description"),Status.ACTIVE,0,new AudiencePolicy(id,1,Set.of(),NOW),rules,ALICE,NOW);}
    static Collection create(String tenant){var c=collection(tenant,UUID.randomUUID(),visible());execute(tenant,c.collectionId(),r->{r.insertCollection(c);return null;});return c;}
    static CommandKey key(String tenant,UUID collection,String value){return new CommandKey(new Context(ALICE,tenant),Action.UPDATE.wire(),collection.toString(),value);}
    static CommandOutcome outcome(CommandKey key,UUID collection,long revision){return new CommandOutcome(key,"a".repeat(64),200,new Receipt(collection,null,revision));}
    static Fact fact(String tenant,UUID collection,long revision){return new Fact(UUID.randomUUID(),new Context(ALICE,tenant),collection,revision,Action.UPDATE.wire(),"test change",NOW,0,1,new UUID(0,1));}
    static Snapshot snapshot(String tenant,UUID document,List<ScopeEntry> entries){return new Snapshot(UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),new Context(ALICE,tenant),document,7,read(r->r.tenantFence(tenant)),read(r->r.scope(tenant,document).revision()),entries,Decision.INCOMPATIBLE,NOW);}
    @Test void migrationRoleIsolationAndImmutableEvidence()throws Exception{
        assertEquals(0,flyway.migrate().migrationsExecuted);flyway.validate();
        try(var c=runtime.getConnection();var s=c.createStatement()){
            for(String setting:List.of("fsync","synchronous_commit","full_page_writes")){try(var r=s.executeQuery("SHOW "+setting)){assertTrue(r.next());assertEquals("on",r.getString(1));}}
            try(var r=s.executeQuery("SELECT rolsuper,rolcreatedb,rolcreaterole,rolinherit,rolbypassrls FROM pg_roles WHERE rolname=current_user")){assertTrue(r.next());for(int i=1;i<=5;i++)assertFalse(r.getBoolean(i));}
            for(String forbidden:List.of("CREATE TABLE kb.bad(id int)","CREATE TABLE public.bad(id int)","CREATE TEMP TABLE bad(id int)","CREATE SCHEMA bad","CREATE DATABASE bad","SET ROLE dkp_kb_migrator","ALTER TABLE kb.knowledge_collections ADD bad int","DROP TABLE kb.policy_snapshots","TRUNCATE kb.knowledge_collections","DELETE FROM kb.flyway_schema_history","SELECT * FROM kb.flyway_schema_history","SELECT * FROM dm.documents"))assertThrows(SQLException.class,()->s.execute(forbidden),forbidden);
            for(String table:List.of("audience_policies","audience_rules","membership_history","policy_revisions","policy_snapshots","command_outcomes","audit_facts","outbox_events")){
                assertThrows(SQLException.class,()->s.execute("DELETE FROM kb."+table));assertThrows(SQLException.class,()->s.execute("UPDATE kb."+table+" SET tenant_id=tenant_id"));
            }
        }
        var sibling=source("dkp_kb_runtime",RUNTIME_PASSWORD);sibling.setURL(PG.getJdbcUrl().replace("/knowledge_base","/postgres"));assertThrows(SQLException.class,sibling::getConnection);
    }
    @Test void changedAppliedMigrationChecksumRejectsValidationAndMigration()throws Exception{
        long checksum=scalar("SELECT checksum FROM kb.flyway_schema_history WHERE version='1'");
        long history=scalar("SELECT count(*) FROM kb.flyway_schema_history");
        try{
            sql(admin,"UPDATE kb.flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
            assertThrows(org.flywaydb.core.api.FlywayException.class,flyway::validate);
            assertThrows(org.flywaydb.core.api.FlywayException.class,flyway::migrate);
            assertEquals(history,scalar("SELECT count(*) FROM kb.flyway_schema_history"));
        }finally{
            sql(admin,"UPDATE kb.flyway_schema_history SET checksum="+checksum+" WHERE version='1'");
        }
        flyway.validate();
        assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(history,scalar("SELECT count(*) FROM kb.flyway_schema_history"));
    }
    @Test void collectionAudienceSelectorsAclAndCasRoundTrip()throws Exception{
        var c=create("roundtrip");assertEquals(c,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()));
        var rules=Set.of(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.PRINCIPAL,ALICE.issuer(),ALICE.subject())),new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.TENANT,null,"roundtrip")),new AudienceRule(Effect.DENY,new Selector(SelectorKind.FIRM,ALICE.issuer(),"firm")),new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.RESOURCE_SCOPE,ALICE.issuer(),"scope")));
        var policy=new AudiencePolicy(c.collectionId(),2,rules,NOW.plusSeconds(1));
        var next=c.change(new Metadata("changed",""),Status.ARCHIVED,policy,Set.of(new AclRule(ALICE,Action.INSPECT,Effect.ALLOW),new AclRule(ALICE,Action.INSPECT,Effect.DENY)),false);
        execute(c.tenant(),c.collectionId(),r->{r.saveCollection(next,0);return null;});assertEquals(next,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()));
        assertEquals(409,assertThrows(KbFailure.class,()->execute(c.tenant(),c.collectionId(),r->{r.saveCollection(next,0);return null;})).status());
        assertTrue(JdbcCollectionStoreTest.<Boolean>read(r->r.collection("foreign",c.collectionId()).isEmpty()));
        assertEquals(2,scalar("SELECT count(*) FROM kb.audience_policies WHERE tenant_id=?",c.tenant()));assertEquals(2,scalar("SELECT count(*) FROM kb.policy_revisions WHERE tenant_id=?",c.tenant()));
        execute(c.tenant(),c.collectionId(),r->{r.saveCollection(next.change(next.metadata(),next.status(),next.audience(),next.rules(),false),1);return null;});
        assertEquals(2,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()).revision());
        var illegal=new Collection(c.collectionId(),c.tenant(),3,next.metadata(),next.status(),0,next.audience(),next.rules(),BOB,NOW);
        assertEquals(503,assertThrows(KbFailure.class,()->execute(c.tenant(),c.collectionId(),r->{r.saveCollection(illegal,2);return null;})).status());
        var changedPolicySameRevision=new AudiencePolicy(c.collectionId(),2,Set.of(),NOW);
        assertThrows(KbFailure.class,()->execute(c.tenant(),c.collectionId(),r->{r.saveCollection(new Collection(c.collectionId(),c.tenant(),3,next.metadata(),next.status(),0,changedPolicySameRevision,next.rules(),ALICE,NOW),2);return null;}));
    }
    @Test void aclPredicatesPrecedeLimitWithDenyAndForeignRowsHidden(){
        String tenant="pagination";var ids=List.of(new UUID(0,1),new UUID(0,2),new UUID(0,3),new UUID(0,4));
        for(int i=0;i<4;i++){var acl=i==0?Set.<AclRule>of():i==1?Set.of(new AclRule(ALICE,Action.INSPECT,Effect.ALLOW),new AclRule(ALICE,Action.INSPECT,Effect.DENY)):visible();var c=collection(tenant,ids.get(i),acl);execute(tenant,c.collectionId(),r->{r.insertCollection(c);return null;});}
        var context=new Context(ALICE,tenant);assertEquals(List.of(ids.get(2)),read(r->r.visibleCollections(context,null,1)).stream().map(Collection::collectionId).toList());
        assertEquals(List.of(ids.get(3)),read(r->r.visibleCollections(context,ids.get(2),1)).stream().map(Collection::collectionId).toList());
        assertTrue(read(r->r.visibleCollections(context,ids.get(3),50)).isEmpty());assertTrue(read(r->r.visibleCollections(new Context(BOB,tenant),null,50)).isEmpty());
        assertTrue(read(r->r.visibleCollections(new Context(ALICE,"foreign-page"),null,50)).isEmpty());
        for(int invalid:List.of(0,52))assertEquals(503,assertThrows(KbFailure.class,()->read(r->r.visibleCollections(context,null,invalid))).status());
    }
    @Test void membershipIdentityHistoryScopeAndCountersAreAtomic()throws Exception{
        var c=create("membership");UUID doc=UUID.randomUUID();var m=new Membership(UUID.randomUUID(),c.tenant(),c.collectionId(),doc,0,State.ACTIVE,ALICE,NOW,"associate");
        store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(doc),null),Deadline.request(),r->{r.saveMembership(m,null,ALICE,NOW);r.saveCollection(c.change(c.metadata(),c.status(),c.audience(),c.rules(),true),0);r.saveScope(new Scope(c.tenant(),doc,1),0);r.advanceTenantFence(c.tenant(),0);return null;});
        assertEquals(List.of(m),read(r->r.members(c.tenant(),c.collectionId())));assertEquals(List.of(m),read(r->r.memberships(c.tenant(),doc)));assertEquals(1,read(r->r.scope(c.tenant(),doc)).revision());assertEquals(1L,JdbcCollectionStoreTest.<Long>read(r->r.tenantFence(c.tenant())).longValue());
        var changed=m.change(State.INACTIVE,"suspend");execute(c.tenant(),c.collectionId(),r->{r.saveMembership(changed,0L,BOB,NOW.plusSeconds(1));return null;});
        assertEquals(List.of(changed),read(r->r.memberships(c.tenant(),doc)));assertEquals(2,scalar("SELECT count(*) FROM kb.membership_history WHERE tenant_id=?",c.tenant()));assertEquals(1,scalar("SELECT count(*) FROM kb.membership_history WHERE tenant_id=? AND actor_subject='bob'",c.tenant()));
        assertEquals(409,assertThrows(KbFailure.class,()->execute(c.tenant(),c.collectionId(),r->{r.saveMembership(changed,0L,BOB,NOW);return null;})).status());
        assertEquals(409,assertThrows(KbFailure.class,()->execute(c.tenant(),c.collectionId(),r->{r.advanceTenantFence(c.tenant(),0);return null;})).status());
        assertEquals(409,assertThrows(KbFailure.class,()->execute(c.tenant(),c.collectionId(),r->{r.saveScope(new Scope(c.tenant(),doc,2),0);return null;})).status());
        assertTrue(read(r->r.members("other",c.collectionId())).isEmpty());assertTrue(read(r->r.memberships("other",doc)).isEmpty());assertEquals(0,read(r->r.scope("other",doc)).revision());
    }
    @Test void completeSnapshotsAreActorTenantDocumentBoundAndImmutable()throws Exception{
        var c=create("snapshots");UUID doc=UUID.randomUUID();var entry=new ScopeEntry(UUID.randomUUID(),c.collectionId(),0,0,1,State.INACTIVE,Status.ARCHIVED);var snap=snapshot(c.tenant(),doc,List.of(entry));
        store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(doc),null),Deadline.request(),r->{r.saveSnapshot(snap);return null;});
        assertEquals(snap,read(r->r.snapshot(snap.tokenHash(),snap.context(),doc).orElseThrow()));
        for(var wrong:List.of(new Context(BOB,c.tenant()),new Context(new Principal("https://other.example",ALICE.subject()),c.tenant()),new Context(ALICE,"other")))assertTrue(read(r->r.snapshot(snap.tokenHash(),wrong,doc)).isEmpty());
        assertTrue(read(r->r.snapshot(snap.tokenHash(),snap.context(),UUID.randomUUID())).isEmpty());assertTrue(read(r->r.snapshot("b".repeat(64),snap.context(),doc)).isEmpty());
        sql(admin,"UPDATE kb.policy_snapshots SET payload=decode('00','hex') WHERE token_hash='"+snap.tokenHash()+"'");
        assertEquals(503,assertThrows(KbFailure.class,()->read(r->r.snapshot(snap.tokenHash(),snap.context(),doc))).status());
    }
    @Test void relationalMembershipLimitsDuplicateAndForeignBindingsAreRejected()throws Exception{
        var c=create("collection-cap");
        execute(c.tenant(),c.collectionId(),r->{for(int i=0;i<100;i++)r.saveMembership(new Membership(UUID.randomUUID(),c.tenant(),c.collectionId(),UUID.randomUUID(),0,State.INACTIVE,ALICE,NOW,"retained"),null,ALICE,NOW);return null;});
        assertEquals(100,read(r->r.members(c.tenant(),c.collectionId())).size());
        assertEquals(503,assertThrows(KbFailure.class,()->execute(c.tenant(),c.collectionId(),r->{r.saveMembership(new Membership(UUID.randomUUID(),c.tenant(),c.collectionId(),UUID.randomUUID(),0,State.ACTIVE,ALICE,NOW,"overflow"),null,ALICE,NOW);return null;})).status());
        String tenant="document-cap";UUID doc=UUID.randomUUID();var collectionIds=new ArrayList<UUID>();
        for(int i=0;i<51;i++){var next=create(tenant);collectionIds.add(next.collectionId());if(i<50)execute(tenant,next.collectionId(),r->{r.saveMembership(new Membership(UUID.randomUUID(),tenant,next.collectionId(),doc,0,State.INACTIVE,ALICE,NOW,"retained"),null,ALICE,NOW);return null;});}
        assertEquals(50,read(r->r.memberships(tenant,doc)).size());
        assertThrows(KbFailure.class,()->execute(tenant,collectionIds.getLast(),r->{r.saveMembership(new Membership(UUID.randomUUID(),tenant,collectionIds.getLast(),doc,0,State.ACTIVE,ALICE,NOW,"overflow"),null,ALICE,NOW);return null;}));
        var existing=read(r->r.memberships(tenant,doc)).getFirst();
        assertThrows(KbFailure.class,()->execute(tenant,existing.collectionId(),r->{r.saveMembership(new Membership(UUID.randomUUID(),tenant,existing.collectionId(),doc,0,State.ACTIVE,ALICE,NOW,"duplicate"),null,ALICE,NOW);return null;}));
        assertThrows(KbFailure.class,()->execute("foreign-binding",existing.collectionId(),r->{r.saveMembership(new Membership(UUID.randomUUID(),"foreign-binding",existing.collectionId(),UUID.randomUUID(),0,State.ACTIVE,ALICE,NOW,"foreign"),null,ALICE,NOW);return null;}));
        assertEquals(50,scalar("SELECT count(*) FROM kb.membership_history WHERE tenant_id=?",tenant));
    }
    @Test void explicitAclAndSelectorSqlBoundsRejectExtraAndContradictoryShapes()throws Exception{
        String tenant="acl-cap";UUID id=UUID.randomUUID();var rules=new HashSet<AclRule>();for(int i=0;i<100;i++)rules.add(new AclRule(new Principal(ALICE.issuer(),"actor-"+i),Action.INSPECT,Effect.ALLOW));
        var c=collection(tenant,id,rules);execute(tenant,id,r->{r.insertCollection(c);return null;});assertEquals(c,read(r->r.collection(tenant,id).orElseThrow()));
        assertThrows(SQLException.class,()->sql(runtime,"INSERT INTO kb.collection_acl_rules VALUES ('acl-cap','"+id+"','https://issuer.example','extra','knowledge.collection.inspect','ALLOW')"));
        assertThrows(SQLException.class,()->sql(runtime,"INSERT INTO kb.audience_rules VALUES ('acl-cap','"+id+"',1,0,'PRINCIPAL',NULL,NULL,NULL,'ALLOW')"));
        assertThrows(SQLException.class,()->sql(runtime,"INSERT INTO kb.audience_rules VALUES ('acl-cap','"+id+"',1,0,'TENANT','https://issuer.example',NULL,'acl-cap','ALLOW')"));
        assertThrows(SQLException.class,()->sql(runtime,"INSERT INTO kb.audience_rules VALUES ('acl-cap','"+id+"',1,100,'TENANT',NULL,NULL,'acl-cap','ALLOW')"));
        assertThrows(SQLException.class,()->sql(runtime,"UPDATE kb.knowledge_collections SET aggregate_revision=9007199254740992 WHERE tenant_id='acl-cap'"));
    }
    @Test void committedAudienceChildrenAreSealedWhileNewRevisionRulesCommitAtomically()throws Exception{
        var c=create("sealed-policy");String before=ownedState(c.tenant());
        String late="INSERT INTO kb.audience_rules VALUES ('sealed-policy','"+c.collectionId()+"',1,0,'TENANT',NULL,NULL,'sealed-policy','ALLOW')";
        var refused=assertThrows(SQLException.class,()->sql(runtime,late));assertEquals("23514",refused.getSQLState());
        assertEquals(before,ownedState(c.tenant()));assertEquals(c,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()));
        assertEquals(1,scalar("SELECT count(*) FROM kb.audience_policies WHERE tenant_id=? AND sealed",c.tenant()));
        assertThrows(SQLException.class,()->sql(runtime,"UPDATE kb.audience_policies SET sealed=false WHERE tenant_id='sealed-policy'"));
        assertEquals(0,scalar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='kb' AND p.proname='seal_audience_policy' AND has_function_privilege('dkp_kb_runtime',p.oid,'EXECUTE')"));
        assertEquals(1,scalar("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_roles r ON r.oid=p.proowner WHERE n.nspname='kb' AND p.proname='seal_audience_policy' AND p.prosecdef AND r.rolname='dkp_kb_migrator' AND NOT r.rolsuper"));
        var policy=new AudiencePolicy(c.collectionId(),2,Set.of(new AudienceRule(Effect.ALLOW,new Selector(SelectorKind.TENANT,null,c.tenant()))),NOW.plusSeconds(1));
        var updated=c.change(c.metadata(),c.status(),policy,c.rules(),false);
        execute(c.tenant(),c.collectionId(),r->{r.saveCollection(updated,0);return null;});assertEquals(updated,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()));
        assertEquals(2,scalar("SELECT count(*) FROM kb.audience_policies WHERE tenant_id=? AND sealed",c.tenant()));
        assertEquals(1,scalar("SELECT count(*) FROM kb.audience_rules WHERE tenant_id=?",c.tenant()));
        String after=ownedState(c.tenant());assertThrows(SQLException.class,()->sql(runtime,"INSERT INTO kb.audience_rules VALUES ('sealed-policy','"+c.collectionId()+"',2,1,'TENANT',NULL,NULL,'sealed-policy','DENY')"));assertEquals(after,ownedState(c.tenant()));
        try(var writer=runtime.getConnection();var statement=writer.createStatement();var concurrent=Executors.newSingleThreadExecutor()){
            writer.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO kb.audience_policies(tenant_id,collection_id,policy_revision,effective_at,classification,classification_policy_revision) VALUES ('sealed-policy','"+c.collectionId()+"',3,now(),'development-restricted',1)");
            String child="INSERT INTO kb.audience_rules VALUES ('sealed-policy','"+c.collectionId()+"',3,0,'TENANT',NULL,NULL,'sealed-policy','ALLOW')";
            var attempted=concurrent.submit(()->assertThrows(SQLException.class,()->sql(runtime,child)).getSQLState());assertEquals("23514",attempted.get(3,TimeUnit.SECONDS));
            assertEquals(1,statement.executeUpdate(child)); // Only the parent's owning transaction can construct its children.
            writer.rollback();
        }
        assertEquals(after,ownedState(c.tenant())); // Rollback creates no unsealed parent or child evidence.
    }
    private static String ownedState(String tenant)throws SQLException{
        var hashes=new StringBuilder();for(String table:List.of("knowledge_collections","collection_acl_rules","audience_policies","audience_rules","collection_memberships","membership_history","policy_revisions","document_membership_scopes","tenant_policy_fences","policy_snapshots","command_outcomes","audit_facts","outbox_events")){
            try(var c=admin.getConnection();var s=c.prepareStatement("SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]')) FROM kb."+table+" t WHERE tenant_id=?")){s.setString(1,tenant);try(var rows=s.executeQuery()){assertTrue(rows.next());hashes.append(table).append(':').append(rows.getString(1)).append('\n');}}
        }return hashes.toString();
    }
    @Test void failuresAfterEachFactStageRollBackEveryProjectionAndFact()throws Exception{
        var c=create("rollback");UUID doc=UUID.randomUUID();var key=key(c.tenant(),c.collectionId(),"atomic");
        for(int failureAt=0;failureAt<7;failureAt++){final int at=failureAt;
            assertThrows(KbFailure.class,()->store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(doc),key),Deadline.request(),r->{
                int step=0;var next=c.change(c.metadata(),c.status(),new AudiencePolicy(c.collectionId(),2,Set.of(),NOW.plusSeconds(1)),c.rules(),true);r.saveCollection(next,0);if(step++==at)throw KbFailure.unavailable();
                var m=new Membership(UUID.randomUUID(),c.tenant(),c.collectionId(),doc,0,State.ACTIVE,ALICE,NOW,"atomic");r.saveMembership(m,null,ALICE,NOW);if(step++==at)throw KbFailure.unavailable();
                r.saveScope(new Scope(c.tenant(),doc,1),0);if(step++==at)throw KbFailure.unavailable();r.advanceTenantFence(c.tenant(),0);if(step++==at)throw KbFailure.unavailable();
                var snap=new Snapshot("c".repeat(64),new Context(ALICE,c.tenant()),doc,1,1,1,List.of(new ScopeEntry(m.membershipId(),c.collectionId(),1,0,2,State.ACTIVE,Status.ACTIVE)),Decision.COMPATIBLE,NOW);r.saveSnapshot(snap);if(step++==at)throw KbFailure.unavailable();
                r.record(outcome(key,c.collectionId(),1),fact(c.tenant(),c.collectionId(),1),fact(c.tenant(),c.collectionId(),1));if(step++==at)throw KbFailure.unavailable();throw KbFailure.unavailable();
            }));
            assertEquals(c,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()));assertEquals(0L,JdbcCollectionStoreTest.<Long>read(r->r.tenantFence(c.tenant())).longValue());assertTrue(read(r->r.memberships(c.tenant(),doc)).isEmpty());assertTrue(read(r->r.command(key)).isEmpty());
            for(String table:List.of("membership_history","collection_memberships","policy_snapshots","command_outcomes","audit_facts","outbox_events","document_membership_scopes"))assertEquals(0,scalar("SELECT count(*) FROM kb."+table+" WHERE tenant_id=?",c.tenant()),table);
            assertEquals(1,scalar("SELECT count(*) FROM kb.policy_revisions WHERE tenant_id=?",c.tenant()));assertEquals(1,scalar("SELECT count(*) FROM kb.audience_policies WHERE tenant_id=?",c.tenant()));
        }
    }
    @Test void sameKeyRaceCommitsOneOriginalOutcomeAndFacts()throws Exception{
        var c=create("same-key");var key=key(c.tenant(),c.collectionId(),"once");var barrier=new CyclicBarrier(2);
        Callable<CommandOutcome> action=()->{barrier.await(3,TimeUnit.SECONDS);return store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(),key),Deadline.request(),r->{var prior=r.command(key);if(prior.isPresent())return prior.get();var current=r.collection(c.tenant(),c.collectionId()).orElseThrow();r.saveCollection(current.change(current.metadata(),current.status(),current.audience(),current.rules(),false),0);r.advanceTenantFence(c.tenant(),0);var result=outcome(key,c.collectionId(),1);r.record(result,fact(c.tenant(),c.collectionId(),1),fact(c.tenant(),c.collectionId(),1));return result;});};
        try(var executor=Executors.newFixedThreadPool(2)){var results=executor.invokeAll(List.of(action,action));assertEquals(results.get(0).get(),results.get(1).get());}
        for(String table:List.of("command_outcomes","audit_facts","outbox_events"))assertEquals(1,scalar("SELECT count(*) FROM kb."+table+" WHERE tenant_id=?",c.tenant()));
        assertEquals(1L,JdbcCollectionStoreTest.<Long>read(r->r.tenantFence(c.tenant())).longValue());assertEquals(1,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()).revision());
        assertTrue(read(r->r.command(new CommandKey(new Context(BOB,c.tenant()),key.operation(),key.resourceKey(),key.idempotencyKey()))).isEmpty());
        assertTrue(read(r->r.command(new CommandKey(key.context(),key.operation(),key.resourceKey(),"different"))).isEmpty());
    }
    @Test void sqlFailureBetweenCommandAuditAndOutboxRollsBackEarlierFactInserts()throws Exception{
        for(String table:List.of("audit_facts","outbox_events")){
            var c=create("fail-"+table);var key=key(c.tenant(),c.collectionId(),"atomic");
            sql(admin,"CREATE FUNCTION kb.test_fail_fact() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic fact failure'; END $$");
            sql(admin,"CREATE TRIGGER test_fail_fact BEFORE INSERT ON kb."+table+" FOR EACH ROW EXECUTE FUNCTION kb.test_fail_fact()");
            try{
                assertEquals(503,assertThrows(KbFailure.class,()->store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(),key),Deadline.request(),r->{r.saveCollection(c.change(c.metadata(),c.status(),c.audience(),c.rules(),false),0);r.advanceTenantFence(c.tenant(),0);r.record(outcome(key,c.collectionId(),1),fact(c.tenant(),c.collectionId(),1),fact(c.tenant(),c.collectionId(),1));return null;})).status());
            }finally{sql(admin,"DROP TRIGGER test_fail_fact ON kb."+table);sql(admin,"DROP FUNCTION kb.test_fail_fact()");}
            assertEquals(c,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()));assertEquals(0,JdbcCollectionStoreTest.<Long>read(r->r.tenantFence(c.tenant())).longValue());
            for(String fact:List.of("command_outcomes","audit_facts","outbox_events"))assertEquals(0,scalar("SELECT count(*) FROM kb."+fact+" WHERE tenant_id=?",c.tenant()));
        }
    }
    @Test void snapshotAdmissionRaceAtTenThousandPreservesOriginalEvidence()throws Exception{
        var c=create("snapshot-cap");UUID doc=UUID.randomUUID();var original=snapshot(c.tenant(),doc,List.of());
        store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(doc),null),Deadline.request(),r->{r.saveSnapshot(original);return null;});
        // Real fixture seeding uses the same enabled quota trigger, never disables enforcement.
        try(var seed=runtime.getConnection();var statement=seed.createStatement()){
            // Fixture population runs all9998 real quota-trigger evaluations. This is
            // separate from the unchanged500ms application admission transaction.
            seed.setNetworkTimeout(Runnable::run,30000);statement.setQueryTimeout(25);
            statement.executeUpdate("INSERT INTO kb.policy_snapshots SELECT encode(sha256(convert_to('snapshot-cap-'||i,'UTF8')),'hex'),'snapshot-cap','https://issuer.example','alice','"+doc+"',7,'development-restricted',1,0,0,1,decode('4b42533100000000','hex'),'INCOMPATIBLE',now() FROM generate_series(1,9998) i");
        }
        var one=snapshot(c.tenant(),doc,List.of());var two=snapshot(c.tenant(),doc,List.of());var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=List.<Callable<Integer>>of(()->admit(one,barrier,c.collectionId()),()->admit(two,barrier,c.collectionId()));var results=executor.invokeAll(tasks);assertEquals(Set.of(201,429),Set.of(results.get(0).get(),results.get(1).get()));
        }
        assertEquals(10000,scalar("SELECT count(*) FROM kb.policy_snapshots WHERE tenant_id=?",c.tenant()));assertEquals(original,read(r->r.snapshot(original.tokenHash(),original.context(),doc).orElseThrow()));
        assertThrows(SQLException.class,()->sql(runtime,"INSERT INTO kb.policy_snapshots SELECT repeat('f',64),tenant_id,actor_issuer,actor_subject,document_id,document_revision,classification,classification_policy_revision,tenant_fence,set_revision,payload_version,payload,decision,created_at FROM kb.policy_snapshots WHERE token_hash='"+original.tokenHash()+"'"));
    }
    private int admit(Snapshot snapshot,CyclicBarrier barrier,UUID collection)throws Exception{barrier.await(3,TimeUnit.SECONDS);try{return store.execute(plan(snapshot.context().tenant(),List.of(collection),List.of(snapshot.documentId()),null),Deadline.request(),r->{r.saveSnapshot(snapshot);return 201;});}catch(KbFailure failure){return failure.status();}}
    @Test void distinctKeysCasRaceHasOnlyOneWinner()throws Exception{
        var c=create("cas-race");var barrier=new CyclicBarrier(2);Callable<Integer> action=()->{barrier.await(3,TimeUnit.SECONDS);try{return execute(c.tenant(),c.collectionId(),r->{var current=r.collection(c.tenant(),c.collectionId()).orElseThrow();r.saveCollection(current.change(current.metadata(),current.status(),current.audience(),current.rules(),false),0);return 200;});}catch(KbFailure failure){return failure.status();}};
        try(var executor=Executors.newFixedThreadPool(2)){var futures=executor.invokeAll(List.of(action,action));assertEquals(Set.of(200,409),Set.of(futures.get(0).get(),futures.get(1).get()));}
    }
    @Test void actualCommittedButLostAcknowledgmentReconcilesOriginalKeyWithoutDuplicateFacts()throws Exception{
        var c=create("unknown-commit");var key=key(c.tenant(),c.collectionId(),"original-key");var expected=outcome(key,c.collectionId(),1);
        var lostResponse=new PGSimpleDataSource(){@Override public Connection getConnection()throws SQLException{
            var actual=runtime.getConnection();return(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{var result=method.invoke(actual,args);if(method.getName().equals("commit"))throw new SQLException("Synthetic lost COMMIT acknowledgment");return result;}
                catch(java.lang.reflect.InvocationTargetException wrapped){throw wrapped.getCause();}
            });
        }};
        var uncertain=new JdbcCollectionStore(lostResponse);
        assertEquals(503,assertThrows(KbFailure.class,()->uncertain.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(),key),Deadline.request(),r->{r.saveCollection(c.change(c.metadata(),c.status(),c.audience(),c.rules(),false),0);r.advanceTenantFence(c.tenant(),0);r.record(expected,fact(c.tenant(),c.collectionId(),1),fact(c.tenant(),c.collectionId(),1));return expected;})).status());
        var fresh=new JdbcCollectionStore(runtime);assertEquals(expected,fresh.read(Deadline.request(),r->r.command(key).orElseThrow()));
        assertEquals(expected,fresh.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(),key),Deadline.request(),r->r.command(key).orElseThrow()));
        for(String table:List.of("command_outcomes","audit_facts","outbox_events"))assertEquals(1,scalar("SELECT count(*) FROM kb."+table+" WHERE tenant_id=?",c.tenant()));
        assertEquals(1,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()).revision());
    }
    @Test void runtimeCannotRebindIdentitiesOrResetRevisions()throws Exception{
        var c=create("sql-guards");UUID doc=UUID.randomUUID();var m=new Membership(UUID.randomUUID(),c.tenant(),c.collectionId(),doc,0,State.ACTIVE,ALICE,NOW,"initial");
        store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(doc),null),Deadline.request(),r->{r.saveMembership(m,null,ALICE,NOW);return null;});
        for(String statement:List.of("UPDATE kb.knowledge_collections SET creator_subject='changed',aggregate_revision=aggregate_revision+1 WHERE tenant_id='sql-guards'","UPDATE kb.knowledge_collections SET aggregate_revision=aggregate_revision WHERE tenant_id='sql-guards'","UPDATE kb.collection_memberships SET document_id='"+UUID.randomUUID()+"',revision=1 WHERE tenant_id='sql-guards'","UPDATE kb.tenant_policy_fences SET revision=revision WHERE tenant_id='sql-guards'","UPDATE kb.document_membership_scopes SET set_revision=set_revision WHERE tenant_id='sql-guards'","DELETE FROM kb.collection_memberships WHERE tenant_id='sql-guards'","UPDATE kb.knowledge_collections SET audience_policy_revision=2,aggregate_revision=1 WHERE tenant_id='sql-guards'"))assertThrows(SQLException.class,()->sql(runtime,statement),statement);
        assertEquals(c,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()));assertEquals(List.of(m),read(r->r.memberships(c.tenant(),doc)));
    }
    @Test void finalUnitOfWorkCannotWriteAnotherTenantOutsideItsLockedFence(){
        var foreign=create("foreign-write");var selected=create("selected-write");UUID doc=UUID.randomUUID();var key=key(foreign.tenant(),foreign.collectionId(),"forged");
        var snapshot=snapshot(foreign.tenant(),doc,List.of());
        var writes=List.<java.util.function.Consumer<CollectionRepository>>of(r->r.insertCollection(collection(foreign.tenant(),UUID.randomUUID(),visible())),r->r.saveCollection(foreign.change(foreign.metadata(),foreign.status(),foreign.audience(),foreign.rules(),false),0),r->r.saveMembership(new Membership(UUID.randomUUID(),foreign.tenant(),foreign.collectionId(),doc,0,State.ACTIVE,ALICE,NOW,"wrong fence"),null,ALICE,NOW),r->r.saveScope(new Scope(foreign.tenant(),doc,1),0),r->r.advanceTenantFence(foreign.tenant(),0),r->r.saveSnapshot(snapshot),r->r.record(outcome(key,foreign.collectionId(),0),fact(foreign.tenant(),foreign.collectionId(),0),fact(foreign.tenant(),foreign.collectionId(),0)));
        for(var write:writes)assertEquals(503,assertThrows(KbFailure.class,()->execute(selected.tenant(),selected.collectionId(),r->{write.accept(r);return null;})).status());
        assertEquals(foreign,read(r->r.collection(foreign.tenant(),foreign.collectionId()).orElseThrow()));assertEquals(0,JdbcCollectionStoreTest.<Long>read(r->r.tenantFence(foreign.tenant())).longValue());assertTrue(read(r->r.command(key)).isEmpty());
    }
    @Test void repeatableReadKeepsCompleteSetCoherentWithoutBlockingOwningWriter(){
        var c=create("mvcc");UUID doc=UUID.randomUUID();
        read(r->{assertTrue(r.memberships(c.tenant(),doc).isEmpty());assertEquals(0,r.tenantFence(c.tenant()));
            store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(doc),null),Deadline.request(),writer->{writer.saveMembership(new Membership(UUID.randomUUID(),c.tenant(),c.collectionId(),doc,0,State.ACTIVE,ALICE,NOW,"new"),null,ALICE,NOW);writer.saveScope(new Scope(c.tenant(),doc,1),0);writer.advanceTenantFence(c.tenant(),0);return null;});
            assertTrue(r.memberships(c.tenant(),doc).isEmpty());assertEquals(0,r.scope(c.tenant(),doc).revision());assertEquals(0,r.tenantFence(c.tenant()));return null;
        });
        assertEquals(1,read(r->r.memberships(c.tenant(),doc)).size());assertEquals(1,read(r->r.scope(c.tenant(),doc)).revision());
    }
    @Test void contendedTenantFenceFailsWithinFiniteLockBudgetAndWritesNothing()throws Exception{
        var c=create("lock-budget");try(var held=runtime.getConnection();var lock=held.prepareStatement("SELECT revision FROM kb.tenant_policy_fences WHERE tenant_id=? FOR UPDATE")){
            held.setAutoCommit(false);lock.setString(1,c.tenant());lock.executeQuery().close();long before=System.nanoTime();
            assertEquals(503,assertThrows(KbFailure.class,()->execute(c.tenant(),c.collectionId(),r->{r.advanceTenantFence(c.tenant(),0);return null;})).status());
            assertTrue(System.nanoTime()-before<Duration.ofSeconds(2).toNanos(),"Lock contention must not wait indefinitely");held.rollback();
        }
        assertEquals(0,JdbcCollectionStoreTest.<Long>read(r->r.tenantFence(c.tenant())).longValue());
    }
    @Test void opposingAudienceAndMembershipChangesSerializeWithoutMissedDocumentFence()throws Exception{
        var a=create("opposing");var b=create("opposing");UUID doc=UUID.randomUUID();var barrier=new CyclicBarrier(2);
        Callable<Void> audience=()->{barrier.await(3,TimeUnit.SECONDS);return store.execute(plan(a.tenant(),List.of(b.collectionId(),a.collectionId()),List.of(doc),null),Deadline.request(),r->{var current=r.collection(a.tenant(),a.collectionId()).orElseThrow();r.saveCollection(current.change(current.metadata(),current.status(),new AudiencePolicy(a.collectionId(),2,Set.of(),NOW.plusSeconds(1)),current.rules(),false),0);var scope=r.scope(a.tenant(),doc);r.saveScope(new Scope(a.tenant(),doc,scope.revision()+1),scope.revision());r.advanceTenantFence(a.tenant(),r.tenantFence(a.tenant()));return null;});};
        Callable<Void> membership=()->{barrier.await(3,TimeUnit.SECONDS);return store.execute(plan(a.tenant(),List.of(a.collectionId(),b.collectionId()),List.of(doc),null),Deadline.request(),r->{r.saveMembership(new Membership(UUID.randomUUID(),a.tenant(),b.collectionId(),doc,0,State.ACTIVE,ALICE,NOW,"associate"),null,ALICE,NOW);r.saveCollection(b.change(b.metadata(),b.status(),b.audience(),b.rules(),true),0);var scope=r.scope(a.tenant(),doc);r.saveScope(new Scope(a.tenant(),doc,scope.revision()+1),scope.revision());r.advanceTenantFence(a.tenant(),r.tenantFence(a.tenant()));return null;});};
        try(var executor=Executors.newFixedThreadPool(2)){for(var result:executor.invokeAll(List.of(audience,membership)))assertNull(result.get());}
        assertEquals(2,read(r->r.scope(a.tenant(),doc)).revision());assertEquals(2,JdbcCollectionStoreTest.<Long>read(r->r.tenantFence(a.tenant())).longValue());assertEquals(1,read(r->r.memberships(a.tenant(),doc)).size());assertEquals(2,read(r->r.collection(a.tenant(),a.collectionId()).orElseThrow()).audience().revision());
    }
    @Test @Order(Integer.MAX_VALUE) void physicalRestartPreservesCompleteSnapshotCountersAndOriginalOutcome()throws Exception{
        var c=create("restart");UUID doc=UUID.randomUUID();var key=key(c.tenant(),c.collectionId(),"uncertain-original");var receipt=outcome(key,c.collectionId(),1);
        var membership=new Membership(UUID.randomUUID(),c.tenant(),c.collectionId(),doc,0,State.INACTIVE,ALICE,NOW,"preserved inactive history");
        var changed=c.change(c.metadata(),Status.ARCHIVED,new AudiencePolicy(c.collectionId(),2,Set.of(),NOW.plusSeconds(1)),c.rules(),true);
        store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(doc),key),Deadline.request(),r->{r.saveMembership(membership,null,ALICE,NOW);r.saveCollection(changed,0);r.advanceTenantFence(c.tenant(),0);r.saveScope(new Scope(c.tenant(),doc,1),0);var audit=new Fact(UUID.randomUUID(),key.context(),c.collectionId(),1,key.operation(),"restart proof",NOW,1,2,new UUID(0,8));var outbox=new Fact(UUID.randomUUID(),key.context(),c.collectionId(),1,key.operation(),"restart proof",NOW,1,2,new UUID(0,8));r.record(receipt,audit,outbox);return null;});
        var snap=snapshot(c.tenant(),doc,List.of(new ScopeEntry(membership.membershipId(),c.collectionId(),1,1,0,2,State.INACTIVE,Status.ARCHIVED)));store.execute(plan(c.tenant(),List.of(c.collectionId()),List.of(doc),null),Deadline.request(),r->{r.saveSnapshot(snap);return null;});
        long before=scalar("SELECT extract(epoch FROM pg_postmaster_start_time())*1000000");String id=PG.getContainerId();
        var prior=PG.getDockerClient().inspectContainerCmd(id).exec();assertEquals(id,prior.getId());assertEquals("true",prior.getConfig().getLabels().get("org.testcontainers"));String mounts=mountHash(prior);int previousPort=mappedPort(prior);
        for(var source:List.of(admin,runtime,migrator))assertTrue(Arrays.equals(new int[]{previousPort},source.getPortNumbers()),"Existing sources must match observed owned PostgreSQL port");
        PG.getDockerClient().restartContainerCmd(id).withTimeout(5).exec();
        var restarted=PG.getDockerClient().inspectContainerCmd(id).exec();assertEquals(id,restarted.getId());assertEquals(id,PG.getContainerId());assertEquals("true",restarted.getConfig().getLabels().get("org.testcontainers"));assertEquals(prior.getImageId(),restarted.getImageId());assertEquals(mounts,mountHash(restarted));
        int actualPort=mappedPort(restarted);if(actualPort!=previousPort)for(var source:List.of(admin,runtime,migrator))source.setPortNumbers(new int[]{actualPort});
        long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();boolean healthy=false;
        while(System.nanoTime()<until){try{if(scalar("SELECT extract(epoch FROM pg_postmaster_start_time())*1000000")>before){healthy=true;break;}}catch(SQLException unavailable){/* Only the same owned PostgreSQL is retried. */}Thread.sleep(100);}
        assertTrue(healthy,"Same physical PostgreSQL must recover within30 seconds");
        assertEquals(receipt,read(r->r.command(key).orElseThrow()));assertEquals(snap,read(r->r.snapshot(snap.tokenHash(),snap.context(),doc).orElseThrow()));assertEquals(1L,JdbcCollectionStoreTest.<Long>read(r->r.tenantFence(c.tenant())).longValue());assertEquals(1,read(r->r.scope(c.tenant(),doc)).revision());
        assertEquals(changed,read(r->r.collection(c.tenant(),c.collectionId()).orElseThrow()));assertEquals(List.of(membership),read(r->r.memberships(c.tenant(),doc)));assertEquals(2,scalar("SELECT count(*) FROM kb.audience_policies WHERE tenant_id=?",c.tenant()));assertEquals(2,scalar("SELECT count(*) FROM kb.policy_revisions WHERE tenant_id=?",c.tenant()));assertEquals(1,scalar("SELECT count(*) FROM kb.membership_history WHERE tenant_id=?",c.tenant()));
        for(String table:List.of("command_outcomes","audit_facts","outbox_events"))assertEquals(1,scalar("SELECT count(*) FROM kb."+table+" WHERE tenant_id=?",c.tenant()));
    }
    private static int mappedPort(com.github.dockerjava.api.command.InspectContainerResponse metadata){
        var bindings=metadata.getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432));
        assertNotNull(bindings,"Owned PostgreSQL must retain published bindings");assertTrue(bindings.length>0&&bindings.length<=4,"Published bindings must be bounded");
        var ports=new HashSet<Integer>();for(var binding:bindings){assertNotNull(binding);assertTrue(Set.of("0.0.0.0","::","127.0.0.1","::1").contains(binding.getHostIp()),"Only fixed wildcard/loopback bindings permitted");String specification=binding.getHostPortSpec();assertNotNull(specification);assertTrue(specification.matches("[0-9]{1,5}"),"Published port must be finite");int port=Integer.parseInt(specification);assertTrue(port>0&&port<=65535);ports.add(port);}
        assertEquals(1,ports.size(),"IPv4/IPv6 bindings must resolve to one owned port");return ports.iterator().next();
    }
    private static String mountHash(com.github.dockerjava.api.command.InspectContainerResponse metadata)throws Exception{
        var entries=metadata.getMounts().stream().map(m->String.join("\u0000",String.valueOf(m.getDriver()),String.valueOf(m.getMode()),String.valueOf(m.getName()),String.valueOf(m.getSource()),String.valueOf(m.getDestination()),String.valueOf(m.getRW()))).sorted().toList();
        assertFalse(entries.isEmpty(),"Owned PostgreSQL data mounts must exist");return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n",entries).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

}
