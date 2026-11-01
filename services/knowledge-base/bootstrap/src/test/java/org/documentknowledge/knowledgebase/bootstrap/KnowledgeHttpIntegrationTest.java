package org.documentknowledge.knowledgebase.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeRequestFactory;
import org.documentknowledge.knowledgebase.application.*;
import org.documentknowledge.knowledgebase.application.DocumentShareBatchAuthorityPort.*;
import org.documentknowledge.knowledgebase.application.DocumentShareEligibilityPort.ShareEligibilityResult;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.*;
import org.documentknowledge.knowledgebase.domain.KbModels.Collection;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;

/** Real bearer validation/server/closed JSON boundary with the actual pure service, no controller mock. */
class KnowledgeHttpIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final AtomicInteger OPENS=new AtomicInteger(),CLOSES=new AtomicInteger();
    static Memory memory;
    static volatile boolean unavailable,revokedAtResponse,pauseSetup,pauseFinalAssessment,pauseEncoding,pauseResponseGuard;
    static volatile long authDelayMillis,catalogDelayMillis;
    static java.util.concurrent.CountDownLatch PAUSED;
    static java.util.concurrent.CompletableFuture<Void> RELEASE;
    static final java.util.concurrent.atomic.AtomicReference<Deadline> CAPTURED=new java.util.concurrent.atomic.AtomicReference<>(),WHOLE=new java.util.concurrent.atomic.AtomicReference<>();
    static final AtomicInteger ASSESSMENTS=new AtomicInteger();
    final SecurityIntegrationTest identity=new SecurityIntegrationTest();
    EmbeddedServer server;HttpClient client;String bearer;
    @BeforeEach void start() throws Exception{
        memory=new Memory();OPENS.set(0);CLOSES.set(0);unavailable=false;revokedAtResponse=false;pauseSetup=false;pauseFinalAssessment=false;pauseEncoding=false;pauseResponseGuard=false;authDelayMillis=0;catalogDelayMillis=0;PAUSED=new java.util.concurrent.CountDownLatch(1);RELEASE=new java.util.concurrent.CompletableFuture<>();CAPTURED.set(null);WHOLE.set(null);ASSESSMENTS.set(0);
        var settings=identity.settings("standalone");settings.put("dkp.test.kb-http",true);
        server=ApplicationContext.run(EmbeddedServer.class,settings);client=HttpClient.newHttpClient();bearer="Bearer "+identity.token(identity.claims());
    }
    @AfterEach void stop(){RELEASE.complete(null);client.close();server.close();}
    @Factory @Requires(property="dkp.test.kb-http",value="true")
    static class ServiceFixture {
        @Singleton @Replaces(value=KnowledgeRequestFactory.class,factory=KnowledgeRuntimeFactory.class)
        KnowledgeRequestFactory requests(){return (context,claims,original)->{
            assertEquals(SecurityIntegrationTest.ISSUER,context.actor().issuer());assertEquals("user",context.actor().subject());assertTrue(Set.of("tenant-a","tenant-b").contains(context.tenant()));assertTrue(original.startsWith("Bearer "));OPENS.incrementAndGet();
            WHOLE.set(io.micronaut.http.context.ServerRequestContext.currentRequest().orElseThrow().getAttribute("dkp.kb.request-deadline",Deadline.class).orElseThrow());
            if(pauseSetup){CAPTURED.set(WHOLE.get());PAUSED.countDown();RELEASE.join();}
            if(unavailable)throw KbFailure.unavailable();
            var positions=new HashMap<String,CollectionCursorPort.Position>();var members=new HashMap<String,MemberCursorPort.Position>();
            var setup=org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeSetupBudget.current();
            KnowledgeAuthorityPort authority=new KnowledgeAuthorityPort(){public Result authorize(Context c,Action a){return setup.call(()->{if(catalogDelayMillis>0)new java.util.concurrent.CompletableFuture<Void>().completeOnTimeout(null,catalogDelayMillis,java.util.concurrent.TimeUnit.MILLISECONDS).join();return Result.ALLOW;});}public Result delegationCeiling(Context c,Action a){return Result.ALLOW;}};
            DocumentShareBatchAuthorityPort sharing=(c,docs,d)->{if(pauseFinalAssessment&&ASSESSMENTS.incrementAndGet()==2){CAPTURED.set(d);PAUSED.countDown();RELEASE.join();}return new ShareBatchAssessment(c,BatchEvidence.ACTION,docs.stream().map(e->new Assessment(e.documentId(),0,"development-restricted",1)).sorted(Comparator.comparing(a->a.documentId().toString())).toList());};
            DocumentShareEligibilityPort eligibility=(c,docs,d)->new ShareEligibilityResult(c,BatchEvidence.ACTION,BatchEvidence.requestedDigest(docs),docs.stream().map(id->new Assessment(id,0,"development-restricted",1)).sorted(Comparator.comparing(a->a.documentId().toString())).toList());
            var service=new KnowledgeService(memory,authority,(c,s)->KnowledgeAuthorityPort.Result.ALLOW,sharing,eligibility,()->"A".repeat(43),new MemberCursorPort(){public String encode(Position p){members.put("member",p);return "member";}public Position decode(String t){return Optional.ofNullable(members.get(t)).orElseThrow(()->new KbFailure(400,"BAD_REQUEST"));}},new CollectionCursorPort(){public String encode(Position p){positions.put("collection",p);return "collection";}public Position decode(String t){return Optional.ofNullable(positions.get(t)).orElseThrow(()->new KbFailure(400,"BAD_REQUEST"));}},new RequestAdmission(),Clock.systemUTC(),UUID::randomUUID);
            return new KnowledgeRequestFactory.RequestHandle(service,()->{if(pauseResponseGuard){PAUSED.countDown();RELEASE.join();}if(revokedAtResponse)throw new KbFailure(403,"FORBIDDEN");},CLOSES::incrementAndGet);
        };}
        @Singleton @Replaces(org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeFinalResponse.class)
        org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeFinalResponse responses(){return new org.documentknowledge.knowledgebase.adapters.inbound.KnowledgeFinalResponse(){
            @Override protected byte[] encode(Object value){if(pauseEncoding){PAUSED.countDown();RELEASE.join();}return super.encode(value);}
        };}
    }
    @io.micronaut.http.annotation.ServerFilter("/api/v1/knowledge/**")
    @io.micronaut.core.annotation.Order(io.micronaut.core.order.Ordered.HIGHEST_PRECEDENCE+1)
    @Requires(property="dkp.test.kb-http",value="true")
    static class SlowIdentityFixture {
        @io.micronaut.http.annotation.RequestFilter void beforeAuthentication(){if(authDelayMillis>0)new java.util.concurrent.CompletableFuture<Void>().completeOnTimeout(null,authDelayMillis,java.util.concurrent.TimeUnit.MILLISECONDS).join();}
    }
    HttpResponse<String> send(String method,String path,String body,String key) throws Exception{return send(method,path,body,key,bearer,"tenant-a","application/json");}
    HttpResponse<String> send(String method,String path,String body,String key,String auth,String tenant,String type)throws Exception{
        var request=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge"+path)).timeout(Duration.ofSeconds(12));
        if(auth!=null)request.header("Authorization",auth);if(tenant!=null)request.header("X-DKP-Tenant",tenant);if(key!=null)request.header("Idempotency-Key",key);if(type!=null)request.header("Content-Type",type);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    Map<?,?> object(HttpResponse<String> r,int status){assertEquals(status,r.statusCode(),r.body());assertEquals(List.of("no-store"),r.headers().allValues("Cache-Control"));assertTrue(r.headers().allValues("Content-Encoding").isEmpty());assertTrue(r.headers().firstValue("ETag").isEmpty());return JSON.readValue(r.body(),Map.class);}
    void error(HttpResponse<String> r,int status,String code){assertEquals(Map.of("code",code),object(r,status));}
    String create()throws Exception{return (String)object(send("POST","/collections","{\"title\":\"Collection\",\"description\":\"Description\"}","create"),201).get("collectionId");}
    @Test void allFifteenRoutesAndMinimalIdempotentReceiptsUseActualService()throws Exception{
        String id=create();String base="/collections/"+id;
        assertEquals(Set.of("collectionId","revision"),object(send("POST","/collections","{\"description\":\"Description\",\"title\":\"Collection\"}","create"),201).keySet());assertEquals(1,memory.collections.size());
        error(send("POST","/collections","{\"title\":\"Changed\",\"description\":\"Description\"}","create"),409,"IDEMPOTENCY_CONFLICT");
        var page=object(send("GET","/collections?limit=1",null,null),200);assertEquals(1,((List<?>)page.get("items")).size());assertTrue(page.containsKey("nextCursor"));assertNull(page.get("nextCursor"));
        assertEquals(8,object(send("GET",base,null,null),200).size());
        assertEquals(Set.of("collectionId","revision"),object(send("PATCH",base,"{\"expectedRevision\":0,\"metadata\":{\"title\":\"Edited\",\"description\":\"\"}}","edit"),200).keySet());
        assertEquals(8,((List<?>)object(send("GET",base+"/acl",null,null),200).get("rules")).size());
        object(send("PATCH",base+"/acl","{\"expectedRevision\":1,\"mode\":\"DELEGATE\",\"changes\":[{\"operation\":\"ADD_ALLOW\",\"principal\":{\"issuer\":\"https://issuer\",\"subject\":\"target\"},\"action\":\"knowledge.collection.inspect\"}],\"reason\":\"share\"}","acl"),200);
        object(send("GET",base+"/audience",null,null),200);
        object(send("PUT",base+"/audience","{\"expectedRevision\":2,\"rules\":[{\"effect\":\"ALLOW\",\"selector\":{\"kind\":\"TENANT\",\"tenant\":\"tenant-a\"}}],\"reason\":\"audience\"}","policy"),200);
        assertEquals("ALLOW",object(send("POST",base+"/audience-evaluations","{}",null),200).get("decision"));
        UUID document=UUID.randomUUID();String add="{\"expectedRevision\":3,\"documentId\":\""+document+"\",\"expectedDocumentRevision\":0,\"sharingScope\":\"SELECTED_TENANT\",\"reason\":\"attach\"}";
        var member=object(send("POST",base+"/memberships",add,"member"),201);assertEquals(Set.of("collectionId","membershipId","revision"),member.keySet());String memberId=(String)member.get("membershipId");
        assertEquals(1,((List<?>)object(send("GET",base+"/memberships?limit=50",null,null),200).get("items")).size());
        var snapshot=object(send("POST","/documents/"+document+"/policy-snapshots","{\"expectedDocumentRevision\":0}",null),201);assertEquals(43,((String)snapshot.get("snapshotToken")).length());
        assertEquals("COMPATIBLE",object(send("POST","/documents/"+document+"/policy-validations","{\"snapshotToken\":\""+snapshot.get("snapshotToken")+"\",\"expectedDocumentRevision\":0}",null),200).get("decision"));
        object(send("PATCH",base+"/memberships/"+memberId,"{\"expectedRevision\":4,\"expectedMembershipRevision\":0,\"expectedDocumentRevision\":0,\"state\":\"INACTIVE\",\"reason\":\"remove\"}","inactive"),200);
        object(send("PATCH",base+"/status","{\"expectedRevision\":5,\"status\":\"ARCHIVED\",\"reason\":\"archive\"}","archive"),200);
        assertEquals(OPENS.get(),CLOSES.get());assertEquals(7,memory.outcomes.size());
    }
    @Test void closedBodiesDuplicatesNullNumbersUnknownFieldsAndMissingKeysNeverWrite()throws Exception{
        for(String body:List.of("null","[]","{}","{\"title\":\"A\",\"description\":null}","{\"title\":\"A\",\"title\":\"B\",\"description\":\"\"}","{\"title\":\"A\",\"description\":\"\",\"actor\":\"admin\"}","{\"title\":\"A\",\"description\":\"\"} {}"))error(send("POST","/collections",body,"bad"),400,"BAD_REQUEST");
        error(send("POST","/collections","{\"title\":\"A\",\"description\":\"\"}",null),400,"BAD_REQUEST");
        String id=create();
        for(String revision:List.of("null","-1","1.0","9007199254740992","\"0\"","true"))error(send("PATCH","/collections/"+id,"{\"expectedRevision\":"+revision+",\"metadata\":{\"title\":\"A\",\"description\":\"\"}}","bad"),400,"BAD_REQUEST");
        assertEquals(1,memory.outcomes.size());
    }
    @Test void authenticationTenantAndDependencyFailuresAreClosedAndDoNotLeak()throws Exception{
        error(send("GET","/collections",null,null,null,"tenant-a",null),401,"UNAUTHENTICATED");
        for(String tenant:List.of("","tenant-c"))error(send("GET","/collections",null,null,bearer,tenant,null),403,"FORBIDDEN");
        error(send("GET","/collections",null,null,bearer,null,null),403,"FORBIDDEN");assertEquals(0,OPENS.get());
        error(send("GET","/collections/"+UUID.randomUUID(),null,null),404,"NOT_FOUND");
        unavailable=true;error(send("GET","/collections",null,null),503,"UNAVAILABLE");assertTrue(memory.collections.isEmpty());
    }
    @Test void finalAuthorityRevocationWithholdsSuccessAndClosesRequestTransport()throws Exception{
        revokedAtResponse=true;error(send("POST","/collections","{\"title\":\"A\",\"description\":\"\"}","revoke"),403,"FORBIDDEN");assertEquals(1,CLOSES.get());assertEquals(1,memory.outcomes.size());
        revokedAtResponse=false;object(send("POST","/collections","{\"title\":\"A\",\"description\":\"\"}","revoke"),201);assertEquals(1,memory.outcomes.size());
    }
    @Test void frameworkAndTransportBoundsUseFiniteCodes()throws Exception{
        error(send("POST","/collections","{}","x",bearer,"tenant-a","text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");
        error(send("POST","/collections","x".repeat(16385),"x"),413,"PAYLOAD_TOO_LARGE");
        for(String path:List.of("/collections?limit=0","/collections?limit=51","/collections?limit=01","/collections?limit=1&limit=2","/collections?actor=admin","/collections/00000000-0000-0000-0000-00000000000A","/collections/00000000%2d0000-0000-0000-000000000000"))error(send("GET",path,null,null),400,"BAD_REQUEST");
        var rejected=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections")).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").header("Accept","application/pdf").GET().build();error(client.send(rejected,HttpResponse.BodyHandlers.ofString()),406,"NOT_ACCEPTABLE");
        assertTrue(memory.outcomes.isEmpty());
    }
    @Test void duplicateHeadersEncodedBodiesAndQueryOnDetailCannotBypassBoundary()throws Exception{
        for(String header:List.of("Authorization","X-DKP-Tenant","Idempotency-Key","Content-Type")){
            var request=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections")).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").header(header,header.equals("Authorization")?bearer:header.equals("X-DKP-Tenant")?"tenant-a":"duplicate").POST(HttpRequest.BodyPublishers.ofString("{}"));
            if(header.equals("Idempotency-Key"))request.header(header,"other");if(header.equals("Content-Type"))request.header(header,"application/json");
            var result=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            // Security may reject ambiguous Authorization before the controller; it never forwards it.
            if(header.equals("Authorization"))assertTrue(Set.of(400,401).contains(result.statusCode()),result.body());else error(result,400,"BAD_REQUEST");
        }
        for(String encoding:List.of("gzip","identity")){
            var request=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections")).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").header("Content-Type","application/json").header("Content-Encoding",encoding).POST(HttpRequest.BodyPublishers.ofString("{}"));
            error(client.send(request.build(),HttpResponse.BodyHandlers.ofString()),400,"BAD_REQUEST");
        }
        error(send("GET","/collections/"+UUID.randomUUID()+"?limit=1",null,null),400,"BAD_REQUEST");assertEquals(0,OPENS.get());
    }
    @Test void jsonNegotiationIsExplicitAndCannotOverrideSpecificZeroQuality()throws Exception{
        for(String accept:List.of("application/json","application/*","*/*","text/plain, application/json;q=0.5")){
            var request=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections")).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").header("Accept",accept).GET();object(client.send(request.build(),HttpResponse.BodyHandlers.ofString()),200);
        }
        for(String accept:List.of("application/json;q=0, */*;q=1","application/*;q=0, */*;q=1","text/plain")){
            var request=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections")).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").header("Accept",accept).GET();error(client.send(request.build(),HttpResponse.BodyHandlers.ofString()),406,"NOT_ACCEPTABLE");
        }
    }
    @Test void dribblingMetadataIsCancelledAtOriginalEightSecondDeadlineBeforeService()throws Exception{
        try(var socket=new java.net.Socket("127.0.0.1",server.getPort())){
            socket.setSoTimeout(12000);var out=socket.getOutputStream();
            String headers="POST /api/v1/knowledge/collections HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: "+bearer+"\r\nX-DKP-Tenant: tenant-a\r\nContent-Type: application/json\r\nIdempotency-Key: slow\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{";
            long start=System.nanoTime();out.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.flush();
            String response=new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 503"),response);assertTrue(response.contains("UNAVAILABLE"),response);assertTrue(System.nanoTime()-start<Duration.ofSeconds(11).toNanos());assertEquals(0,OPENS.get());assertTrue(memory.outcomes.isEmpty());
        }
    }
    @Test void chunkedOversizeAndRepeatedRealGzipCannotReachAuthority()throws Exception{
        var chunked=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections")).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").header("Content-Type","application/json").header("Idempotency-Key","oversize").POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(new byte[16385])));
        error(client.send(chunked.build(),HttpResponse.BodyHandlers.ofString()),413,"PAYLOAD_TOO_LARGE");
        var bytes=new java.io.ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write("{\"title\":\"Encoded\",\"description\":\"\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        for(int i=0;i<3;i++){
            var encoded=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections")).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").header("Content-Type","application/json").header("Idempotency-Key","gzip").header("Content-Encoding","gzip").POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()));
            error(client.send(encoded.build(),HttpResponse.BodyHandlers.ofString()),400,"BAD_REQUEST");
        }
        assertEquals(0,OPENS.get());assertTrue(memory.outcomes.isEmpty());
    }
    static void awaitCondition(java.util.function.BooleanSupplier condition,long milliseconds){
        long until=System.nanoTime()+Duration.ofMillis(milliseconds).toNanos();while(!condition.getAsBoolean()&&System.nanoTime()<until)java.util.concurrent.locks.LockSupport.parkNanos(1000000);assertTrue(condition.getAsBoolean());
    }
    static boolean cancelled(Deadline deadline){try{deadline.check();return false;}catch(KbFailure unavailable){return true;}}
    @Test void actualClientDisconnectInvalidatesPausedFinalAuthorityBeforeShortPhaseExpiry()throws Exception{
        String collection=create();int before=memory.executions;int closed=CLOSES.get();pauseFinalAssessment=true;
        String body="{\"expectedRevision\":0,\"documentId\":\""+UUID.randomUUID()+"\",\"expectedDocumentRevision\":0,\"sharingScope\":\"SELECTED_TENANT\",\"reason\":\"abort\"}";
        try(var socket=new java.net.Socket("127.0.0.1",server.getPort())){
            String request="POST /api/v1/knowledge/collections/"+collection+"/memberships HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: "+bearer+"\r\nX-DKP-Tenant: tenant-a\r\nContent-Type: application/json\r\nIdempotency-Key: disconnect\r\nContent-Length: "+body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+"\r\n\r\n"+body;
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));socket.getOutputStream().flush();assertTrue(PAUSED.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(CAPTURED.get().remainingNanos()>Duration.ofSeconds(1).toNanos());socket.setSoLinger(true,0);
        }
        awaitCondition(()->cancelled(CAPTURED.get()),500);assertTrue(cancelled(WHOLE.get()),"Transport cancellation must invalidate parent ownership, not merely expire the1.5s phase");
        RELEASE.complete(null);awaitCondition(()->CLOSES.get()==closed+1,1000);assertEquals(before,memory.executions);assertEquals(1,memory.outcomes.size());assertTrue(memory.members.isEmpty());
    }
    @Test void activeCumulativeSetupTimeoutRespondsBeforeNoncooperativeSetupReturnsAndLateAllowCannotWrite()throws Exception{
        pauseSetup=true;
        var request=HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections")).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").header("Content-Type","application/json").header("Idempotency-Key","owner-timeout").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Deadline\",\"description\":\"\"}"));
        long started=System.nanoTime();var response=client.sendAsync(request.build(),HttpResponse.BodyHandlers.ofString());assertTrue(PAUSED.await(3,java.util.concurrent.TimeUnit.SECONDS));
        error(response.get(10,java.util.concurrent.TimeUnit.SECONDS),503,"UNAVAILABLE");assertTrue(System.nanoTime()-started<Duration.ofSeconds(3).toNanos());assertFalse(RELEASE.isDone());assertTrue(cancelled(CAPTURED.get()));assertEquals(0,memory.executions);
        RELEASE.complete(null);awaitCondition(()->CLOSES.get()==1,1000);assertEquals(0,memory.executions);assertTrue(memory.outcomes.isEmpty());assertTrue(memory.collections.isEmpty());
    }
    @Test void finalEncodingHasOwn500msBudgetAndWithheldCommittedReceiptReconcilesOnce()throws Exception{
        pauseEncoding=true;long started=System.nanoTime();String body="{\"title\":\"Encoded\",\"description\":\"\"}";
        error(send("POST","/collections",body,"encode-timeout"),503,"UNAVAILABLE");assertTrue(PAUSED.await(1,java.util.concurrent.TimeUnit.SECONDS));assertTrue(System.nanoTime()-started<Duration.ofSeconds(2).toNanos());assertTrue(WHOLE.get().remainingNanos()>Duration.ofSeconds(5).toNanos());assertFalse(RELEASE.isDone());assertEquals(1,memory.outcomes.size());
        RELEASE.complete(null);pauseEncoding=false;object(send("POST","/collections",body,"encode-timeout"),201);assertEquals(1,memory.outcomes.size());assertEquals(1,memory.collections.size());
    }
    @Test void frameworkAuthenticationAndCurrentCatalogShareOneTwoSecondAllowance()throws Exception{
        authDelayMillis=1100;catalogDelayMillis=1100;long started=System.nanoTime();
        error(send("POST","/collections","{\"title\":\"Cumulative\",\"description\":\"\"}","cumulative"),503,"UNAVAILABLE");
        long elapsed=Duration.ofNanos(System.nanoTime()-started).toMillis();assertTrue(elapsed>=1800&&elapsed<2800,"elapsed_ms="+elapsed);
        awaitCondition(()->CLOSES.get()==1,1500);assertEquals(0,memory.executions);assertEquals(0,memory.reads);assertTrue(memory.outcomes.isEmpty());
    }
    @Test void inspectionBusinessReadsFinishBeforeFinalGuardAndNeverRunOnEncodingWorker()throws Exception{
        String id=create();
        for(String suffix:List.of("","/acl","/audience")){
            PAUSED=new java.util.concurrent.CountDownLatch(1);RELEASE=new java.util.concurrent.CompletableFuture<>();pauseResponseGuard=true;int before=memory.reads;
            var result=client.sendAsync(HttpRequest.newBuilder(server.getURI().resolve("/api/v1/knowledge/collections/"+id+suffix)).header("Authorization",bearer).header("X-DKP-Tenant","tenant-a").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertTrue(PAUSED.await(1,java.util.concurrent.TimeUnit.SECONDS));int completedReads=memory.reads;assertTrue(completedReads>before);
            error(result.get(2,java.util.concurrent.TimeUnit.SECONDS),503,"UNAVAILABLE");assertFalse(RELEASE.isDone());RELEASE.complete(null);pauseResponseGuard=false;
            awaitCondition(()->CLOSES.get()==OPENS.get(),1000);assertEquals(completedReads,memory.reads,"No business read may resume after final response timeout");
        }
        assertEquals(1,memory.outcomes.size());
    }
    static final class Memory implements KBUnitOfWork,CollectionRepository {
        Map<UUID,Collection> collections=new HashMap<>(); Map<UUID,Membership> members=new HashMap<>(); Map<UUID,Scope> scopes=new HashMap<>(); Map<CommandKey,CommandOutcome> outcomes=new HashMap<>(); Map<String,Snapshot> snapshots=new HashMap<>();
        List<Fact> audit=new ArrayList<>(); long fence; int executions,histories,reads; boolean inTransaction,failBeforeCommit,unknownCommit;
        public <T>T read(Deadline d,Function<CollectionRepository,T> f) { assertFalse(Thread.currentThread().getName().startsWith("kb-response-"),"Business SQL must not execute inside response supplier");reads++; assertFalse(inTransaction); d.check(); inTransaction=true; try { return f.apply(this); } finally { inTransaction=false; } }
        public <T>T execute(LockPlan plan,Deadline d,Function<CollectionRepository,T> f) {
            assertFalse(inTransaction); d.check(); executions++; var oldC=new HashMap<>(collections); var oldM=new HashMap<>(members); var oldS=new HashMap<>(scopes); var oldO=new HashMap<>(outcomes); var oldP=new HashMap<>(snapshots); var oldA=new ArrayList<>(audit); long oldF=fence; int oldH=histories;
            inTransaction=true; T result;
            try { result=f.apply(this); d.check(); if(failBeforeCommit) throw KbFailure.unavailable(); }
            catch(RuntimeException failure) { collections=oldC; members=oldM; scopes=oldS; outcomes=oldO; snapshots=oldP; audit=oldA; fence=oldF; histories=oldH; throw failure; }
            finally { inTransaction=false; }
            if(unknownCommit) throw KbFailure.unavailable(); return result;
        }
        public long tenantFence(String tenant) { return fence; }
        public Optional<Collection> collection(String tenant,UUID id) { return Optional.ofNullable(collections.get(id)).filter(c->c.tenant().equals(tenant)); }
        public List<Collection> visibleCollections(Context c,UUID after,int limit) { return collections.values().stream().filter(k->k.tenant().equals(c.tenant())&&k.permits(c.actor(),Action.INSPECT)&&(after==null||k.collectionId().toString().compareTo(after.toString())>0)).sorted(Comparator.comparing(k->k.collectionId().toString())).limit(limit).toList(); }
        public List<Membership> members(String tenant,UUID id) { return members.values().stream().filter(m->m.tenant().equals(tenant)&&m.collectionId().equals(id)).sorted(Comparator.comparing(m->m.membershipId().toString())).toList(); }
        public List<Membership> memberships(String tenant,UUID document) { return members.values().stream().filter(m->m.tenant().equals(tenant)&&m.documentId().equals(document)).sorted(Comparator.comparing(m->m.collectionId().toString())).toList(); }
        public Scope scope(String tenant,UUID doc) { return scopes.getOrDefault(doc,new Scope(tenant,doc,0)); }
        public Optional<CommandOutcome> command(CommandKey key) { return Optional.ofNullable(outcomes.get(key)); }
        public Optional<Snapshot> snapshot(String hash,Context c,UUID doc) { return Optional.ofNullable(snapshots.get(hash)).filter(s->s.context().equals(c)&&s.documentId().equals(doc)); }
        public void insertCollection(Collection c) { assertNull(collections.putIfAbsent(c.collectionId(),c)); }
        public void saveCollection(Collection c,long expected) { assertEquals(expected,collections.get(c.collectionId()).revision()); collections.put(c.collectionId(),c); }
        public void saveMembership(Membership m,Long expected,Principal current,Instant at) { if(expected!=null) assertEquals(expected.longValue(),members.get(m.membershipId()).revision()); members.put(m.membershipId(),m); histories++; }
        public void saveScope(Scope s,long expected) { assertEquals(expected,scope(s.tenant(),s.documentId()).revision()); scopes.put(s.documentId(),s); }
        public void advanceTenantFence(String tenant,long expected) { assertEquals(expected,fence); fence++; }
        public void saveSnapshot(Snapshot s) { snapshots.put(s.tokenHash(),s); }
        public void record(CommandOutcome o,Fact a,Fact outbox) { assertNull(outcomes.putIfAbsent(o.key(),o)); assertNotEquals(a.eventId(),outbox.eventId()); audit.add(a); }
    }
}
