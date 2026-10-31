package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, strict signed JWT validation, real catalog and least-privilege PostgreSQL. */
@SuppressWarnings("unchecked")
class DocumentHttpIntegrationTest {
    private static final String BASE="/api/v1/documents";
    private static final String ISSUER=SecurityIntegrationTest.ISSUER;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> ALL=Set.of("document.create","document.inspect-draft","document.read","document.update-metadata","document.acl.restrict","document.acl.delegate","document.version.append","document.legal-hold.place","document.legal-hold.inspect");
    private static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("dm").withUsername("postgres").withPassword(UUID.randomUUID().toString())
        .withCommand("postgres","-c","fsync=on","-c","synchronous_commit=on","-c","full_page_writes=on");
    private static final String MIGRATION_PASSWORD=UUID.randomUUID().toString(),RUNTIME_PASSWORD=UUID.randomUUID().toString();
    private static PGSimpleDataSource admin;
    private final SecurityIntegrationTest security=new SecurityIntegrationTest();
    private final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    private Path directory;
    private Path catalog,cursorKey;

    @BeforeAll static void database() throws Exception {
        POSTGRES.start();admin=source("postgres",POSTGRES.getPassword());
        try(Connection c=admin.getConnection();Statement s=c.createStatement()) {
            s.execute("CREATE ROLE dkp_dm_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+MIGRATION_PASSWORD+"'");
            s.execute("CREATE ROLE dkp_dm_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+RUNTIME_PASSWORD+"'");
            s.execute("REVOKE ALL ON DATABASE dm FROM PUBLIC");s.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            s.execute("GRANT CONNECT,CREATE ON DATABASE dm TO dkp_dm_migrator");s.execute("GRANT CONNECT ON DATABASE dm TO dkp_dm_runtime");
            s.execute("ALTER ROLE dkp_dm_runtime SET search_path=dm");s.execute("CREATE SCHEMA dm AUTHORIZATION dkp_dm_migrator");
        }
    }
    private static PGSimpleDataSource source(String user,String password) {var s=new PGSimpleDataSource();s.setURL(POSTGRES.getJdbcUrl());s.setUser(user);s.setPassword(password);return s;}
    @AfterEach void closeClient() throws Exception {client.shutdownNow();if(directory!=null)try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    @AfterAll static void shutdown() {POSTGRES.stop();}
    @BeforeEach void isolate() throws Exception {
        Path fixtureRoot=Path.of("build/http-authority-fixtures").toAbsolutePath();Files.createDirectories(fixtureRoot);Files.setPosixFilePermissions(fixtureRoot,PosixFilePermissions.fromString("rwx------"));directory=Files.createTempDirectory(fixtureRoot,"authority-");
        Path parent=directory.toRealPath();Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwx------"));
        catalog=parent.resolve("grants.json");cursorKey=parent.resolve("cursor.key");byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);Files.writeString(cursorKey,Base64.getEncoder().encodeToString(key)+"\n");Files.setPosixFilePermissions(cursorKey,PosixFilePermissions.fromString("rw-------"));
        grants(entry("user","tenant-a",ALL,ALL),entry("user","tenant-b",ALL,ALL),entry("other","tenant-a",ALL,ALL));
        try(Connection c=admin.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT to_regclass('dm.documents')")){if(r.next() && r.getString(1)!=null)try(Statement clear=c.createStatement()){
            clear.execute("TRUNCATE dm.documents CASCADE");
            clear.execute("TRUNCATE dm.upload_resource_accounts CASCADE");
            clear.execute("UPDATE dm.upload_global_resource_account SET admitted_count=0,reserved_transient_bytes=0,promised_original_bytes=0,retained_original_bytes=0,revision=0");
        }}
    }
    private Map<String,Object> configuration() {
        var c=security.settings("standalone");c.put("dkp.dm.catalog.path",catalog.toString());c.put("dkp.dm.cursor.key-path",cursorKey.toString());
        c.put("datasources.default.url",POSTGRES.getJdbcUrl());c.put("datasources.default.username","dkp_dm_runtime");c.put("datasources.default.password",RUNTIME_PASSWORD);
        c.put("datasources.default.driver-class-name","org.postgresql.Driver");c.put("datasources.default.maximum-pool-size",4);
        c.put("flyway.datasources.dm-migrations.enabled",true);c.put("flyway.datasources.dm-migrations.url",POSTGRES.getJdbcUrl());c.put("flyway.datasources.dm-migrations.user","dkp_dm_migrator");c.put("flyway.datasources.dm-migrations.password",MIGRATION_PASSWORD);
        c.put("flyway.datasources.dm-migrations.schemas",List.of("dm"));c.put("flyway.datasources.dm-migrations.default-schema","dm");c.put("flyway.datasources.dm-migrations.create-schemas",false);c.put("flyway.datasources.dm-migrations.async",false);return c;
    }
    private EmbeddedServer server(){return ApplicationContext.run(EmbeddedServer.class,configuration());}
    private Map<String,Object> entry(String subject,String tenant,Set<String> actions,Set<String> ceiling){return Map.of("issuer",ISSUER,"subject",subject,"tenant",tenant,"actions",actions.stream().sorted().toList(),"delegationCeiling",ceiling.stream().sorted().toList());}
    @SafeVarargs private final void grants(Map<String,Object>...entries) throws Exception {Files.writeString(catalog,JSON.writeValueAsString(Map.of("schemaVersion",1,"entries",List.of(entries))));Files.setPosixFilePermissions(catalog,PosixFilePermissions.fromString("rw-------"));}
    private String token(String subject) throws Exception {var claims=security.claims();claims.put("sub",subject);return security.token(claims);}
    private HttpResponse<String> send(EmbeddedServer server,String method,String path,String token,String tenant,String key,String contentType,String body) throws Exception {
        var request=HttpRequest.newBuilder(server.getURI().resolve(path)).timeout(java.time.Duration.ofSeconds(10)).header("X-User-Id","user").header("X-Tenant-Id","tenant-a").header("X-Spiffe-Id","spiffe://forged");
        if(token!=null)request.header("Authorization","Bearer "+token);if(tenant!=null)request.header("X-DKP-Tenant",tenant);if(key!=null)request.header("Idempotency-Key",key);if(contentType!=null)request.header("Content-Type",contentType);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> call(EmbeddedServer server,String method,String path,String subject,String tenant,String key,Object body) throws Exception {return send(server,method,path,token(subject),tenant,key,body==null?null:"application/json",body==null?null:JSON.writeValueAsString(body));}
    private Map<String,Object> body(HttpResponse<String> response) throws Exception {return JSON.readValue(response.body(),Map.class);}
    private Map<String,Object> object(Object value){assertInstanceOf(Map.class,value);return (Map<String,Object>)value;}
    private List<Map<String,Object>> items(Object value){assertInstanceOf(List.class,value);return (List<Map<String,Object>>)value;}
    private Map<String,Object> draft(){return Map.of("metadata",Map.of("title","<script>untrusted</script>","description","plain text"),"classification","development-restricted","classificationPolicyRevision",1);}
    private UUID created(EmbeddedServer server,String tenant) throws Exception {var response=call(server,"POST",BASE,"user",tenant,UUID.randomUUID().toString(),draft());receipt(response,201,0);return UUID.fromString((String)body(response).get("documentId"));}
    private void receipt(HttpResponse<String> response,int status,long revision) throws Exception {assertEquals(status,response.statusCode(),response.body());var b=body(response);assertEquals(Set.of("documentId","revision"),b.keySet());UUID.fromString((String)b.get("documentId"));assertEquals(revision,((Number)b.get("revision")).longValue());assertNoStore(response);}
    private void error(HttpResponse<String> response,int status) throws Exception {assertEquals(status,response.statusCode(),response.body());var b=body(response);assertEquals(Set.of("code"),b.keySet());assertTrue(((String)b.get("code")).matches("[A-Z][A-Z0-9_]*"));assertNoStore(response);}
    private void assertNoStore(HttpResponse<String> response){assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));}
    private Map<String,Object> change(long revision,String mode,String operation,String subject,String action){return Map.of("expectedRevision",revision,"mode",mode,"changes",List.of(Map.of("operation",operation,"principal",Map.of("issuer",ISSUER,"subject",subject),"action",action)));}

