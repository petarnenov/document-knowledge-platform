package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.application.LogProducerRegistry.*;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** V1-to-V2 migration and credential tests in a separately owned disposable database. */
class JdbcLogProducersTest {
    static final String DATABASE = "dkp_reg_"+UUID.randomUUID().toString().replace("-","");
    static final String READER = "reader_"+UUID.randomUUID().toString().replace("-","");
    static final String PASSWORD = UUID.randomUUID().toString();
    static PGSimpleDataSource admin, owner, reader;
    static JdbcLogProducers registry;
    ArtifactRegistration artifact;
    final LogScope.Description tenant = tenant("tenant-a");

    static PGSimpleDataSource source(String database, String user, String password) {
        String base = Objects.requireNonNull(System.getenv("DKP_LOG_TEST_JDBC"),"Owned PostgreSQL required");
        var source = new PGSimpleDataSource();
        source.setURL(database == null ? base : base.substring(0,base.lastIndexOf('/')+1)+database);
        source.setUser(user); source.setPassword(password); source.setConnectTimeout(1); source.setLoginTimeout(1); source.setSocketTimeout(5);
        return source;
    }
    @BeforeAll static void migrate() throws Exception {
        admin = source(null,"postgres",Objects.requireNonNull(System.getenv("DKP_LOG_TEST_PASSWORD")));
        try(var connection=admin.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE DATABASE "+DATABASE);
            sql.execute("CREATE ROLE "+READER+" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+PASSWORD+"'");
        }
        owner=source(DATABASE,"postgres",System.getenv("DKP_LOG_TEST_PASSWORD"));
        try(var connection=owner.getConnection();var sql=connection.createStatement()) {
            for(String name:List.of("V1__owned_dev_logging.sql","V2__scoped_producer_credentials.sql")) {
                if(name.startsWith("V2")) {
                    String digest="a".repeat(64);
                    sql.execute("INSERT INTO dev_logs.manifests VALUES ('"+digest+"',gen_random_uuid(),'migration-fixture','"+"b".repeat(40)+"','"+digest+"','"+digest+"',decode('7b7d','hex'),1026,clock_timestamp())");
                    sql.execute("INSERT INTO dev_logs.producers SELECT gen_random_uuid(),manifest_sha,gen_random_uuid(),statement_timestamp(),statement_timestamp()+interval '1 hour',false,1024 FROM dev_logs.manifests");
                    sql.execute("INSERT INTO dev_logs.scopes SELECT gen_random_uuid(),producer_id,manifest_sha,run_id,gen_random_uuid(),'OPERATOR_ONLY',NULL,NULL,NULL,statement_timestamp(),statement_timestamp()+interval '5 minutes',statement_timestamp()+interval '1 hour',1024 FROM dev_logs.producers");
                    sql.execute("UPDATE dev_logs.capacity SET reserved_bytes=3074");
                }
                try(var input=JdbcLogProducersTest.class.getResourceAsStream("/db/logging-migration/"+name)) {
                    sql.execute(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8));
                }
            }
            try(var rows=sql.executeQuery("SELECT credential_sha256,reserved_bytes FROM dev_logs.scopes")) {
                assertTrue(rows.next()); assertNull(rows.getString(1)); assertEquals(1024,rows.getLong(2)); assertFalse(rows.next());
            }
            try(var rows=sql.executeQuery("SELECT reserved_bytes FROM dev_logs.capacity")) {
                assertTrue(rows.next()); assertEquals(3074,rows.getLong(1));
            }
            sql.execute("REVOKE ALL ON DATABASE "+DATABASE+" FROM PUBLIC");
            sql.execute("GRANT CONNECT ON DATABASE "+DATABASE+" TO "+READER);
            sql.execute("GRANT USAGE ON SCHEMA dev_logs TO "+READER);
            sql.execute("GRANT SELECT ON dev_logs.events TO "+READER);
        }
        reader=source(DATABASE,READER,PASSWORD); registry=new JdbcLogProducers(owner);
    }
    @AfterAll static void cleanup() throws Exception {
        if(admin==null)return;
        try(var connection=admin.getConnection();var sql=connection.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS "+DATABASE+" WITH (FORCE)");
            sql.execute("DROP ROLE IF EXISTS "+READER);
        }
    }
    @BeforeEach void seed() throws Exception {
        sql("TRUNCATE dev_logs.events,dev_logs.scopes,dev_logs.producers,dev_logs.manifests,dev_logs.capacity");
        sql("INSERT INTO dev_logs.capacity VALUES(true,0)");
        artifact=install("browser-bff");
    }
    ArtifactRegistration install(String component) {
        var build=new BuildIdentity(UUID.randomUUID(),component,"b".repeat(40),"c".repeat(64));
        // Synthetic verified-port fixture; parser-to-registry proof lives in LoggingAssemblyTest.
        byte[] bytes=("{\"verifiedProducerTest\":\""+build.buildId()+"\"}").getBytes(StandardCharsets.UTF_8);
        var manifest=new VerifiedLogManifest(build,VerifiedLogManifest.sha256(bytes),"d".repeat(64),bytes);
        new JdbcLogManifests(owner).install(manifest,RequestDeadline.start());
        return new ArtifactRegistration(new ProducerBinding(build,UUID.randomUUID()),manifest.digest(),manifest.vocabularyDigest());
    }
    static LogScope.Description tenant(String id) { return new LogScope.Description(LogScope.Kind.TENANT,"https://issuer.invalid","actor",id); }
    Registrar registrar() { return new Registrar(UUID.randomUUID(),Instant.now().plusSeconds(7200)); }
    Issued register() { return registry.register(registrar(),artifact,tenant,Instant.now().plusSeconds(3600),RequestDeadline.start()); }
    Delegator grant(UUID parent, Set<String> bridges) {
        return new Delegator(UUID.randomUUID(),Instant.now().plusSeconds(3600),Set.of(parent),bridges,
            Set.of(new ScopePermission(LogScope.Kind.TENANT,tenant.issuer(),"tenant-a"),new ScopePermission(LogScope.Kind.TENANT,tenant.issuer(),"tenant-b")));
    }
    Issued delegate(Issued parent, ArtifactRegistration target, LogScope.Description scope, Set<String> bridges) {
        return registry.delegate(grant(parent.authority().producerId(),bridges),parent.authority().producerId(),target,scope,
            UUID.randomUUID(),Instant.now().plusSeconds(120),RequestDeadline.start());
    }
    void sql(String text) throws SQLException { try(var c=owner.getConnection();var s=c.createStatement()){s.execute(text);} }
    long count(String text) throws SQLException { try(var c=owner.getConnection();var s=c.createStatement();var r=s.executeQuery(text)){assertTrue(r.next());return r.getLong(1);} }
    void fails(LogFailure.Kind kind,org.junit.jupiter.api.function.Executable call) { assertEquals(kind,assertThrows(LogFailure.class,call).kind()); }

