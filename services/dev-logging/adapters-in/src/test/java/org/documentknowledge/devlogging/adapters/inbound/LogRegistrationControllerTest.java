package org.documentknowledge.devlogging.adapters.inbound;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.devlogging.adapters.inbound.requests.DeadlineLogRequests;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.application.LogProducerRegistry.*;
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP boundary with port fixtures; real SQL composition is separately tested in bootstrap. */
class LogRegistrationControllerTest {
    final UUID parent=UUID.randomUUID();
    final AtomicReference<LogFailure.Kind> failure=new AtomicReference<>();
    final AtomicReference<RequestDeadline> observed=new AtomicReference<>();
    final String issuedToken="A".repeat(43);
    class Fixture implements AutoCloseable {
        final DeadlineLogRequests requests=new DeadlineLogRequests();
        final ApplicationContext context;
        final EmbeddedServer server;
        final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Fixture() {
            var auth=new LogRegistrationAuthentication() {
                public Registrar registrar(String token) {
                    if(!token.equals("operator")) throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
                    return new Registrar(UUID.randomUUID(),Instant.now().plusSeconds(3600));
                }
                public Delegator delegator(String token) {
                    if(!token.equals("attestor")) throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
                    return new Delegator(UUID.randomUUID(),Instant.now().plusSeconds(3600),Set.of(parent),Set.of(),
                        Set.of(new ScopePermission(LogScope.Kind.TENANT,"https://issuer.invalid","tenant-a")));
                }
            };
            var registry=new LogProducerRegistry() {
                Issued issued(ArtifactRegistration artifact,LogScope.Description scope,Instant capture,RequestDeadline deadline) {
                    observed.set(deadline);deadline.requireTime();
                    if(failure.get()!=null)throw new LogFailure(failure.get());
                    return new Issued(new ProducerScope(parent,scope.identified(UUID.randomUUID()),artifact.binding(),Instant.now().plusSeconds(3600)),capture,issuedToken);
                }
                public Issued register(Registrar grant,ArtifactRegistration artifact,LogScope.Description scope,Instant expiry,RequestDeadline deadline) {
                    return issued(artifact,scope,Instant.now().plusSeconds(60),deadline);
                }
                public Issued delegate(Delegator grant,UUID id,ArtifactRegistration artifact,LogScope.Description scope,UUID epoch,Instant capture,RequestDeadline deadline) {
                    grant.requireScope(id,scope); return issued(artifact,scope,capture,deadline);
                }
                public void revoke(Registrar grant,UUID id,RequestDeadline deadline) {
                    observed.set(deadline); if(failure.get()!=null)throw new LogFailure(failure.get());
                }
            };
            context=ApplicationContext.builder().properties(Map.of("micronaut.server.host","127.0.0.1","micronaut.server.port",-1,
                "micronaut.server.netty.worker.threads",2,"micronaut.server.netty.parent.threads",1,
                "micronaut.server.max-request-size",32768,"micronaut.server.max-request-buffer-size",32768)).build();
            context.registerSingleton(LogRegistrationAuthentication.class,auth);context.registerSingleton(LogProducerRegistry.class,registry);
            context.registerSingleton(DeadlineLogRequests.class,requests);context.start();server=context.getBean(EmbeddedServer.class).start();
        }
        HttpResponse<String> send(String method,String path,byte[] body,String... headers) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getPort()+"/api/v1/dev-logs/"+path)).timeout(Duration.ofSeconds(8))
                .header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofByteArray(body));
            for(int i=0;i<headers.length;i+=2)builder.header(headers[i],headers[i+1]);
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
        public void close(){client.close();server.close();context.close();requests.close();}
    }
    byte[] registration(){return LogRegistrationJsonTest.bytes(LogRegistrationJsonTest.registration(LogRegistrationJsonTest.TENANT));}
    byte[] delegation(String tenant){return LogRegistrationJsonTest.bytes("{\"parentProducerId\":\""+parent+"\",\"binding\":"+LogRegistrationJsonTest.BINDING+",\"scope\":"+LogRegistrationJsonTest.TENANT.replace("tenant-a",tenant)+",\"contextEpoch\":\""+UUID.randomUUID()+"\",\"captureUntil\":\"2026-11-03T10:00:00Z\"}");}
    @Test void actualHttpRoutesReturnCommittedGrantsAndIdempotentRevocation() throws Exception {
        try(var fixture=new Fixture()) {
            var root=fixture.send("POST","registrations",registration(),"Authorization","Bearer operator");
            assertEquals(201,root.statusCode());assertTrue(root.body().contains(parent.toString()));assertTrue(root.body().contains(issuedToken));
            assertNotNull(observed.get()); assertTrue(observed.get().remainingNanos()>0);
            var child=fixture.send("POST","delegations",delegation("tenant-a"),"Authorization","bearer attestor");
            assertEquals(201,child.statusCode());assertTrue(child.body().contains("captureUntil"));assertTrue(child.body().contains("replayUntil"));
            for(int i=0;i<2;i++) {
                var revoked=fixture.send("DELETE","registrations/"+parent,new byte[0],"Authorization","Bearer operator");
                assertEquals(204,revoked.statusCode());assertEquals("",revoked.body());
                assertEquals("no-store",revoked.headers().firstValue("Cache-Control").orElseThrow());
            }
            assertEquals("nosniff",root.headers().firstValue("X-Content-Type-Options").orElseThrow());
        }
    }
    @Test void authenticatesBeforeParsingAndRejectsCrossRoleAndTenantRequests() throws Exception {
        try(var fixture=new Fixture()) {
            for(String credential:List.of("Bearer producer","Bearer reader","Bearer attestor","Basic operator")) {
                var response=fixture.send("POST","registrations",new byte[]{(byte)0xff},"Authorization",credential);
                assertEquals(401,response.statusCode());assertFalse(response.body().contains(issuedToken));
            }
            assertEquals(401,fixture.send("POST","registrations",registration()).statusCode());
            assertEquals(401,fixture.send("POST","registrations",registration(),"Authorization","Bearer operator","Authorization","Bearer operator").statusCode());
            assertEquals(401,fixture.send("POST","delegations",delegation("tenant-a"),"Authorization","Bearer operator").statusCode());
            assertEquals(403,fixture.send("POST","delegations",delegation("tenant-b"),"Authorization","Bearer attestor").statusCode());
            assertNull(observed.get());
        }
    }
    @Test void appliesRawBoundsClosedParserAndFixedSafeFailureCodes() throws Exception {
        try(var fixture=new Fixture()) {
            for(byte[] invalid:List.of(new byte[]{(byte)0xff},LogRegistrationJsonTest.bytes("{}"),LogRegistrationJsonTest.bytes(new String(registration(),java.nio.charset.StandardCharsets.UTF_8)+" {}")))
                assertEquals(400,fixture.send("POST","registrations",invalid,"Authorization","Bearer operator").statusCode());
            assertEquals(400,fixture.send("POST","registrations?unknown=secret",registration(),"Authorization","Bearer operator").statusCode());
            assertEquals(400,fixture.send("POST","registrations",registration(),"Authorization","Bearer operator","Content-Encoding","identity").statusCode());
            assertEquals(413,fixture.send("POST","registrations",new byte[32769],"Authorization","Bearer operator").statusCode());
            assertEquals(400,fixture.send("DELETE","registrations/1-1-1-1-1",new byte[0],"Authorization","Bearer operator").statusCode());
            var codes=Map.of(LogFailure.Kind.INVALID,400,LogFailure.Kind.EXPIRED,403,LogFailure.Kind.FORBIDDEN,403,
                LogFailure.Kind.CONFLICT,409,LogFailure.Kind.RATE_LIMITED,429,LogFailure.Kind.CAPACITY,503,LogFailure.Kind.UNAVAILABLE,503);
            for(var entry:codes.entrySet()) {
                failure.set(entry.getKey());var response=fixture.send("POST","registrations",registration(),"Authorization","Bearer operator");
                assertEquals(entry.getValue().intValue(),response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                assertFalse(response.body().contains(issuedToken));assertFalse(response.body().contains("operator"));
            }
        }
    }
    @Test void actualHttpRejectsUtf16EvenWhenOctetsPassUtf8Decoder() throws Exception {
        try(var fixture=new Fixture()) {
            String body=new String(registration(),java.nio.charset.StandardCharsets.UTF_8);
            for(var encoding:List.of(java.nio.charset.StandardCharsets.UTF_16BE,java.nio.charset.StandardCharsets.UTF_16LE))
                assertEquals(400,fixture.send("POST","registrations",body.getBytes(encoding),"Authorization","Bearer operator").statusCode(),encoding.name());
            assertEquals(201,fixture.send("POST","registrations",LogRegistrationJsonTest.bytes(body.replace("actor","потребител")),"Authorization","Bearer operator").statusCode());
        }
    }

}