    @Test void completeDraftMetadataAclAndLegalHoldContract() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");
            var readiness=client.send(HttpRequest.newBuilder(server.getURI().resolve("/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,readiness.statusCode(),readiness.body());assertEquals("{\"status\":\"UP\"}",readiness.body());
            var inspect=call(server,"GET",BASE+"/"+id,"user","tenant-a",null,null);assertEquals(200,inspect.statusCode(),inspect.body());assertNoStore(inspect);var document=body(inspect);
            assertEquals(Set.of("documentId","tenant","creator","createdAt","revision","lifecycle","metadata","classification","classificationPolicyRevision","activeVersionId","retention"),document.keySet());
            assertEquals("tenant-a",document.get("tenant"));assertEquals(Map.of("issuer",ISSUER,"subject","user"),document.get("creator"));assertEquals("DRAFT",document.get("lifecycle"));assertNull(document.get("activeVersionId"));assertEquals(draft().get("metadata"),document.get("metadata"));assertEquals("development-restricted",document.get("classification"));assertEquals(1,((Number)document.get("classificationPolicyRevision")).intValue());java.time.Instant.parse((String)document.get("createdAt"));
            var retention=object(document.get("retention"));assertEquals(Set.of("policyRef","policyRevision","resolution","retainUntil","destructiveEligible"),retention.keySet());assertEquals("development-unresolved",retention.get("policyRef"));assertEquals(1,((Number)retention.get("policyRevision")).intValue());assertEquals("UNRESOLVED",retention.get("resolution"));assertNull(retention.get("retainUntil"));assertEquals(false,retention.get("destructiveEligible"));
            var page=body(call(server,"GET",BASE,"user","tenant-a",null,null));assertEquals(Set.of("items","nextCursor"),page.keySet());assertEquals(1,items(page.get("items")).size());assertNull(page.get("nextCursor"));
            receipt(call(server,"PATCH",BASE+"/"+id,"user","tenant-a","metadata",Map.of("expectedRevision",0,"metadata",Map.of("title","updated","description",""))),200,1);
            var acl=call(server,"GET",BASE+"/"+id+"/acl","user","tenant-a",null,null);assertEquals(200,acl.statusCode());assertEquals(Set.of("documentId","revision","rules"),body(acl).keySet());
            var initialRules=items(body(acl).get("rules"));assertEquals(7,initialRules.size());for(var rule:initialRules){assertEquals(Set.of("principal","action","effect"),rule.keySet());assertEquals(Map.of("issuer",ISSUER,"subject","user"),rule.get("principal"));assertEquals("ALLOW",rule.get("effect"));assertFalse(Set.of("document.create","document.read").contains(rule.get("action")));}
            receipt(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","delegate",change(1,"DELEGATE","ADD_ALLOW","other","document.inspect-draft")),200,2);
            assertEquals(200,call(server,"GET",BASE+"/"+id,"other","tenant-a",null,null).statusCode());
            receipt(call(server,"POST",BASE+"/"+id+"/legal-holds","user","tenant-a","hold",Map.of("expectedRevision",2,"reason","preserve")),201,3);
            var holds=body(call(server,"GET",BASE+"/"+id+"/legal-holds","user","tenant-a",null,null));assertEquals(Set.of("items"),holds.keySet());var hold=items(holds.get("items")).getFirst();assertEquals(Set.of("holdId","documentId","revision","active","reason","placedBy","placedAt"),hold.keySet());UUID.fromString((String)hold.get("holdId"));java.time.Instant.parse((String)hold.get("placedAt"));assertEquals(id.toString(),hold.get("documentId"));assertEquals(3,((Number)hold.get("revision")).longValue());assertEquals(true,hold.get("active"));assertEquals("preserve",hold.get("reason"));assertEquals(Map.of("issuer",ISSUER,"subject","user"),hold.get("placedBy"));
            error(call(server,"POST",BASE+"/"+id+"/versions","user","tenant-a","append",Map.of("expectedRevision",3,"receiptId","untrusted-opaque-receipt")),404);
            assertEquals(3,((Number)body(call(server,"GET",BASE+"/"+id,"user","tenant-a",null,null)).get("revision")).longValue());
        }
    }
    @Test void realAuthenticatedUploadAdmissionAndClosedBinaryBoundaries() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");String uploads=BASE+"/uploads";
            var command=Map.of("documentId",id.toString(),"expectedRevision",0,"format","PDF","sizeBytes",4);
            var initialized=call(server,"POST",uploads,"user","tenant-a","upload-init",command);
            assertEquals(201,initialized.statusCode(),initialized.body());assertEquals(Set.of("uploadId"),body(initialized).keySet());assertNoStore(initialized);
            String slot=(String)body(initialized).get("uploadId");UUID.fromString(slot);
            assertInitializedUploadFacts(id,UUID.fromString(slot));
            assertEquals(body(initialized),body(call(server,"POST",uploads,"user","tenant-a","upload-init",command)));
            assertInitializedUploadFacts(id,UUID.fromString(slot));
            var status=call(server,"GET",uploads+"/"+slot+"/status","user","tenant-a",null,null);
            assertEquals(200,status.statusCode(),status.body());assertEquals(Set.of("uploadId","state","expiresAt","failureCode"),body(status).keySet());assertEquals("INITIALIZED",body(status).get("state"));assertNull(body(status).get("failureCode"));
            error(call(server,"GET",uploads+"/"+slot+"/status","other","tenant-a",null,null),404);
            error(send(server,"PUT",uploads+"/"+slot+"/content",token("user"),"tenant-a","wrong-type","text/plain","1234"),415);
            // Preserve the original encoding header and bytes until the closed
            // upload boundary rejects them; no scanner/storage or state transition.
            byte[] plain="1234".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            var compressed=new java.io.ByteArrayOutputStream();
            try(var gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(plain);}
            for(byte[] encoded:List.of(plain,compressed.toByteArray())) {
                for(int attempt=0;attempt<3;attempt++) {
                    var encodedRequest=HttpRequest.newBuilder(server.getURI().resolve(uploads+"/"+slot+"/content"))
                        .header("Authorization","Bearer "+token("user")).header("X-DKP-Tenant","tenant-a")
                        .header("Idempotency-Key","encoded-"+UUID.randomUUID()).header("Content-Type","application/octet-stream")
                        .header("Content-Encoding","gzip").PUT(HttpRequest.BodyPublishers.ofByteArray(encoded)).build();
                    var encodedResponse=client.send(encodedRequest,HttpResponse.BodyHandlers.ofString());
                    error(encodedResponse,400);assertEquals("INVALID_REQUEST",body(encodedResponse).get("code"));
                }
            }
            error(call(server,"POST",uploads+"/"+slot+"/complete","user","tenant-a","extra",Map.of("extra",true)),400);
            error(call(server,"POST",uploads+"/"+slot+"/complete","user","tenant-a","not-transferred",Map.of()),409);
            error(call(server,"GET",BASE+"/"+id+"/upload-receipts/"+UUID.randomUUID()+"/version","user","tenant-a",null,null),404);
            error(call(server,"GET",BASE+"/"+id+"/versions/"+UUID.randomUUID()+"/content","user","tenant-a",null,null),404);
            error(call(server,"POST",uploads,"user","tenant-a","too-large",Map.of("documentId",id.toString(),"expectedRevision",0,"format","PDF","sizeBytes",100000001)),413);
            error(call(server,"POST",uploads,"user","tenant-a","unknown-format",Map.of("documentId",id.toString(),"expectedRevision",0,"format","HTML","sizeBytes",4)),400);
            var metadataLarge=" ".repeat(16384)+"{}";var oversized=send(server,"POST",uploads,token("user"),"tenant-a","metadata-large","application/json",metadataLarge);error(oversized,413);assertEquals("PAYLOAD_TOO_LARGE",body(oversized).get("code"));
            var oldOversized=send(server,"POST",BASE,token("user"),"tenant-a","old-metadata-large","application/json",metadataLarge);error(oldOversized,413);assertEquals("REQUEST_TOO_LARGE",body(oldOversized).get("code"));
            var unauthenticated=send(server,"GET",uploads+"/"+slot+"/status",null,"tenant-a",null,null,null);error(unauthenticated,401);assertEquals("UNAUTHENTICATED",body(unauthenticated).get("code"));
            assertEquals(0,((Number)body(call(server,"GET",BASE+"/"+id,"user","tenant-a",null,null)).get("revision")).longValue());
            assertInitializedUploadFacts(id,UUID.fromString(slot));
        }
    }
    private void assertInitializedUploadFacts(UUID document,UUID slot) throws Exception {
        // Inspect committed facts using the test owner; every HTTP mutation still uses the runtime role.
        try(Connection c=admin.getConnection();Statement s=c.createStatement()) {
            for(String table:List.of("upload_slots","upload_command_outcomes","upload_audit_facts")) {
                try(ResultSet r=s.executeQuery("SELECT count(*) FROM dm."+table)) {assertTrue(r.next());assertEquals(1,r.getInt(1),table+" must not duplicate on replay");}
            }
            try(var statement=c.prepareStatement("SELECT tenant_id,document_id,actor_issuer,actor_subject,state,declared_size FROM dm.upload_slots WHERE upload_id=?")) {
                statement.setObject(1,slot);
                try(ResultSet r=statement.executeQuery()) {assertTrue(r.next());assertEquals("tenant-a",r.getString(1));assertEquals(document,r.getObject(2,UUID.class));assertEquals(ISSUER,r.getString(3));assertEquals("user",r.getString(4));assertEquals("INITIALIZED",r.getString(5));assertEquals(4,r.getLong(6));}
            }
            try(ResultSet r=s.executeQuery("SELECT operation,status,idempotency_key FROM dm.upload_command_outcomes")) {assertTrue(r.next());assertEquals("upload.initialize",r.getString(1));assertEquals(201,r.getInt(2));assertEquals("upload-init",r.getString(3));}
            try(ResultSet r=s.executeQuery("SELECT operation,outcome_code,state_revision FROM dm.upload_audit_facts")) {assertTrue(r.next());assertEquals("upload.initialize",r.getString(1));assertEquals("OK",r.getString(2));assertEquals(0,r.getLong(3));}
            for(String table:List.of("upload_global_resource_account","upload_resource_accounts")) {
                try(ResultSet r=s.executeQuery("SELECT admitted_count,reserved_transient_bytes,promised_original_bytes,retained_original_bytes,revision FROM dm."+table)) {
                    assertTrue(r.next());assertEquals(1,r.getLong(1));assertEquals(8,r.getLong(2));assertEquals(4,r.getLong(3));assertEquals(0,r.getLong(4));assertEquals(1,r.getLong(5));assertFalse(r.next());
                }
            }
        }
    }
    @Test void uploadMembershipOutageAndFrameworkOversizeUseFiniteCodes() throws Exception {
        var settings=configuration();settings.put("dkp.security.profile","external");
        try(var server=ApplicationContext.run(EmbeddedServer.class,settings)){
            String slot=UUID.randomUUID().toString();var unavailable=call(server,"GET",BASE+"/uploads/"+slot+"/status","user","tenant-a",null,null);error(unavailable,503);assertEquals("UNAVAILABLE",body(unavailable).get("code"));
            var metadata=call(server,"GET",BASE+"/"+UUID.randomUUID(),"user","tenant-a",null,null);error(metadata,503);assertEquals("AUTHORITY_UNAVAILABLE",body(metadata).get("code"));
        }
        settings=configuration();settings.put("micronaut.server.max-request-size",64);
        try(var server=ApplicationContext.run(EmbeddedServer.class,settings)){
            var oversized=send(server,"PUT",BASE+"/uploads/"+UUID.randomUUID()+"/content",token("user"),"tenant-a","framework-limit","application/octet-stream","x".repeat(65));error(oversized,413);assertEquals("PAYLOAD_TOO_LARGE",body(oversized).get("code"));
        }
    }
    @Test void strictJwtMembershipAndForwardedHeadersCannotBypassDirectApi() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");String path=BASE+"/"+id;
            error(send(server,"GET",path,null,"tenant-a",null,null,null),401);error(send(server,"GET",path,"malformed","tenant-a",null,null,null),401);
            for(var claims:List.of(withClaim("iss","https://forged.example"),withClaim("aud",List.of("dkp-bff")),withClaim("azp","untrusted"),withClaim("typ","ID"),withClaim("exp",java.time.Instant.now().minusSeconds(1).getEpochSecond())))error(send(server,"GET",path,security.token(claims),"tenant-a",null,null,null),401);
            error(send(server,"GET",path,security.token(security.claims(),SecurityIntegrationTest.key("primary"),"primary",com.nimbusds.jose.JWSAlgorithm.RS256),"tenant-a",null,null,null),401);
            error(call(server,"GET",path,"user",null,null,null),403);error(call(server,"GET",path,"user","tenant-c",null,null),403);
            error(call(server,"POST",BASE,"user","tenant-c","tenant-before-schema",Map.of("forged","field")),403);
            error(call(server,"PATCH",path,"user","tenant-c","tenant-before-schema",Map.of("forged","field")),403);
            error(call(server,"GET",path,"other","tenant-a",null,null),404);error(call(server,"GET",path,"user","tenant-b",null,null),404);
            error(call(server,"GET","/api/v1/dm/documents","user","tenant-a",null,null),403);
        }
    }
    private Map<String,Object> withClaim(String name,Object value){var claims=security.claims();claims.put(name,value);return claims;}
    @Test void fixedOperationAndInspectionDenialOrderAndCatalogFailure() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");grants(entry("user","tenant-a",Set.of("document.read"),Set.of()));
            for(UUID candidate:List.of(id,UUID.randomUUID())) {
                error(call(server,"GET",BASE+"/"+candidate,"user","tenant-a",null,null),404);
                error(call(server,"GET",BASE+"/"+candidate+"/acl","user","tenant-a",null,null),404);
                error(call(server,"GET",BASE+"/"+candidate+"/legal-holds","user","tenant-a",null,null),404);
                error(call(server,"PATCH",BASE+"/"+candidate,"user","tenant-a","mutation",Map.of("expectedRevision",999,"metadata",Map.of("title","no","description",""))),403);
            }
            error(call(server,"GET",BASE,"user","tenant-a",null,null),403);error(call(server,"POST",BASE,"user","tenant-a","create",draft()),403);
            Files.writeString(catalog,"partial");error(call(server,"GET",BASE+"/"+id,"user","tenant-a",null,null),503);error(call(server,"POST",BASE,"user","tenant-a","create",draft()),503);
            Files.delete(catalog);error(call(server,"GET",BASE,"user","tenant-a",null,null),503);
        }
    }
    @Test void commandReceiptsStayMinimalWithoutInspectionAndReplayRechecksCurrentAuthority() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");Set<String> actions=new HashSet<>(ALL);actions.remove("document.inspect-draft");grants(entry("user","tenant-a",actions,ALL));
            var mutation=Map.of("expectedRevision",0,"metadata",Map.of("title","updated","description",""));
            receipt(call(server,"PATCH",BASE+"/"+id,"user","tenant-a","stable",mutation),200,1);receipt(call(server,"PATCH",BASE+"/"+id,"user","tenant-a","stable",mutation),200,1);
            error(call(server,"GET",BASE+"/"+id,"user","tenant-a",null,null),404);
            error(call(server,"PATCH",BASE+"/"+id,"user","tenant-a","stable",Map.of("expectedRevision",0,"metadata",Map.of("title","changed","description",""))),409);
            error(call(server,"PATCH",BASE+"/"+id,"user","tenant-a","stale",mutation),409);
            actions.remove("document.update-metadata");grants(entry("user","tenant-a",actions,ALL));error(call(server,"PATCH",BASE+"/"+id,"user","tenant-a","stable",mutation),403);
        }
    }
    @Test void aclSelfRevocationReturnsReceiptThenReplayFailsAndDenyOverridesAllow() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");var restrict=change(0,"RESTRICT","REMOVE_ALLOW","user","document.acl.restrict");
            receipt(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","self",restrict),200,1);error(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","self",restrict),404);
            receipt(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","allow",change(1,"DELEGATE","ADD_ALLOW","other","document.inspect-draft")),200,2);
            receipt(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","deny",change(2,"DELEGATE","ADD_DENY","other","document.inspect-draft")),200,3);
            receipt(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","noop",change(3,"DELEGATE","ADD_ALLOW","other","document.inspect-draft")),200,4);error(call(server,"GET",BASE+"/"+id,"other","tenant-a",null,null),404);
            receipt(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","remove-deny",change(4,"DELEGATE","REMOVE_DENY","other","document.inspect-draft")),200,5);assertEquals(200,call(server,"GET",BASE+"/"+id,"other","tenant-a",null,null).statusCode());
        }
    }
    @Test void aclDeltaDuplicateRestrictionTargetAndCeilingFailuresAreAtomic() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");var single=change(0,"DELEGATE","ADD_ALLOW","other","document.inspect-draft");var duplicate=new HashMap<>(single);var entries=(List<Object>)single.get("changes");duplicate.put("changes",List.of(entries.getFirst(),entries.getFirst()));
            error(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","duplicate",duplicate),422);
            error(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","restrict-other",change(0,"RESTRICT","ADD_DENY","other","document.inspect-draft")),422);
            error(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","restrict-remove-deny",change(0,"RESTRICT","REMOVE_DENY","user","document.inspect-draft")),422);
            error(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","no-target",change(0,"DELEGATE","ADD_ALLOW","missing","document.inspect-draft")),422);
            grants(entry("user","tenant-a",ALL,Set.of()),entry("other","tenant-a",ALL,ALL));error(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","no-ceiling",single),403);
            assertEquals(0,((Number)body(call(server,"GET",BASE+"/"+id,"user","tenant-a",null,null)).get("revision")).longValue());error(call(server,"GET",BASE+"/"+id,"other","tenant-a",null,null),404);
        }
    }
    @Test void resultingAclCapIsEnforcedAtomicallyWithoutChangingAnyRules() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");var actors=new ArrayList<Map<String,Object>>();actors.add(entry("user","tenant-a",ALL,ALL));var delta=new ArrayList<Map<String,Object>>();
            for(int i=0;i<14;i++) {String subject="target-"+i;actors.add(entry(subject,"tenant-a",ALL,ALL));for(String action:ALL)if(!Set.of("document.create","document.read").contains(action))delta.add(Map.of("operation","ADD_ALLOW","principal",Map.of("issuer",ISSUER,"subject",subject),"action",action));}
            grants(actors.toArray(Map[]::new));assertEquals(98,delta.size());
            var before=body(call(server,"GET",BASE+"/"+id+"/acl","user","tenant-a",null,null));
            var response=call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","over-cap",Map.of("expectedRevision",0,"mode","DELEGATE","changes",delta));error(response,422);assertEquals("ACL_RULE_LIMIT",body(response).get("code"));
            var after=body(call(server,"GET",BASE+"/"+id+"/acl","user","tenant-a",null,null));assertEquals(before,after);
        }
    }
    @Test void paginationUsesOnlyVisibleRowsAndAuthenticatedActorTenantCursor() throws Exception {
        try(var server=server()) {
            UUID first=created(server,"tenant-a"),second=created(server,"tenant-a");created(server,"tenant-b");
            var firstPage=body(call(server,"GET",BASE+"?limit=1","user","tenant-a",null,null));assertEquals(Set.of("items","nextCursor"),firstPage.keySet());assertEquals(1,items(firstPage.get("items")).size());String cursor=(String)firstPage.get("nextCursor");assertNotNull(cursor);
            var secondPage=body(call(server,"GET",BASE+"?limit=1&cursor="+cursor,"user","tenant-a",null,null));assertEquals(1,items(secondPage.get("items")).size());assertNotEquals(items(firstPage.get("items")).getFirst().get("documentId"),items(secondPage.get("items")).getFirst().get("documentId"));
            var terminal=body(call(server,"GET",BASE+"?limit=1&cursor="+secondPage.get("nextCursor"),"user","tenant-a",null,null));assertTrue(items(terminal.get("items")).isEmpty());assertNull(terminal.get("nextCursor"));
            error(call(server,"GET",BASE+"?limit=1&cursor="+cursor,"other","tenant-a",null,null),400);error(call(server,"GET",BASE+"?limit=1&cursor="+cursor,"user","tenant-b",null,null),400);error(call(server,"GET",BASE+"?cursor="+cursor.substring(1),"user","tenant-a",null,null),400);
            // Neither another actor's rows nor another tenant's rows cause totals or cursor disclosure.
            var hidden=body(call(server,"GET",BASE+"?limit=1","other","tenant-a",null,null));assertEquals(Set.of("items","nextCursor"),hidden.keySet());assertEquals(List.of(),hidden.get("items"));assertNull(hidden.get("nextCursor"));
            receipt(call(server,"PATCH",BASE+"/"+second+"/acl","user","tenant-a","visible-after-hidden",change(0,"DELEGATE","ADD_ALLOW","other","document.inspect-draft")),200,1);
            var filtered=body(call(server,"GET",BASE+"?limit=1","other","tenant-a",null,null));assertEquals(1,items(filtered.get("items")).size());assertEquals(second.toString(),items(filtered.get("items")).getFirst().get("documentId"));
            assertNotEquals(first,second);
        }
    }
    @Test void rejectsUnknownFieldsForgedOwnershipAndMalformedRevisionBodies() throws Exception {
        try(var server=server()) {
            for(String field:List.of("tenant","creator","documentId","revision","lifecycle","activeVersionId")) {var forged=new HashMap<>(draft());forged.put(field,"forged");error(call(server,"POST",BASE,"user","tenant-a",UUID.randomUUID().toString(),forged),400);}
            var badMetadata=new HashMap<>(draft());badMetadata.put("metadata",Map.of("title","ok","description","","html",true));error(call(server,"POST",BASE,"user","tenant-a","unknown-meta",badMetadata),400);
            UUID id=created(server,"tenant-a");
            for(Object revision:List.of(-1,9007199254740992L,0.5,"0"))error(call(server,"PATCH",BASE+"/"+id,"user","tenant-a",UUID.randomUUID().toString(),Map.of("expectedRevision",revision,"metadata",Map.of("title","ok","description",""))),400);
            for(var request:List.of(
                    Map.of("path",BASE+"/"+id,"method","PATCH","body",Map.of("expectedRevision",0,"metadata",Map.of("title","ok","description",""),"tenant","forged")),
                    Map.of("path",BASE+"/"+id+"/acl","method","PATCH","body",Map.of("expectedRevision",0,"mode","RESTRICT","changes",List.of(Map.of("operation","REMOVE_ALLOW","principal",Map.of("issuer",ISSUER,"subject","user"),"action","document.read")),"rules",List.of())),
                    Map.of("path",BASE+"/"+id+"/legal-holds","method","POST","body",Map.of("expectedRevision",0,"reason","preserve","placedBy","forged")),
                    Map.of("path",BASE+"/"+id+"/versions","method","POST","body",Map.of("expectedRevision",0,"receiptId","opaque","bucket","forged")))) {
                error(call(server,(String)request.get("method"),(String)request.get("path"),"user","tenant-a",UUID.randomUUID().toString(),request.get("body")),400);
            }
            error(call(server,"PATCH",BASE+"/"+id,"user","tenant-a","missing-revision",Map.of("metadata",Map.of("title","ok","description",""))),400);
            error(call(server,"POST",BASE,"user","tenant-a",null,draft()),400);error(call(server,"POST",BASE,"user","tenant-a","x".repeat(129),draft()),400);
            error(send(server,"POST",BASE,token("user"),"tenant-a","malformed","application/json","{"),400);error(send(server,"POST",BASE,token("user"),"tenant-a","duplicate-json","application/json","{\"metadata\":{},\"metadata\":{}}"),400);
            assertEquals(0,((Number)body(call(server,"GET",BASE+"/"+id,"user","tenant-a",null,null)).get("revision")).longValue());
        }
    }
    @Test void rejectsOutOfBoundsTextClassificationAndUnsupportedRequestShapes() throws Exception {
        try(var server=server()) {
            for(var metadata:List.of(Map.of("title","","description",""),Map.of("title","t".repeat(257),"description",""),Map.of("title","ok","description","x".repeat(4097)),Map.of("title","bad\u0001","description",""))) {var bad=new HashMap<>(draft());bad.put("metadata",metadata);error(call(server,"POST",BASE,"user","tenant-a",UUID.randomUUID().toString(),bad),400);}
            for(var override:List.of(Map.of("classification","public"),Map.of("classificationPolicyRevision",2))) {var bad=new HashMap<>(draft());bad.putAll(override);error(call(server,"POST",BASE,"user","tenant-a",UUID.randomUUID().toString(),bad),400);}
            error(send(server,"POST",BASE,token("user"),"tenant-a","text","text/plain","hello"),415);
            error(send(server,"POST",BASE,token("user"),"tenant-a","oversize","application/json"," ".repeat(16385)),413);
            UUID id=created(server,"tenant-a");
            error(call(server,"GET",BASE+"?unknown=true","user","tenant-a",null,null),400);for(String limit:List.of("0","51","-1","1.5","bad"))error(call(server,"GET",BASE+"?limit="+limit,"user","tenant-a",null,null),400);
            error(call(server,"GET",BASE+"/"+id+"?unknown=true","user","tenant-a",null,null),400);
            error(call(server,"POST",BASE+"?unknown=true","user","tenant-a","unknown-create-query",draft()),400);
            error(call(server,"GET",BASE+"?limit=1&limit=2","user","tenant-a",null,null),400);
            error(call(server,"GET",BASE+"/not-a-uuid","user","tenant-a",null,null),400);
            for(String method:List.of("PUT","DELETE")) {var response=call(server,method,BASE+"/"+id+"/acl","user","tenant-a","unsupported",Map.of());assertTrue(Set.of(403,404,405).contains(response.statusCode()),response.body());}
        }
    }
    @Test void duplicateAuthorityHeadersAndEncodedPathsCannotSelectAnotherIdentity() throws Exception {
        try(var server=server()) {
            UUID id=created(server,"tenant-a");String bearer=token("user");
            var duplicateTenant=HttpRequest.newBuilder(server.getURI().resolve(BASE+"/"+id)).timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+bearer).header("X-DKP-Tenant","tenant-a").header("X-DKP-Tenant","tenant-b").GET().build();
            error(client.send(duplicateTenant,HttpResponse.BodyHandlers.ofString()),400);
            var duplicateKey=HttpRequest.newBuilder(server.getURI().resolve(BASE)).timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+bearer).header("X-DKP-Tenant","tenant-a").header("Content-Type","application/json").header("Idempotency-Key","one").header("Idempotency-Key","two").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(draft()))).build();
            error(client.send(duplicateKey,HttpResponse.BodyHandlers.ofString()),400);
            String raw=id.toString();String encoded=String.format("%%%02x",(int)raw.charAt(0))+raw.substring(1);error(call(server,"GET",BASE+"/"+encoded,"user","tenant-a",null,null),400);
        }
    }
    @Test void cursorSecretReplacementAndInvalidMaterialFailClosed() throws Exception {
        try(var server=server()) {
            created(server,"tenant-a");var page=body(call(server,"GET",BASE+"?limit=1","user","tenant-a",null,null));String old=(String)page.get("nextCursor");assertNotNull(old);
            byte[] replacement=new byte[32];new java.security.SecureRandom().nextBytes(replacement);String valid=Base64.getEncoder().encodeToString(replacement);Files.writeString(cursorKey,valid);
            error(call(server,"GET",BASE+"?limit=1&cursor="+old,"user","tenant-a",null,null),400);
            for(String invalid:List.of("invalid!",Base64.getEncoder().encodeToString(new byte[31]),"x".repeat(129))) {Files.writeString(cursorKey,invalid);error(call(server,"GET",BASE+"?limit=1","user","tenant-a",null,null),503);}
            Files.writeString(cursorKey,valid);Files.setPosixFilePermissions(cursorKey,PosixFilePermissions.fromString("rw-r--r--"));error(call(server,"GET",BASE+"?limit=1","user","tenant-a",null,null),503);
            Files.setPosixFilePermissions(cursorKey,PosixFilePermissions.fromString("rw-------"));Path actual=cursorKey.resolveSibling("actual.key");Files.move(cursorKey,actual);Files.createSymbolicLink(cursorKey,actual);error(call(server,"GET",BASE+"?limit=1","user","tenant-a",null,null),503);
            Files.delete(cursorKey);error(call(server,"GET",BASE+"?limit=1","user","tenant-a",null,null),503);
        }
    }
    @Test void maximumMetadataCrossesBodyBufferBoundaryAndReturnsMinimalReceipt() throws Exception {
        try(var server=server()) {
            var maximum=new HashMap<>(draft());maximum.put("metadata",Map.of("title","t".repeat(256),"description","d".repeat(4096)));
            receipt(call(server,"POST",BASE,"user","tenant-a","maximum-metadata",maximum),201,0);
        }
    }
    @Test void jsonBodyAboveSixteenKiBIsRejectedWithoutCaching() throws Exception {
        try(var server=server()) {
            var oversized=new HashMap<>(draft());oversized.put("metadata",Map.of("title","title","description","x".repeat(17000)));
            assertTrue(JSON.writeValueAsBytes(oversized).length>16384);error(call(server,"POST",BASE,"user","tenant-a","oversized-json",oversized),413);
        }
    }
    @Test void chunkedJsonBodyAboveLimitIsAlsoRejectedWithoutCaching() throws Exception {
        try(var server=server()) {
            var oversized=new HashMap<>(draft());oversized.put("metadata",Map.of("title","title","description","x".repeat(17000)));byte[] payload=JSON.writeValueAsBytes(oversized);
            var request=HttpRequest.newBuilder(server.getURI().resolve(BASE)).timeout(java.time.Duration.ofSeconds(10)).header("Authorization","Bearer "+token("user")).header("X-DKP-Tenant","tenant-a").header("Idempotency-Key","oversized-chunked").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(payload))).build();
            error(client.send(request,HttpResponse.BodyHandlers.ofString()),413);
        }
    }
    @Test void multiChunkInvalidMetadataHasBoundedHttpResponse() throws Exception {
        try(var server=server();var socket=new java.net.Socket(server.getHost(),server.getPort())) {
            socket.setSoTimeout(5000);var invalid=new HashMap<>(draft());invalid.put("metadata",Map.of("title","ok","description","x".repeat(4097)));byte[] payload=JSON.writeValueAsBytes(invalid);
            String headers="POST "+BASE+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token("user")+"\r\nX-DKP-Tenant: tenant-a\r\nIdempotency-Key: raw-multichunk\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+payload.length+"\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(payload);socket.getOutputStream().flush();
            byte[] response=socket.getInputStream().readNBytes(65537);assertTrue(response.length<=65536);String wire=new String(response,java.nio.charset.StandardCharsets.UTF_8);assertTrue(wire.startsWith("HTTP/1.1 400"),wire);assertTrue(wire.endsWith("{\"code\":\"INVALID_REQUEST\"}"),wire);
        }
    }
    @Test void unresponsiveDatabaseBoundsReadinessWithoutBlockingLivenessAndRecovers() throws Exception {
        var settings=configuration();settings.put("micronaut.server.netty.worker.threads",1);
        // A separate, disabled datasource configuration must retain its own bounds.
        settings.put("datasources.other.enabled",false);settings.put("datasources.other.url",POSTGRES.getJdbcUrl());settings.put("datasources.other.driver-class-name","org.postgresql.Driver");
        settings.put("datasources.other.connection-timeout",3500);settings.put("datasources.other.validation-timeout",1200);
        try(var server=ApplicationContext.run(EmbeddedServer.class,settings)) {
            var pool=server.getApplicationContext().getBean(javax.sql.DataSource.class,io.micronaut.inject.qualifiers.Qualifiers.byName("default"));
            var hikari=pool.unwrap(com.zaxxer.hikari.HikariDataSource.class);assertEquals(1000,hikari.getConnectionTimeout());assertEquals(500,hikari.getValidationTimeout());
            var other=server.getApplicationContext().getBean(io.micronaut.configuration.jdbc.hikari.DatasourceConfiguration.class,io.micronaut.inject.qualifiers.Qualifiers.byName("other"));
            assertEquals(3500,other.getConnectionTimeout());assertEquals(1200,other.getValidationTimeout());
            assertFalse(server.getApplicationContext().containsBean(javax.sql.DataSource.class,io.micronaut.inject.qualifiers.Qualifiers.byName("other")),"disabled configuration must not create a second pool");
            var readinessRequest=HttpRequest.newBuilder(server.getURI().resolve("/health/readiness")).timeout(java.time.Duration.ofSeconds(3)).GET().build();
            var warm=client.send(readinessRequest,HttpResponse.BodyHandlers.ofString());assertEquals(200,warm.statusCode(),warm.body());
            // Hikari bypasses validation for very recently used idle connections. Expire that
            // window so the paused database exercises validation as well as acquisition.
            Thread.sleep(600);
            boolean paused=false;
            try {
                POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();paused=true;
                long started=System.nanoTime();
                var pending=client.sendAsync(readinessRequest,HttpResponse.BodyHandlers.ofString());
                Thread.sleep(150);assertFalse(pending.isDone(),"readiness must still be waiting on the paused database");
                var livenessRequest=HttpRequest.newBuilder(server.getURI().resolve("/health/liveness")).timeout(java.time.Duration.ofMillis(750)).GET().build();
                var live=client.send(livenessRequest,HttpResponse.BodyHandlers.ofString());assertEquals(200,live.statusCode(),live.body());assertEquals("{\"status\":\"UP\"}",live.body());
                assertFalse(pending.isDone(),"liveness must complete while readiness is still waiting");
                var down=pending.get(3,java.util.concurrent.TimeUnit.SECONDS);assertEquals(503,down.statusCode(),down.body());assertEquals("{\"status\":\"DOWN\"}",down.body());
                assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).compareTo(java.time.Duration.ofSeconds(3))<0,"database outage readiness must finish within the existing three-second probe deadline");
            } finally {
                if(paused)POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
            }
            long recoveryDeadline=System.nanoTime()+java.time.Duration.ofSeconds(6).toNanos();
            HttpResponse<String> recovered;
            do {
                recovered=client.send(readinessRequest,HttpResponse.BodyHandlers.ofString());
                if(recovered.statusCode()==200)break;
                Thread.sleep(100);
            } while(System.nanoTime()<recoveryDeadline);
            assertEquals(200,recovered.statusCode(),recovered.body());assertEquals("{\"status\":\"UP\"}",recovered.body());
        }
    }
    @Test void absentDatabaseCannotProduceBusinessSuccessAndLivenessRemainsAvailable() throws Exception {
        var settings=security.settings("standalone");settings.put("dkp.dm.catalog.path",catalog.toString());settings.put("dkp.dm.cursor.key-path",cursorKey.toString());
        try(var server=ApplicationContext.run(EmbeddedServer.class,settings)) {
            error(call(server,"POST",BASE,"user","tenant-a","unavailable",draft()),503);
            var probe=client.send(HttpRequest.newBuilder(server.getURI().resolve("/health/liveness")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,probe.statusCode());assertEquals("{\"status\":\"UP\"}",probe.body());
        }
    }
}