    @Test void registrationCommitsParentAndScopedHashWithExactMicrosecondReplayAndCapacity() throws Exception {
        long before=count("SELECT reserved_bytes FROM dev_logs.capacity");
        Instant expiry=Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS).plusNanos(123456789);
        var issued=registry.register(registrar(),artifact,tenant,expiry,RequestDeadline.start());
        assertEquals(expiry.truncatedTo(ChronoUnit.MICROS),issued.authority().replayUntil());
        assertEquals(issued.authority(),registry.authenticate(issued.producerToken(),RequestDeadline.start()));
        assertEquals(before+2048,count("SELECT reserved_bytes FROM dev_logs.capacity"));
        assertEquals(1,count("SELECT count(*) FROM dev_logs.producers")); assertEquals(1,count("SELECT count(*) FROM dev_logs.scopes"));
        try(var c=owner.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT credential_sha256,context_epoch,capture_until,created_at FROM dev_logs.scopes")) {
            assertTrue(r.next()); assertEquals(VerifiedLogManifest.sha256(issued.producerToken().getBytes(StandardCharsets.US_ASCII)),r.getString(1));
            assertNotEquals(issued.producerToken(),r.getString(1)); assertNotNull(r.getObject(2));
            assertEquals(issued.captureUntil(),r.getTimestamp(3).toInstant());
            assertEquals(r.getTimestamp(4).toInstant().plusSeconds(300),issued.captureUntil());
        }
        var second=register(); assertNotEquals(issued.producerToken(),second.producerToken());
        assertNotEquals(issued.authority().producerId(),second.authority().producerId());
        assertNotEquals(issued.authority().scope().id(),second.authority().scope().id());
        assertFalse(issued.toString().contains(issued.producerToken()));
    }
    @Test void allTrustedScopeKindsRoundTripWithoutInventingTenantClaims() {
        for(var description:List.of(tenant,new LogScope.Description(LogScope.Kind.AUTHENTICATED_UNSELECTED,tenant.issuer(),"actor",null),
                new LogScope.Description(LogScope.Kind.OPERATOR_ONLY,null,null,null),new LogScope.Description(LogScope.Kind.UNATTRIBUTED,null,null,null))) {
            var issued=registry.register(registrar(),artifact,description,Instant.now().plusSeconds(60),RequestDeadline.start());
            assertEquals(issued.authority(),registry.authenticate(issued.producerToken(),RequestDeadline.start()));
            assertEquals(issued.authority().replayUntil(),issued.captureUntil());
        }
    }
    @Test void manifestIdentityVocabularyAndExpiryAreVerifiedBeforeReservation() throws Exception {
        long before=count("SELECT reserved_bytes FROM dev_logs.capacity");
        var b=artifact.binding().build();
        for(var bad:List.of(new ArtifactRegistration(artifact.binding(),"f".repeat(64),artifact.vocabularySha256()),
                new ArtifactRegistration(artifact.binding(),artifact.manifestSha256(),"f".repeat(64)),
                new ArtifactRegistration(new ProducerBinding(new BuildIdentity(UUID.randomUUID(),b.component(),b.gitSha(),b.sourceFingerprint()),UUID.randomUUID()),artifact.manifestSha256(),artifact.vocabularySha256())))
            fails(LogFailure.Kind.FORBIDDEN,()->registry.register(registrar(),bad,tenant,Instant.now().plusSeconds(60),RequestDeadline.start()));
        for(var expiry:List.of(Instant.now().minusSeconds(1),Instant.now().plusSeconds(172801)))
            fails(LogFailure.Kind.INVALID,()->registry.register(registrar(),artifact,tenant,expiry,RequestDeadline.start()));
        fails(LogFailure.Kind.EXPIRED,()->registry.register(new Registrar(UUID.randomUUID(),Instant.EPOCH),artifact,tenant,Instant.now().plusSeconds(60),RequestDeadline.start()));
        assertEquals(before,count("SELECT reserved_bytes FROM dev_logs.capacity")); assertEquals(0,count("SELECT count(*) FROM dev_logs.producers"));
    }
    @Test void exactParentDelegationAndExplicitBrowserBridgeMintDistinctScopedReplayCredentials() throws Exception {
        var root=register(); long before=count("SELECT reserved_bytes FROM dev_logs.capacity");
        var child=delegate(root,artifact,tenant("tenant-b"),Set.of());
        assertEquals(root.authority().producerId(),child.authority().producerId());
        assertNotEquals(root.authority().scope().id(),child.authority().scope().id());
        assertNotEquals(root.producerToken(),child.producerToken());
        assertEquals(root.authority().replayUntil(),child.authority().replayUntil());
        assertEquals(child.authority(),registry.authenticate(child.producerToken(),RequestDeadline.start()));
        assertEquals(before+1024,count("SELECT reserved_bytes FROM dev_logs.capacity"));
        var browser=install("admin-portal");
        var bridge=delegate(root,browser,tenant,Set.of(browser.manifestSha256()));
        assertEquals(root.authority().producerId(),bridge.authority().producerId());
        assertEquals(browser.binding(),registry.authenticate(bridge.producerToken(),RequestDeadline.start()).binding());
        assertEquals(1,count("SELECT count(*) FROM dev_logs.producers"));
    }
    @Test void delegationRejectsForeignParentRunManifestIssuerTenantAndUnattestedEpoch() throws Exception {
        var root=register(); var id=root.authority().producerId(); long before=count("SELECT reserved_bytes FROM dev_logs.capacity");
        var otherRun=new ArtifactRegistration(new ProducerBinding(artifact.binding().build(),UUID.randomUUID()),artifact.manifestSha256(),artifact.vocabularySha256());
        var browser=install("admin-portal"); long afterInstall=count("SELECT reserved_bytes FROM dev_logs.capacity");
        for(var target:List.of(otherRun,browser)) fails(LogFailure.Kind.FORBIDDEN,()->delegate(root,target,tenant,Set.of()));
        for(var scope:List.of(tenant("tenant-c"),new LogScope.Description(LogScope.Kind.TENANT,"https://other.invalid","actor","tenant-a")))
            fails(LogFailure.Kind.FORBIDDEN,()->delegate(root,artifact,scope,Set.of()));
        fails(LogFailure.Kind.FORBIDDEN,()->registry.delegate(grant(UUID.randomUUID(),Set.of()),id,artifact,tenant,UUID.randomUUID(),Instant.now().plusSeconds(60),RequestDeadline.start()));
        fails(LogFailure.Kind.INVALID,()->registry.delegate(grant(id,Set.of()),id,artifact,tenant,new UUID(0,0),Instant.now().plusSeconds(60),RequestDeadline.start()));
        for(var capture:List.of(Instant.now().minusSeconds(1),Instant.now().plusSeconds(301)))
            fails(LogFailure.Kind.INVALID,()->registry.delegate(grant(id,Set.of()),id,artifact,tenant,UUID.randomUUID(),capture,RequestDeadline.start()));
        var expired=new Delegator(UUID.randomUUID(),Instant.EPOCH,Set.of(id),Set.of(),grant(id,Set.of()).permissions());
        fails(LogFailure.Kind.EXPIRED,()->registry.delegate(expired,id,artifact,tenant,UUID.randomUUID(),Instant.now().plusSeconds(60),RequestDeadline.start()));
        var shortGrant=new Delegator(UUID.randomUUID(),Instant.now().plusSeconds(30),Set.of(id),Set.of(),grant(id,Set.of()).permissions());
        fails(LogFailure.Kind.INVALID,()->registry.delegate(shortGrant,id,artifact,tenant,UUID.randomUUID(),Instant.now().plusSeconds(60),RequestDeadline.start()));
        assertTrue(afterInstall>before); assertEquals(afterInstall,count("SELECT reserved_bytes FROM dev_logs.capacity"));
        assertEquals(1,count("SELECT count(*) FROM dev_logs.scopes"));
    }
    @Test void revokeIsIdempotentPreservesRowsAndDeniesAllChildTokens() throws Exception {
        var root=register(); var child=delegate(root,artifact,tenant,Set.of()); long before=count("SELECT reserved_bytes FROM dev_logs.capacity");
        for(int i=0;i<2;i++) registry.revoke(registrar(),root.authority().producerId(),RequestDeadline.start());
        registry.revoke(registrar(),UUID.randomUUID(),RequestDeadline.start());
        for(var issued:List.of(root,child)) fails(LogFailure.Kind.FORBIDDEN,()->registry.authenticate(issued.producerToken(),RequestDeadline.start()));
        fails(LogFailure.Kind.FORBIDDEN,()->delegate(root,artifact,tenant,Set.of()));
        fails(LogFailure.Kind.EXPIRED,()->registry.revoke(new Registrar(UUID.randomUUID(),Instant.EPOCH),root.authority().producerId(),RequestDeadline.start()));
        assertEquals(2,count("SELECT count(*) FROM dev_logs.scopes")); assertEquals(before,count("SELECT reserved_bytes FROM dev_logs.capacity"));
    }
    @Test void malformedUnknownLegacyAndExpiredCredentialsFailClosed() throws Exception {
        var root=register();
        for(String invalid:new String[]{null,"",root.producerToken()+"=","!".repeat(43),"A".repeat(42)+"B","A".repeat(43)})
            fails(LogFailure.Kind.UNAUTHENTICATED,()->registry.authenticate(invalid,RequestDeadline.start()));
        sql("UPDATE dev_logs.scopes SET credential_sha256=NULL");
        fails(LogFailure.Kind.UNAUTHENTICATED,()->registry.authenticate(root.producerToken(),RequestDeadline.start()));
        var fresh=register(); final String token=fresh.producerToken();
        sql("UPDATE dev_logs.producers SET created_at=clock_timestamp()-interval '2 hours',accept_until=clock_timestamp()-interval '1 hour'");
        fails(LogFailure.Kind.EXPIRED,()->registry.authenticate(token,RequestDeadline.start()));
        var expiredRoot=fresh; fails(LogFailure.Kind.EXPIRED,()->delegate(expiredRoot,artifact,tenant,Set.of()));
    }
    @Test void delegatedCaptureCannotOutliveParentAndExpiredReplayCannotAuthenticate() throws Exception {
        var root=registry.register(registrar(),artifact,tenant,Instant.now().plusSeconds(60),RequestDeadline.start());
        var id=root.authority().producerId();
        fails(LogFailure.Kind.INVALID,()->registry.delegate(grant(id,Set.of()),id,artifact,tenant,UUID.randomUUID(),Instant.now().plusSeconds(90),RequestDeadline.start()));
        var child=registry.delegate(grant(id,Set.of()),id,artifact,tenant,UUID.randomUUID(),Instant.now().plusSeconds(30).plusNanos(123),RequestDeadline.start());
        assertEquals(root.authority().replayUntil(),child.authority().replayUntil());
        assertEquals(child.authority(),registry.authenticate(child.producerToken(),RequestDeadline.start()));
        sql("UPDATE dev_logs.scopes SET created_at=statement_timestamp()-interval '1 hour',capture_until=statement_timestamp()-interval '55 minutes',replay_until=statement_timestamp()-interval '30 minutes' WHERE scope_id='"+child.authority().scope().id()+"'");
        fails(LogFailure.Kind.EXPIRED,()->registry.authenticate(child.producerToken(),RequestDeadline.start()));
        assertEquals(root.authority(),registry.authenticate(root.producerToken(),RequestDeadline.start()));
    }
    @Test void scopedAuthenticationFeedsRealPersistenceWithoutCrossTenantReceipts() {
        var root=register(); var child=delegate(root,artifact,tenant("tenant-b"),Set.of());
        var a=registry.authenticate(root.producerToken(),RequestDeadline.start()); var b=registry.authenticate(child.producerToken(),RequestDeadline.start());
        var event=new CanonicalLogEvent(new EventMetadata(UUID.randomUUID(),new ExecutionIdentity(artifact.binding().build(),artifact.binding().runId(),UUID.randomUUID()),
            Instant.now(),Severity.DEBUG,EventCode.OPERATION_STARTED,UUID.randomUUID(),new TraceContext("1".repeat(32),"2".repeat(16))),"{}".getBytes(StandardCharsets.UTF_8));
        var store=new JdbcLogPersistence(owner,()->{}); var batch=new LogBatch(List.of(event));
        store.commit(a,batch,Instant.now());
        assertEquals(LogPersistence.ReceiptState.UNKNOWN,store.receipts(b,List.of(event.metadata().eventId()),Instant.now()).getFirst().state());
        store.commit(b,batch,Instant.now());
        assertEquals(LogPersistence.ReceiptState.COMMITTED,store.receipts(a,List.of(event.metadata().eventId()),Instant.now()).getFirst().state());
        assertEquals(LogPersistence.ReceiptState.COMMITTED,store.receipts(b,List.of(event.metadata().eventId()),Instant.now()).getFirst().state());
    }
    @Test void capacityAndInsertFailureRollbackParentScopeAndReservation() throws Exception {
        long before=count("SELECT reserved_bytes FROM dev_logs.capacity");
        sql("ALTER TABLE dev_logs.scopes ADD CONSTRAINT reject_test_scope CHECK(kind<>'TENANT') NOT VALID");
        try {
            fails(LogFailure.Kind.UNAVAILABLE,()->register());
            assertEquals(0,count("SELECT count(*) FROM dev_logs.producers")); assertEquals(before,count("SELECT reserved_bytes FROM dev_logs.capacity"));
        } finally { sql("ALTER TABLE dev_logs.scopes DROP CONSTRAINT reject_test_scope"); }
        var root=register(); sql("UPDATE dev_logs.capacity SET reserved_bytes=1073741824");
        fails(LogFailure.Kind.CAPACITY,()->register()); fails(LogFailure.Kind.CAPACITY,()->delegate(root,artifact,tenant,Set.of()));
        assertEquals(1,count("SELECT count(*) FROM dev_logs.scopes")); assertEquals(1073741824,count("SELECT reserved_bytes FROM dev_logs.capacity"));
        sql("DELETE FROM dev_logs.capacity"); fails(LogFailure.Kind.UNAVAILABLE,()->register());
    }
    @Test void readerCannotRegisterAuthenticateOrRevokeAndExpiredDeadlineHasNoSideEffects() throws Exception {
        var root=register(); var denied=new JdbcLogProducers(reader);
        fails(LogFailure.Kind.UNAVAILABLE,()->denied.register(registrar(),artifact,tenant,Instant.now().plusSeconds(60),RequestDeadline.start()));
        fails(LogFailure.Kind.UNAVAILABLE,()->denied.authenticate(root.producerToken(),RequestDeadline.start()));
        fails(LogFailure.Kind.UNAVAILABLE,()->denied.revoke(registrar(),root.authority().producerId(),RequestDeadline.start()));
        var deadline=RequestDeadline.start(); Thread.sleep(5050);
        fails(LogFailure.Kind.UNAVAILABLE,()->registry.register(registrar(),artifact,tenant,Instant.now().plusSeconds(60),deadline));
        assertEquals(1,count("SELECT count(*) FROM dev_logs.producers"));
    }
    @Test void parentAndScopeCountBoundsRejectOnlyNewRegistryEntries() throws Exception {
        var root=register(); String parent=root.authority().producerId().toString();
        sql("INSERT INTO dev_logs.producers SELECT gen_random_uuid(),manifest_sha,gen_random_uuid(),created_at,accept_until,false,1024 FROM dev_logs.producers CROSS JOIN generate_series(1,4095) WHERE producer_id='"+parent+"'");
        sql("UPDATE dev_logs.capacity SET reserved_bytes=reserved_bytes+4095*1024");
        fails(LogFailure.Kind.CAPACITY,()->register());
        var child=delegate(root,artifact,tenant,Set.of()); assertEquals(child.authority(),registry.authenticate(child.producerToken(),RequestDeadline.start()));
        // Preserve the real 131072-row boundary without one oversized fixture statement.
        // Each setup call retains the existing five-second socket bound; application deadlines are unchanged.
        for(int inserted=0;inserted<131070;inserted+=4096) {
            int amount=Math.min(4096,131070-inserted);
            sql("INSERT INTO dev_logs.scopes(scope_id,producer_id,target_manifest_sha,target_run_id,context_epoch,kind,issuer,subject,tenant_id,created_at,capture_until,replay_until,reserved_bytes) SELECT gen_random_uuid(),producer_id,target_manifest_sha,target_run_id,gen_random_uuid(),kind,issuer,subject,tenant_id,created_at,capture_until,replay_until,1024 FROM dev_logs.scopes CROSS JOIN generate_series(1,"+amount+") WHERE scope_id='"+root.authority().scope().id()+"'");
        }
        sql("UPDATE dev_logs.capacity SET reserved_bytes=reserved_bytes+131070::bigint*1024");
        assertEquals(131072,count("SELECT count(*) FROM dev_logs.scopes"));
        fails(LogFailure.Kind.CAPACITY,()->delegate(root,artifact,tenant,Set.of()));
        assertEquals(root.authority(),registry.authenticate(root.producerToken(),RequestDeadline.start()));
    }
    @Test void scopedCatalogLookupUsesOriginalInstalledBridgeAndRejectsForgedOrCorruptEnvelope() throws Exception {
        var root=register();var installed=registry.load(root.authority(),RequestDeadline.start());
        assertEquals(artifact.manifestSha256(),installed.digest());assertEquals(artifact.vocabularySha256(),installed.vocabularyDigest());
        assertEquals(artifact.binding().build(),installed.build());
        var browser=install("admin-portal");var child=delegate(root,browser,tenant,Set.of(browser.manifestSha256()));
        assertEquals(browser.manifestSha256(),registry.load(child.authority(),RequestDeadline.start()).digest());
        var a=root.authority();
        for(var forged:List.of(new ProducerScope(UUID.randomUUID(),a.scope(),a.binding(),a.replayUntil()),
                new ProducerScope(a.producerId(),tenant("tenant-b").identified(a.scope().id()),a.binding(),a.replayUntil()),
                new ProducerScope(a.producerId(),tenant.identified(UUID.randomUUID()),a.binding(),a.replayUntil()),
                new ProducerScope(a.producerId(),a.scope(),a.binding(),a.replayUntil().plusSeconds(1))))
            fails(LogFailure.Kind.FORBIDDEN,()->registry.load(forged,RequestDeadline.start()));
        sql("UPDATE dev_logs.manifests SET manifest_bytes=decode('7b7d','hex') WHERE manifest_sha='"+artifact.manifestSha256()+"'");
        fails(LogFailure.Kind.UNAVAILABLE,()->registry.load(a,RequestDeadline.start()));
        fails(LogFailure.Kind.UNAVAILABLE,()->new JdbcLogProducers(reader).load(child.authority(),RequestDeadline.start()));
        registry.revoke(registrar(),a.producerId(),RequestDeadline.start());
        fails(LogFailure.Kind.FORBIDDEN,()->registry.load(child.authority(),RequestDeadline.start()));
    }

}
