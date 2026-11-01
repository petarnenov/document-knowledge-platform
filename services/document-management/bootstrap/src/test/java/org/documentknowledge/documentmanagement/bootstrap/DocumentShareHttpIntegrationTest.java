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
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class DocumentShareHttpIntegrationTest {
    private static final String BASE="/api/v1/documents";
    private static final String ISSUER=SecurityIntegrationTest.ISSUER;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> ALL=Set.of("document.create","document.inspect-draft","document.read","document.update-metadata","document.acl.restrict","document.acl.delegate","document.version.append","document.legal-hold.place","document.legal-hold.inspect","document.content.review","document.share-to-collection");
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
        var c=security.settings("standalone");c.put("dkp.test.jwks",false);c.put("dkp.test.sharing.jwks",true);c.put("dkp.dm.catalog.path",catalog.toString());c.put("dkp.dm.cursor.key-path",cursorKey.toString());
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


    static final java.util.concurrent.atomic.AtomicBoolean SLOW_KEYS=new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<String>> LAST_KEYS=new java.util.concurrent.atomic.AtomicReference<>();
    @io.micronaut.context.annotation.Factory
    @io.micronaut.context.annotation.Requires(property="dkp.test.sharing.jwks",value="true")
    static class SharingKeys {
        @jakarta.inject.Singleton @io.micronaut.context.annotation.Replaces(io.micronaut.security.token.jwt.signature.jwks.HttpClientJwksClient.class)
        io.micronaut.security.token.jwt.signature.jwks.JwksClient client(){return(provider,url)->{
            String keys=new com.nimbusds.jose.jwk.JWKSet(SecurityIntegrationTest.KEY.toPublicJWK()).toString();
            if(!SLOW_KEYS.get())return reactor.core.publisher.Mono.just(keys);
            var future=java.util.concurrent.CompletableFuture.supplyAsync(()->keys,java.util.concurrent.CompletableFuture.delayedExecutor(2200,java.util.concurrent.TimeUnit.MILLISECONDS));LAST_KEYS.set(future);
            return reactor.core.publisher.Mono.fromFuture(future,true);
        };}
    }
    private static final String STRICT=BASE+"/knowledge-share-assessments",ELIGIBLE=BASE+"/knowledge-share-eligibility";
    private Map<String,Object> strict(UUID...ids){return Map.of("documents",Arrays.stream(ids).map(id->Map.of("documentId",id.toString())).toList());}
    private Map<String,Object> eligibility(UUID...ids){return Map.of("documentIds",Arrays.stream(ids).map(UUID::toString).toList());}
    @Test void realStrictAndEligibilityContractsAreDistinctAndNonMutating()throws Exception{
        try(var server=server()){
            UUID one=created(server,"tenant-a"),two=created(server,"tenant-a");
            var complete=call(server,"POST",STRICT,"user","tenant-a",null,strict(two,one));assertEquals(200,complete.statusCode(),complete.body());assertNoStore(complete);
            var result=body(complete);assertEquals(Set.of("actor","tenant","action","assessments"),result.keySet());assertEquals(Map.of("issuer",ISSUER,"subject","user"),result.get("actor"));assertEquals("tenant-a",result.get("tenant"));assertEquals("document.share-to-collection",result.get("action"));
            var assessments=items(result.get("assessments"));assertEquals(List.of(one.toString(),two.toString()).stream().sorted().toList(),assessments.stream().map(a->a.get("documentId")).toList());
            for(var assessment:assessments){assertEquals(Set.of("documentId","documentRevision","classification","classificationPolicyRevision"),assessment.keySet());assertEquals("development-restricted",assessment.get("classification"));assertEquals(0,((Number)assessment.get("documentRevision")).intValue());assertEquals(1,((Number)assessment.get("classificationPolicyRevision")).intValue());}
            UUID missing=UUID.randomUUID();var subset=call(server,"POST",ELIGIBLE,"user","tenant-a",null,eligibility(missing,one));assertEquals(200,subset.statusCode(),subset.body());var visible=body(subset);assertEquals(Set.of("actor","tenant","action","requestedSetDigest","eligible"),visible.keySet());assertEquals(org.documentknowledge.documentmanagement.application.sharing.DocumentShareService.requestedSetDigest(List.of(missing,one)),visible.get("requestedSetDigest"));assertEquals(List.of(one.toString()),items(visible.get("eligible")).stream().map(a->a.get("documentId")).toList());error(call(server,"POST",STRICT,"user","tenant-a",null,strict(one,missing)),404);
            var foreign=call(server,"POST",ELIGIBLE,"user","tenant-b",null,eligibility(one));assertEquals(200,foreign.statusCode());assertTrue(items(body(foreign).get("eligible")).isEmpty());
            try(Connection c=admin.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM dm.audit_facts")){assertTrue(r.next());assertEquals(2,r.getLong(1),"Only the two real creates wrote audit facts");}
        }
    }
    @Test void currentRouteAndRowAclAreIndependentAndDenyPrecedesStale()throws Exception{
        try(var server=server()){
            UUID id=created(server,"tenant-a");
            error(call(server,"POST",STRICT,"other","tenant-a",null,strict(id)),404);var omitted=call(server,"POST",ELIGIBLE,"other","tenant-a",null,eligibility(id));assertEquals(200,omitted.statusCode());assertTrue(items(body(omitted).get("eligible")).isEmpty());
            var expected=Map.of("documents",List.of(Map.of("documentId",id.toString(),"expectedDocumentRevision",1)));error(call(server,"POST",STRICT,"user","tenant-a",null,expected),409);
            receipt(call(server,"PATCH",BASE+"/"+id+"/acl","user","tenant-a","deny-share",change(0,"RESTRICT","ADD_DENY","user","document.share-to-collection")),200,1);
            error(call(server,"POST",STRICT,"user","tenant-a",null,expected),404);assertTrue(items(body(call(server,"POST",ELIGIBLE,"user","tenant-a",null,eligibility(id))).get("eligible")).isEmpty());
            var without=new HashSet<>(ALL);without.remove("document.share-to-collection");grants(entry("user","tenant-a",without,without));error(call(server,"POST",ELIGIBLE,"user","tenant-a",null,eligibility(UUID.randomUUID())),404);
            Files.writeString(catalog,"partial");error(call(server,"POST",ELIGIBLE,"user","tenant-a",null,eligibility(UUID.randomUUID())),503);
        }
    }
    @Test void strictClosedWireRejectsNullOverridesDuplicateAndNoncanonicalIds()throws Exception{
        try(var server=server()){
            UUID id=created(server,"tenant-a");
            for(Object invalid:List.of(Map.of(),Map.of("documents",List.of()),Map.of("documentIds",List.of(id.toString())),Map.of("documents",List.of(Map.of("documentId",id.toString(),"actor","forged"))),Map.of("documents",List.of(Map.of("documentId",id.toString(),"expectedDocumentRevision",0.5))),Map.of("documents",List.of(Map.of("documentId",id.toString(),"expectedDocumentRevision",9007199254740992L))),Map.of("documents",List.of(Map.of("documentId","FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")))))error(call(server,"POST",STRICT,"user","tenant-a",null,invalid),400);
            error(call(server,"POST",STRICT,"user","tenant-a",null,strict(id,id)),422);error(call(server,"POST",ELIGIBLE,"user","tenant-a",null,eligibility(id,id)),422);
            for(String malformed:List.of("null","{}{}","{\"documents\":[],\"documents\":[]}","{\"documents\":[{\"documentId\":\""+id+"\",\"expectedDocumentRevision\":null}]}"))error(send(server,"POST",STRICT,token("user"),"tenant-a",null,"application/json",malformed),400);
            error(send(server,"POST",STRICT,token("user"),"tenant-a",null,"text/plain","{}"),415);error(send(server,"POST",STRICT,token("user"),"tenant-a",null,"application/json"," ".repeat(16385)),413);
            error(call(server,"POST",STRICT+"?actor=forged","user","tenant-a",null,strict(id)),400);
            for(String method:List.of("GET","PATCH","PUT","DELETE"))error(call(server,method,STRICT,"user","tenant-a",null,null),405);
        }
    }
    @Test void independentlyAuthenticatesOriginalActorAudienceIssuerAndTenant()throws Exception{
        try(var server=server()){
            UUID id=created(server,"tenant-a");error(send(server,"POST",STRICT,null,"tenant-a",null,"application/json",JSON.writeValueAsString(strict(id))),401);
            for(var invalid:List.of(Map.<String,Object>of("aud",List.of("dkp-kb")),Map.<String,Object>of("iss","https://other.example"),Map.<String,Object>of("azp","other"),Map.<String,Object>of("typ","ID"))){var claims=security.claims();claims.putAll(invalid);error(send(server,"POST",STRICT,security.token(claims),"tenant-a",null,"application/json",JSON.writeValueAsString(strict(id))),401);}
            error(call(server,"POST",STRICT,"user","tenant-c",null,strict(id)),403);
        }
    }
    @Test void deadlineIncludesAuthenticationAndLateKeysCannotOpenSharingSql()throws Exception{
        SLOW_KEYS.set(true);
        try(var server=server()){
            var runtime=server.getApplicationContext().getBean(SharingRuntime.class);var pool=SharingRuntime.class.getDeclaredField("pool");pool.setAccessible(true);
            long started=System.nanoTime();var response=call(server,"POST",STRICT,"user","tenant-a",null,strict(UUID.randomUUID()));error(response,503);long elapsed=java.time.Duration.ofNanos(System.nanoTime()-started).toMillis();assertTrue(elapsed>=1200&&elapsed<2000,"Whole authentication-inclusive deadline: "+elapsed);assertNull(pool.get(runtime));
            LAST_KEYS.get().get(2,java.util.concurrent.TimeUnit.SECONDS);assertNull(pool.get(runtime),"Late authentication cannot start SQL or renew request ownership");
            var live=client.send(HttpRequest.newBuilder(server.getURI().resolve("/health/liveness")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,live.statusCode());
        }finally{SLOW_KEYS.set(false);LAST_KEYS.set(null);}
    }

    @Test void fixedLengthHalfBodyTimesOutBeforeSqlAndLateBytesCannotRenewOwnership()throws Exception{
        incompleteBody(false,STRICT,JSON.writeValueAsBytes(strict(UUID.randomUUID())));
    }
    @Test void chunkedHalfBodyTimesOutBeforeSqlAndLateBytesCannotRenewOwnership()throws Exception{
        incompleteBody(true,ELIGIBLE,JSON.writeValueAsBytes(eligibility(UUID.randomUUID())));
    }
    private void incompleteBody(boolean chunked,String endpoint,byte[] payload)throws Exception{
        try(var server=server();var socket=new java.net.Socket("127.0.0.1",server.getPort())){
            socket.setSoTimeout(2500);
            var runtime=server.getApplicationContext().getBean(SharingRuntime.class);
            var pool=SharingRuntime.class.getDeclaredField("pool");pool.setAccessible(true);
            assertNull(pool.get(runtime));assertNoSharingFacts();
            var input=new java.io.BufferedInputStream(socket.getInputStream());var output=socket.getOutputStream();
            String framing=chunked?"Transfer-Encoding: chunked\r\n":"Content-Length: "+payload.length+"\r\n";
            String headers="POST "+endpoint+" HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "+token("user")+"\r\nX-DKP-Tenant: tenant-a\r\nContent-Type: application/json\r\n"+framing+"Connection: keep-alive\r\n\r\n";
            long start=System.nanoTime();output.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if(chunked)output.write("1\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.write(payload,0,1);if(chunked)output.write("\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.flush();
            // The body is STILL incomplete when the real server returns its response.
            RawReply response=rawReply(input);assertNotNull(response);assertEquals(503,response.status());
            assertEquals(Map.of("code","UNAVAILABLE"),JSON.readValue(response.body(),Map.class));
            assertEquals("no-store",response.headers().get("cache-control"));
            long elapsed=java.time.Duration.ofNanos(System.nanoTime()-start).toMillis();
            assertTrue(elapsed>=1200&&elapsed<2000,"1.5s owner plus at most500ms transport/scheduling tolerance: "+elapsed);
            assertNull(pool.get(runtime),"Incomplete request cannot initialize the sharing SQL pool");assertNoSharingFacts();
            // Release actual remaining bytes ONLY AFTER observing the timeout. A live
            // keep-alive channel must process the following liveness barrier; a closed
            // channel must end without a second protected response. Neither renews owner.
            boolean lateBytesWritten=false;String lateBarrier="EOF";
            try{
                if(chunked)output.write((Integer.toHexString(payload.length-1)+"\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                output.write(payload,1,payload.length-1);
                if(chunked)output.write("\r\n0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                output.write("GET /health/liveness HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.flush();lateBytesWritten=true;
            }catch(java.net.SocketException peerClosed){/* A timed-out request may close its transport; late bytes cannot be accepted. */}
            try{
                RawReply late=rawReply(input);
                if(late!=null){lateBarrier="LIVENESS_200";assertEquals(200,late.status());assertEquals(Map.of("status","UP"),JSON.readValue(late.body(),Map.class));assertNull(rawReply(input),"No late sharing/protected response after the liveness barrier");}
            }catch(java.net.SocketException peerClosed){lateBarrier="RESET";/* Terminal cancellation, after the original503 was verified. */}
            assertNull(pool.get(runtime),"Late body completion cannot ever initialize sharing SQL");assertNoSharingFacts();
            System.out.println("DM_HALF_BODY "+(chunked?"CHUNKED":"FIXED_LENGTH")+" timeout_ms="+elapsed+" late_bytes_written="+lateBytesWritten+" terminal_barrier="+lateBarrier+" sharing_pool_initialized=false durable_facts=0");
        }
    }
    private static void assertNoSharingFacts()throws Exception{
        try(Connection c=admin.getConnection();Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT (SELECT count(*) FROM dm.documents),(SELECT count(*) FROM dm.audit_facts),(SELECT count(*) FROM dm.command_outcomes),(SELECT count(*) FROM dm.versions)")){
            assertTrue(rows.next());for(int column=1;column<=4;column++)assertEquals(0,rows.getLong(column));
        }
    }
    private record RawReply(int status,Map<String,String> headers,byte[] body) { }
    private static RawReply rawReply(java.io.InputStream input)throws Exception{
        String status=line(input);if(status==null)return null;
        assertTrue(status.matches("HTTP/1[.][01] [0-9]{3}.*"),"Expected a finite HTTP response status");
        var headers=new HashMap<String,String>();int headerBytes=0;
        for(String header;(header=line(input))!=null&&!header.isEmpty();){headerBytes+=header.length();assertTrue(headerBytes<=8192);int colon=header.indexOf(':');assertTrue(colon>0);String key=header.substring(0,colon).toLowerCase(Locale.ROOT);assertNull(headers.put(key,header.substring(colon+1).strip()),"No duplicate response header");}
        var body=new java.io.ByteArrayOutputStream();
        if("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))){
            while(true){String size=line(input);assertNotNull(size);int length=Integer.parseInt(size,16);assertTrue(length>=0&&length<=8192-body.size());if(length==0){assertEquals("",line(input));break;}byte[] part=input.readNBytes(length);assertEquals(length,part.length);body.write(part);assertEquals("",line(input));}
        }else{
            assertNotNull(headers.get("content-length"),"Bounded response framing required");int length=Integer.parseInt(headers.get("content-length"));assertTrue(length>=0&&length<=8192);byte[] part=input.readNBytes(length);assertEquals(length,part.length);body.write(part);
        }
        return new RawReply(Integer.parseInt(status.substring(9,12)),Map.copyOf(headers),body.toByteArray());
    }
    private static String line(java.io.InputStream input)throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();int previous=-1;
        while(true){int value=input.read();if(value<0){assertEquals(0,bytes.size(),"No truncated HTTP frame");return null;}if(previous=='\r'&&value=='\n'){byte[] data=bytes.toByteArray();return new String(data,0,data.length-1,java.nio.charset.StandardCharsets.US_ASCII);}bytes.write(value);assertTrue(bytes.size()<=8192);previous=value;}
    }
}
