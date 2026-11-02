package org.documentknowledge.devlogging.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.documentknowledge.devlogging.adapters.outbound.persistence.JdbcReadRoleVerifier;
import org.documentknowledge.devlogging.adapters.outbound.persistence.JdbcWriteRoleVerifier;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP -> hash-only dedicated reader -> pooled real PostgreSQL; no mock store or owner in service. */
class LoggingAssemblyTest {
    static PGSimpleDataSource owner;
    static final String password=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
    static final String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
    static final String expired=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
    static final UUID build=UUID.randomUUID(), other=UUID.randomUUID(), event=UUID.randomUUID();
    @TempDir Path directory;
    static void sql(String text) throws Exception { try(var c=owner.getConnection();var s=c.createStatement()){s.execute(text);} }
    @BeforeAll static void ownedFixture() throws Exception {
        owner=new PGSimpleDataSource();owner.setURL(Objects.requireNonNull(System.getenv("DKP_LOG_TEST_JDBC"),"Owned PostgreSQL required"));
        owner.setUser("postgres");owner.setPassword(System.getenv("DKP_LOG_TEST_PASSWORD"));owner.setConnectTimeout(1);owner.setSocketTimeout(5);
        sql("DROP SCHEMA IF EXISTS dev_logs CASCADE");
        try(var input=LoggingAssemblyTest.class.getResourceAsStream("/db/logging-migration/V1__owned_dev_logging.sql")) {
            sql(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8));
        }
        sql("CREATE TABLE dev_logs.schema_migrations(version integer PRIMARY KEY,migration_sha char(64) NOT NULL)");
        sql("REVOKE ALL ON DATABASE dkp_dev_logs FROM PUBLIC");sql("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        sql("CREATE ROLE dkp_log_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+password+"'");
        sql("GRANT CONNECT ON DATABASE dkp_dev_logs TO dkp_log_reader");
        sql("GRANT USAGE ON SCHEMA dev_logs TO dkp_log_reader");sql("GRANT SELECT ON dev_logs.events TO dkp_log_reader");
        seed(build,event,"tenant-a");seed(other,UUID.randomUUID(),"tenant-a");seed(build,UUID.randomUUID(),"tenant-b");
    }
    static void seed(UUID buildId,UUID eventId,String tenant) throws Exception {
        try(var c=owner.getConnection();var s=c.prepareStatement("""
            INSERT INTO dev_logs.events(producer_id,scope_id,event_id,build_id,run_id,instance_id,component,git_sha,source_fingerprint,
                event_time,received_at,severity,event_name,correlation_id,scope_kind,actor_issuer,actor_subject,tenant_id,
                canonical_hash,canonical_payload,retain_until,reserved_bytes)
            VALUES (?,?,?,?,?,?,'browser-bff',?,?,clock_timestamp(),clock_timestamp(),'DEBUG','runtime.started',?,
                'TENANT','https://issuer.invalid','fixture',?,?,?,clock_timestamp()+interval '8 days',2048)
            """)) {
            s.setObject(1,UUID.randomUUID());s.setObject(2,UUID.randomUUID());s.setObject(3,eventId);s.setObject(4,buildId);
            s.setObject(5,UUID.randomUUID());s.setObject(6,UUID.randomUUID());s.setString(7,"a".repeat(40));s.setString(8,"b".repeat(64));
            s.setObject(9,UUID.randomUUID());s.setString(10,tenant);s.setString(11,"c".repeat(64));
            s.setBytes(12,("{\"fixture\":\"retained\",\"eventId\":\""+eventId+"\",\"buildId\":\""+buildId+"\"}").getBytes(StandardCharsets.UTF_8));s.executeUpdate();
        }
    }
    Path file(String name,String value) throws Exception {
        var p=directory.resolve(name);Files.writeString(p,value);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));return p;
    }
    String grant(String credential, Instant expiry, boolean operator) throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.US_ASCII)));
        return "{\"id\":\""+UUID.randomUUID()+"\",\"operator\":"+operator+",\"expiresAt\":\""+expiry+"\",\"tokenSha256\":\""+hash+"\""
            +(operator ? "" : ",\"issuer\":\"https://issuer.invalid\",\"tenantId\":\"tenant-a\"")+"}";
    }
    Map<String,Object> properties() throws Exception {
        return Map.of("micronaut.server.host","127.0.0.1","micronaut.server.port",-1,
            "micronaut.server.netty.worker.threads",2,"micronaut.server.netty.parent.threads",1,
            "dkp.logging.jdbc-url",System.getenv("DKP_LOG_TEST_JDBC"),"dkp.logging.db-password-file",file("password",password).toString(),
            "dkp.logging.cursor-key-file",file("key","d".repeat(64)).toString(),
            "dkp.logging.reader-grants-file",file("readers","{\"schemaVersion\":1,\"readers\":["+
                grant(token,Instant.now().plusSeconds(3600),false)+","+grant(expired,Instant.now().plusSeconds(3600),true)+"]}").toString());
    }
    HttpResponse<String> get(EmbeddedServer server,String credential,String suffix) throws Exception {
        var now=Instant.now();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getPort()+"/api/v1/dev-logs/events?from="+
            now.minusSeconds(300)+"&to="+now.plusSeconds(60)+suffix)).timeout(Duration.ofSeconds(8));
        if(credential!=null)request.header("Authorization","Bearer "+credential);
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void actualHttpUsesPooledReadonlyDatabaseAndSurvivesServiceRestart() throws Exception {
        var properties=properties();
        for(int restart=0;restart<2;restart++) {
            HikariDataSource pool;
            try(var context=ApplicationContext.run(properties);var server=context.getBean(EmbeddedServer.class).start()) {
                pool=context.getBean(HikariDataSource.class);
                assertEquals("dkp_log_reader",pool.getUsername());assertEquals(32,pool.getMaximumPoolSize());
                assertEquals(0,pool.getMinimumIdle());assertEquals(1000,pool.getConnectionTimeout());assertEquals(500,pool.getValidationTimeout());
                assertEquals("127.0.0.1",server.getHost());
                var response=get(server,token,"&buildId="+build);
                assertEquals(200,response.statusCode());assertTrue(response.body().contains(event.toString()));
                assertFalse(response.body().contains("tenant-b"));assertFalse(response.body().contains(other.toString()));
                assertEquals(401,get(server,null,"").statusCode());assertEquals(401,get(server,"x".repeat(64),"").statusCode());
                assertFalse(response.body().contains(password));assertFalse(response.body().contains(token));
                var empty=get(server,token,"&buildId="+UUID.randomUUID());
                assertEquals(200,empty.statusCode());
                var emptyPage=context.getBean(io.micronaut.json.JsonMapper.class).readValue(empty.body(),Map.class);
                assertEquals(java.util.List.of(),emptyPage.get("events"));
                try(var c=pool.getConnection();var s=c.createStatement()) {
                    assertThrows(SQLException.class,()->s.execute("DELETE FROM dev_logs.events"));
                    assertThrows(SQLException.class,()->s.executeQuery("SELECT * FROM dev_logs.producers"));
                }
            }
            assertTrue(pool.isClosed());
        }
    }
    @Test void poolExhaustionHasFiniteAcquisitionAndOrdinaryReleaseRecovers() throws Exception {
        try(var context=ApplicationContext.run(properties());var server=context.getBean(EmbeddedServer.class).start()) {
            var pool=context.getBean(HikariDataSource.class);var leases=new ArrayList<Connection>();
            try {
                for(int i=0;i<32;i++)leases.add(pool.getConnection());
                long started=System.nanoTime();
                assertEquals(503,get(server,token,"").statusCode());
                assertTrue(System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
            } finally { for(var c:leases)c.close(); }
            assertEquals(200,get(server,token,"&buildId="+build).statusCode());
        }
    }
    @Test void writerIsExplicitlyDisabledAndInvalidOrIncompleteEnablementFailsClosed() throws Exception {
        for(boolean explicit:List.of(false,true)) {
            var config=new HashMap<>(properties());if(explicit)config.put("dkp.logging.writer-enabled",false);
            try(var context=ApplicationContext.run(config)) {
                assertFalse(context.containsBean(LogProducerRegistry.class));assertFalse(context.containsBean(LogPersistence.class));
                assertFalse(context.containsBean(LogObserverAuthentication.class));assertFalse(context.containsBean(PrivateWriterConfiguration.class));
            }
        }
        var bad=new HashMap<>(properties());bad.put("dkp.logging.writer-enabled","yes");
        assertTrue(assertThrows(Exception.class,()->ApplicationContext.run(bad)).getMessage().contains("LOG_CONFIGURATION_UNAVAILABLE"));
        var incomplete=new HashMap<>(properties());incomplete.put("dkp.logging.writer-enabled",true);
        var failure=assertThrows(Exception.class,()->ApplicationContext.run(incomplete));
        assertTrue(failure.getMessage().contains("LOG_CONFIGURATION_UNAVAILABLE"));assertFalse(failure.getMessage().contains(password));
    }
    @Test void expiredReadGrantIsRejectedWithoutRenewal() throws Exception {
        var instant=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        Clock clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return instant.get();}
        };
        var props=new HashMap<>(properties());
        props.put("dkp.logging.reader-grants-file",file("expiring-readers","{\"schemaVersion\":1,\"readers\":["+
            grant(token,instant.get().plusSeconds(86400),false)+","+grant(expired,instant.get().plusSeconds(3600),true)+"]}").toString());
        try(var context=ApplicationContext.builder().properties(props).build()) {
            context.registerSingleton(Clock.class,clock);context.start();
            try(var server=context.getBean(EmbeddedServer.class).start()) {
                assertEquals(200,get(server,expired,"").statusCode());
                instant.updateAndGet(value->value.plusSeconds(7200));
                assertEquals(403,get(server,expired,"").statusCode());assertEquals(200,get(server,token,"").statusCode());
            }
        }
    }
    @Test void effectivePublicColumnMembershipAndElevatedPrivilegesFailStartupProbe() throws Exception {
        try(var context=ApplicationContext.run(properties())) {
            var pool=context.getBean(HikariDataSource.class);var verifier=new JdbcReadRoleVerifier(pool);
            verifier.verify(RequestDeadline.start());
            for(var mutation:List.of(
                    new String[]{"GRANT UPDATE ON dev_logs.events TO PUBLIC","REVOKE UPDATE ON dev_logs.events FROM PUBLIC"},
                    new String[]{"GRANT UPDATE(component) ON dev_logs.events TO dkp_log_reader","REVOKE UPDATE(component) ON dev_logs.events FROM dkp_log_reader"},
                    new String[]{"GRANT SELECT ON dev_logs.producers TO PUBLIC","REVOKE SELECT ON dev_logs.producers FROM PUBLIC"},
                    new String[]{"GRANT TEMPORARY ON DATABASE dkp_dev_logs TO PUBLIC","REVOKE TEMPORARY ON DATABASE dkp_dev_logs FROM PUBLIC"},
                    new String[]{"ALTER ROLE dkp_log_reader SUPERUSER","ALTER ROLE dkp_log_reader NOSUPERUSER"},
                    new String[]{"CREATE ROLE logger_elevated; GRANT logger_elevated TO dkp_log_reader","REVOKE logger_elevated FROM dkp_log_reader; DROP ROLE logger_elevated"})) {
                sql(mutation[0]);
                try { assertEquals(LogFailure.Kind.UNAVAILABLE,assertThrows(LogFailure.class,()->verifier.verify(RequestDeadline.start())).kind()); }
                finally {sql(mutation[1]);}
                verifier.verify(RequestDeadline.start());
            }
        }
    }
    @Test void exactWriterPrivilegesSupportLocksAndRejectMissingExcessPublicGrantOptionAndOwnership() throws Exception {
        try(var input=LoggingAssemblyTest.class.getResourceAsStream("/db/logging-provision/write-role.sql")) {
            sql(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8).replace("__WRITER_PASSWORD__",password));
        }
        var writer=new PGSimpleDataSource();writer.setURL(owner.getURL());writer.setUser("dkp_log_writer");
        writer.setPassword(password);writer.setConnectTimeout(1);writer.setSocketTimeout(5);
        var verifier=new JdbcWriteRoleVerifier(writer);
        try {
            verifier.verify(RequestDeadline.start());
            // Row-locking SELECT requires column UPDATE even though this statement never mutates it.
            try(var c=writer.getConnection()) {
                c.setAutoCommit(false);
                try(var s=c.createStatement()) {
                    s.executeQuery("SELECT reserved_bytes FROM dev_logs.capacity FOR UPDATE").close();
                    s.executeQuery("SELECT scope_id FROM dev_logs.scopes FOR SHARE").close();
                    s.executeQuery("SELECT producer_id FROM dev_logs.producers FOR SHARE").close();
                    assertThrows(SQLException.class,()->s.execute("UPDATE dev_logs.scopes SET tenant_id=NULL"));
                } finally {c.rollback();}
            }
            for(var mutation:List.of(
                    new String[]{"REVOKE SELECT ON dev_logs.manifests FROM dkp_log_writer","GRANT SELECT ON dev_logs.manifests TO dkp_log_writer"},
                    new String[]{"REVOKE UPDATE(reserved_bytes) ON dev_logs.scopes FROM dkp_log_writer","GRANT UPDATE(reserved_bytes) ON dev_logs.scopes TO dkp_log_writer"},
                    new String[]{"REVOKE USAGE ON SEQUENCE dev_logs.events_sequence_seq FROM dkp_log_writer","GRANT USAGE ON SEQUENCE dev_logs.events_sequence_seq TO dkp_log_writer"},
                    new String[]{"GRANT SELECT ON dev_logs.schema_migrations TO dkp_log_writer","REVOKE SELECT ON dev_logs.schema_migrations FROM dkp_log_writer"},
                    new String[]{"GRANT UPDATE ON dev_logs.events TO dkp_log_writer","REVOKE UPDATE ON dev_logs.events FROM dkp_log_writer"},
                    new String[]{"GRANT UPDATE(tenant_id) ON dev_logs.scopes TO dkp_log_writer","REVOKE UPDATE(tenant_id) ON dev_logs.scopes FROM dkp_log_writer"},
                    new String[]{"GRANT DELETE ON dev_logs.events TO PUBLIC","REVOKE DELETE ON dev_logs.events FROM PUBLIC"},
                    new String[]{"GRANT SELECT ON dev_logs.events TO dkp_log_writer WITH GRANT OPTION","REVOKE GRANT OPTION FOR SELECT ON dev_logs.events FROM dkp_log_writer"},
                    new String[]{"GRANT UPDATE(reserved_bytes) ON dev_logs.capacity TO dkp_log_writer WITH GRANT OPTION","REVOKE GRANT OPTION FOR UPDATE(reserved_bytes) ON dev_logs.capacity FROM dkp_log_writer"},
                    new String[]{"GRANT SELECT ON SEQUENCE dev_logs.events_sequence_seq TO dkp_log_writer","REVOKE SELECT ON SEQUENCE dev_logs.events_sequence_seq FROM dkp_log_writer"},
                    new String[]{"GRANT USAGE ON SEQUENCE dev_logs.events_sequence_seq TO dkp_log_writer WITH GRANT OPTION","REVOKE GRANT OPTION FOR USAGE ON SEQUENCE dev_logs.events_sequence_seq FROM dkp_log_writer"},
                    new String[]{"GRANT TEMPORARY ON DATABASE dkp_dev_logs TO PUBLIC","REVOKE TEMPORARY ON DATABASE dkp_dev_logs FROM PUBLIC"},
                    new String[]{"GRANT USAGE ON SCHEMA dev_logs TO dkp_log_writer WITH GRANT OPTION","REVOKE GRANT OPTION FOR USAGE ON SCHEMA dev_logs FROM dkp_log_writer"},
                    new String[]{"GRANT CONNECT ON DATABASE dkp_dev_logs TO dkp_log_writer WITH GRANT OPTION","REVOKE GRANT OPTION FOR CONNECT ON DATABASE dkp_dev_logs FROM dkp_log_writer"},
                    new String[]{"CREATE TABLE dev_logs.writer_extra(id integer); GRANT SELECT ON dev_logs.writer_extra TO dkp_log_writer","DROP TABLE dev_logs.writer_extra"},
                    new String[]{"CREATE SEQUENCE dev_logs.writer_extra_seq; GRANT USAGE ON SEQUENCE dev_logs.writer_extra_seq TO dkp_log_writer","DROP SEQUENCE dev_logs.writer_extra_seq"},
                    new String[]{"ALTER TABLE dev_logs.capacity OWNER TO dkp_log_writer","ALTER TABLE dev_logs.capacity OWNER TO postgres; GRANT SELECT ON dev_logs.capacity TO dkp_log_writer; GRANT UPDATE(reserved_bytes) ON dev_logs.capacity TO dkp_log_writer"},
                    new String[]{"ALTER ROLE dkp_log_writer SUPERUSER","ALTER ROLE dkp_log_writer NOSUPERUSER"},
                    new String[]{"CREATE ROLE writer_elevated; GRANT writer_elevated TO dkp_log_writer","REVOKE writer_elevated FROM dkp_log_writer; DROP ROLE writer_elevated"})) {
                sql(mutation[0]);
                try { assertEquals(LogFailure.Kind.UNAVAILABLE,assertThrows(LogFailure.class,()->verifier.verify(RequestDeadline.start())).kind()); }
                finally {sql(mutation[1]);}
                verifier.verify(RequestDeadline.start());
            }
            var wrong=new JdbcWriteRoleVerifier(owner);
            assertEquals(LogFailure.Kind.UNAVAILABLE,assertThrows(LogFailure.class,()->wrong.verify(RequestDeadline.start())).kind());
            var unavailable=new PGSimpleDataSource();unavailable.setURL(owner.getURL());unavailable.setUser("dkp_log_writer");unavailable.setPassword("invalid");
            unavailable.setConnectTimeout(1);unavailable.setSocketTimeout(1);
            var failure=assertThrows(LogFailure.class,()->new JdbcWriteRoleVerifier(unavailable).verify(RequestDeadline.start()));
            assertEquals(LogFailure.Kind.UNAVAILABLE,failure.kind());assertNull(failure.getCause());
        } finally {sql("DROP OWNED BY dkp_log_writer; DROP ROLE dkp_log_writer");}
    }
    @Test void actualStartupRejectsMissingPrivateConfigurationWrongDatabaseAndWritePrivilege() throws Exception {
        var absent=assertThrows(Exception.class,()->ApplicationContext.run(Map.of()));
        assertTrue(absent.getMessage().contains("LOG_CONFIGURATION_UNAVAILABLE"));
        var wrong=new HashMap<>(properties());wrong.put("dkp.logging.jdbc-url","jdbc:postgresql://logging-db:5432/business");
        assertThrows(Exception.class,()->ApplicationContext.run(wrong));
        sql("GRANT INSERT ON dev_logs.events TO dkp_log_reader");
        try { var failure=assertThrows(Exception.class,()->ApplicationContext.run(properties()));
            assertTrue(failure.getMessage().contains("LOG_DATABASE_UNAVAILABLE"));assertFalse(failure.getMessage().contains(password)); }
        finally {sql("REVOKE INSERT ON dev_logs.events FROM dkp_log_reader");}
    }
    @Test void privateReviewedManifestInstallsThroughApplicationPortIntoOwnedRealDatabase() throws Exception {
        var bytes=PrivateLogManifest.canonical(PrivateLogManifestTest.manifest(PrivateLogManifestTest.vocabulary()));
        String digest=org.documentknowledge.devlogging.domain.VerifiedLogManifest.sha256(bytes);
        var path=file("reviewed-manifest.json",new String(bytes,StandardCharsets.UTF_8));
        var verified=PrivateLogManifest.read(path,digest);
        LogManifestRegistry registry=new org.documentknowledge.devlogging.adapters.outbound.persistence.JdbcLogManifests(owner);
        var installed=registry.install(verified,RequestDeadline.start());
        assertEquals(digest,installed.manifestSha256());
        assertEquals(installed,registry.install(verified,RequestDeadline.start()));
        try(var connection=owner.getConnection();var statement=connection.prepareStatement("SELECT manifest_bytes,build_id FROM dev_logs.manifests WHERE manifest_sha=?")) {
            statement.setString(1,digest);try(var rows=statement.executeQuery()) {
                assertTrue(rows.next());assertArrayEquals(bytes,rows.getBytes(1));
                assertEquals(verified.build().buildId(),rows.getObject(2,UUID.class));assertFalse(rows.next());
            }
        }
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void enabledWriterBootstrapUsesPrivateGrantsRealRolesAndObserverAcrossRestartAndRevocation() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/logging-migration/V2__scoped_producer_credentials.sql")) {
            sql(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8));
        }
        try(var input=getClass().getResourceAsStream("/db/logging-provision/write-role.sql")) {
            sql(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8).replace("__WRITER_PASSWORD__","w"+password));
        }
        try {
        var buildIdentity=new org.documentknowledge.observability.BuildIdentity(UUID.randomUUID(),"browser-bff","a".repeat(40),"b".repeat(64));
        var manifestRow=PrivateLogManifestTest.manifest(PrivateLogManifestTest.vocabulary());
        manifestRow.put("buildId",buildIdentity.buildId().toString());manifestRow.put("component",buildIdentity.component());
        byte[] manifestBytes=PrivateLogManifest.canonical(manifestRow);
        var manifest=PrivateLogManifest.parse(manifestBytes,org.documentknowledge.devlogging.domain.VerifiedLogManifest.sha256(manifestBytes));
        new org.documentknowledge.devlogging.adapters.outbound.persistence.JdbcLogManifests(owner).install(manifest,RequestDeadline.start());
        var registry=new org.documentknowledge.devlogging.adapters.outbound.persistence.JdbcLogProducers(owner);
        String operatorToken="o".repeat(43),attestorToken="s".repeat(43);
        var registrar=new LogProducerRegistry.Registrar(UUID.randomUUID(),Instant.now().plusSeconds(3600));
        var mapper=io.micronaut.json.JsonMapper.createDefault();
        var binding=Map.of("buildId",buildIdentity.buildId().toString(),"runId",UUID.randomUUID().toString(),"component",buildIdentity.component(),
            "gitSha",buildIdentity.gitSha(),"sourceFingerprint",buildIdentity.sourceFingerprint(),"manifestSha256",manifest.digest(),"vocabularySha256",manifest.vocabularyDigest());
        var scope=Map.of("scopeKind","TENANT","issuer","https://issuer.invalid","subject","verified-actor","tenantId","tenant-a");
        java.util.function.Function<Map<String,Object>,ApplicationContext> start=grants->{
            try {
                var config=new HashMap<>(properties());config.put("dkp.logging.writer-enabled",true);
                config.put("dkp.logging.writer-password-file",file("writer-password","w"+password).toString());
                config.put("dkp.logging.registration-grants-file",file("registration-grants",new String(mapper.writeValueAsBytes(grants),StandardCharsets.UTF_8)).toString());
                var observer=serverGrant(UUID.randomUUID(),Instant.now().plusSeconds(3600),"v".repeat(43));observer.put("schemaVersion",1);
                config.put("dkp.logging.observer-grant-file",file("observer-grant",new String(mapper.writeValueAsBytes(observer),StandardCharsets.UTF_8)).toString());
                config.put("dkp.logging.volume-identity","f".repeat(64));
                return ApplicationContext.run(config);
            } catch(Exception failure) { throw new RuntimeException(failure); }
        };
        Map<?,?> root;
        UUID logEvent=UUID.randomUUID();var contextValue=Map.of("phase","STARTUP","durationMs",0,"exceptionClass","unknown.SecretClass");
        var eventValue=new HashMap<String,Object>(Map.of("eventId",logEvent.toString(),"buildId",binding.get("buildId"),"runId",binding.get("runId"),
            "instanceId",UUID.randomUUID().toString(),"component",buildIdentity.component(),"gitSha",buildIdentity.gitSha(),"sourceFingerprint",buildIdentity.sourceFingerprint(),
            "timestamp",Instant.now().toString(),"severity","DEBUG","eventName","operation.started"));
        eventValue.put("correlationId",UUID.randomUUID().toString());eventValue.put("context",contextValue);
        byte[] eventBatch=mapper.writeValueAsBytes(Map.of("schemaVersion",1,"events",List.of(eventValue)));
        byte[] receiptBody=mapper.writeValueAsBytes(Map.of("eventIds",List.of(logEvent.toString())));
        try(var context=start.apply(Map.of("schemaVersion",1,"registrars",List.of(serverGrant(registrar.id(),registrar.expiresAt(),operatorToken)),"delegators",List.of()));var server=context.getBean(EmbeddedServer.class).start()) {
            var body=mapper.writeValueAsBytes(Map.of("binding",binding,"scope",scope,"acceptUntil",Instant.now().plusSeconds(1800).toString()));
            var response=registrationRequest(server,"POST","registrations",operatorToken,body);
            assertEquals(201,response.statusCode(),response.body()); root=mapper.readValue(response.body(),Map.class);
            var authority=registry.authenticate((String)root.get("producerToken"),RequestDeadline.start());
            assertEquals(root.get("scopeId"),authority.scope().id().toString()); assertEquals("tenant-a",authority.scope().tenantId());
            assertEquals(401,registrationRequest(server,"POST","registrations",(String)root.get("producerToken"),body).statusCode());
            assertEquals("dkp_log_reader",context.getBean(HikariDataSource.class,io.micronaut.inject.qualifiers.Qualifiers.byName("log-reader")).getUsername());
            assertEquals("dkp_log_writer",context.getBean(HikariDataSource.class,io.micronaut.inject.qualifiers.Qualifiers.byName("log-writer")).getUsername());
            assertEquals(503,registrationRequest(server,"POST","batches",(String)root.get("producerToken"),eventBatch).statusCode());
            for(String wrong:List.of(token,operatorToken,"v".repeat(43)))assertEquals(401,registrationRequest(server,"POST","batches",wrong,eventBatch).statusCode());
            // Labelled trusted fixture measurement over actual HTTP; not physical shared-volume deployment proof.
            refreshObservation(server);
            var committed=registrationRequest(server,"POST","batches",(String)root.get("producerToken"),eventBatch);
            assertEquals(200,committed.statusCode(),committed.body());assertTrue(committed.body().contains("COMMITTED"));assertTrue(committed.body().contains("receivedAt"));
            assertFalse(committed.body().contains("SecretClass"));
        }
        var parent=UUID.fromString((String)root.get("producerId"));
        var delegator=new LogProducerRegistry.Delegator(UUID.randomUUID(),Instant.now().plusSeconds(3600),Set.of(parent),Set.of(),
            Set.of(new LogProducerRegistry.ScopePermission(org.documentknowledge.devlogging.domain.LogScope.Kind.TENANT,"https://issuer.invalid","tenant-a"),
                new LogProducerRegistry.ScopePermission(org.documentknowledge.devlogging.domain.LogScope.Kind.TENANT,"https://issuer.invalid","tenant-b")));
        var delegatorRow=serverGrant(delegator.id(),delegator.expiresAt(),attestorToken);
        delegatorRow.put("parents",List.of(parent.toString()));delegatorRow.put("bridgeManifests",List.of());
        delegatorRow.put("permissions",List.of(Map.of("kind","TENANT","issuer","https://issuer.invalid","tenantId","tenant-a"),Map.of("kind","TENANT","issuer","https://issuer.invalid","tenantId","tenant-b")));
        try(var context=start.apply(Map.of("schemaVersion",1,"registrars",List.of(serverGrant(registrar.id(),registrar.expiresAt(),operatorToken)),"delegators",List.of(delegatorRow)));var server=context.getBean(EmbeddedServer.class).start()) {
            var volume=context.getBean(ObservedVolumeAdmission.class);
            var retained=registrationRequest(server,"POST","receipts",(String)root.get("producerToken"),receiptBody);
            assertEquals(200,retained.statusCode());assertTrue(retained.body().contains("COMMITTED"));
            var childScope=new HashMap<>(scope);childScope.put("tenantId","tenant-b");
            var body=mapper.writeValueAsBytes(Map.of("parentProducerId",parent.toString(),"binding",binding,"scope",childScope,
                "contextEpoch",UUID.randomUUID().toString(),"captureUntil",Instant.now().plusSeconds(60).toString()));
            var response=registrationRequest(server,"POST","delegations",attestorToken,body);assertEquals(201,response.statusCode(),response.body());
            var child=mapper.readValue(response.body(),Map.class);assertEquals(parent.toString(),child.get("producerId"));
            var authority=registry.authenticate((String)child.get("producerToken"),RequestDeadline.start());assertEquals(parent,authority.producerId());
            assertNotEquals(root.get("scopeId"),authority.scope().id().toString());
            assertEquals("tenant-b",authority.scope().tenantId());
            var unknown=registrationRequest(server,"POST","receipts",(String)child.get("producerToken"),receiptBody);
            assertEquals(200,unknown.statusCode());assertTrue(unknown.body().contains("UNKNOWN"));assertFalse(unknown.body().contains("receivedAt"));
            refreshObservation(server);
            var replay=registrationRequest(server,"POST","batches",(String)root.get("producerToken"),eventBatch);assertEquals(200,replay.statusCode(),replay.body());
            var childCommit=registrationRequest(server,"POST","batches",(String)child.get("producerToken"),eventBatch);assertEquals(200,childCommit.statusCode(),childCommit.body());
            var visible=get(server,token,"&buildId="+buildIdentity.buildId());assertEquals(200,visible.statusCode());
            var page=mapper.readValue(visible.body(),Map.class);assertEquals(1,((List<?>)page.get("events")).size());
            assertFalse(visible.body().contains("tenant-b"));assertFalse(visible.body().contains("SecretClass"));assertTrue(visible.body().contains("unregisteredSymbols"));
            var operatorPage=mapper.readValue(get(server,expired,"&buildId="+buildIdentity.buildId()).body(),Map.class);
            assertEquals(2,((List<?>)operatorPage.get("events")).size());
            var changed=new HashMap<>(eventValue);changed.put("context",Map.of("phase","STARTUP","durationMs",1));
            refreshObservation(server);
            assertEquals(409,registrationRequest(server,"POST","batches",(String)root.get("producerToken"),mapper.writeValueAsBytes(Map.of("schemaVersion",1,"events",List.of(changed)))).statusCode());
            volume.observationFailed();assertEquals(503,registrationRequest(server,"POST","batches",(String)root.get("producerToken"),eventBatch).statusCode());
            refreshObservation(server);
            sql("UPDATE dev_logs.events SET canonical_payload=NULL WHERE scope_id='"+root.get("scopeId")+"' AND event_id='"+logEvent+"'");
            var tombstone=registrationRequest(server,"POST","batches",(String)root.get("producerToken"),eventBatch);assertEquals(410,tombstone.statusCode(),tombstone.body());
            assertTrue(tombstone.body().contains("LOG_EVENT_EXPIRED"));
            var expiredReceipt=registrationRequest(server,"POST","receipts",(String)root.get("producerToken"),receiptBody);
            assertEquals(200,expiredReceipt.statusCode());assertTrue(expiredReceipt.body().contains("EXPIRED"));assertFalse(expiredReceipt.body().contains("receivedAt"));
            assertEquals(204,registrationRequest(server,"DELETE","registrations/"+parent,operatorToken,new byte[0]).statusCode());
            assertEquals(org.documentknowledge.devlogging.domain.LogFailure.Kind.FORBIDDEN,
                assertThrows(org.documentknowledge.devlogging.domain.LogFailure.class,()->registry.authenticate((String)child.get("producerToken"),RequestDeadline.start())).kind());
        }
            } finally {sql("DROP OWNED BY dkp_log_writer; DROP ROLE dkp_log_writer");}
    }
    Map<String,Object> serverGrant(UUID id,Instant expiry,String credential) throws Exception {
        return new HashMap<>(Map.of("id",id.toString(),"expiresAt",expiry.toString(),"tokenSha256",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.US_ASCII)))));
    }
    void refreshObservation(EmbeddedServer server) throws Exception {
        var bytes=("{\"volumeIdentity\":\""+"f".repeat(64)+"\",\"freeBytes\":4294967296,\"totalBytes\":8589934592}").getBytes(StandardCharsets.UTF_8);
        var response=registrationRequest(server,"POST","storage-observation","v".repeat(43),bytes);
        assertEquals(204,response.statusCode(),response.body());
    }
    HttpResponse<String> registrationRequest(EmbeddedServer server,String method,String path,String credential,byte[] body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getPort()+"/api/v1/dev-logs/"+path))
            .timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+credential).header("Content-Type","application/json")
            .method(method,HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return client.send(request,HttpResponse.BodyHandlers.ofString());
        }
    }

}
