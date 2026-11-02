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
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP parser/authority boundary with port fixtures; bootstrap tests supply real SQL. */
class LogIngestionControllerTest {
    final AtomicReference<LogFailure.Kind> failure=new AtomicReference<>();
    final AtomicReference<RequestDeadline> admitted=new AtomicReference<>();
    final AtomicReference<RequestDeadline> catalogDeadline=new AtomicReference<>();
    final AtomicReference<RequestDeadline> storeDeadline=new AtomicReference<>();
    class Fixture implements AutoCloseable {
        final DeadlineLogRequests requests=new DeadlineLogRequests();
        final ApplicationContext context;
        final EmbeddedServer server;
        final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Fixture() {
            context=ApplicationContext.builder().properties(Map.of("micronaut.server.host","127.0.0.1","micronaut.server.port",-1,
                "micronaut.server.netty.worker.threads",2,"micronaut.server.netty.parent.threads",1,
                "micronaut.server.max-request-size",32768,"micronaut.server.max-request-buffer-size",32768)).build();
            context.registerSingleton(LogProducerAuthentication.class,(token,deadline)->{
                admitted.set(deadline);
                if(token.equals("expired"))throw new LogFailure(LogFailure.Kind.EXPIRED);
                if(!token.equals("producer"))throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);return LogIngestionJsonTest.AUTHORITY;
            });
            context.registerSingleton(LogManifestCatalog.class,(authority,deadline)->{
                assertEquals(LogIngestionJsonTest.AUTHORITY,authority);catalogDeadline.set(deadline);return LogIngestionJsonTest.manifest(LogIngestionJsonTest.vocabulary());
            });
            context.registerSingleton(LogPersistence.class,new LogPersistence() {
                public List<Committed> commit(ProducerScope authority,LogBatch batch,Instant now,RequestDeadline deadline) {
                    assertEquals(LogIngestionJsonTest.AUTHORITY,authority);batch.requireBinding(authority);storeDeadline.set(deadline);
                    if(failure.get()!=null)throw new LogFailure(failure.get());
                    return batch.events().stream().map(item->new Committed(item.metadata().eventId(),now)).toList();
                }
                public List<Receipt> receipts(ProducerScope authority,List<UUID> ids,Instant now,RequestDeadline deadline) {
                    assertEquals(LogIngestionJsonTest.AUTHORITY,authority);storeDeadline.set(deadline);
                    return List.of(new Receipt(ids.getFirst(),ReceiptState.COMMITTED,now),new Receipt(ids.get(1),ReceiptState.UNKNOWN,null),new Receipt(ids.get(2),ReceiptState.EXPIRED,null));
                }
            });
            context.registerSingleton(Clock.class,Clock.systemUTC());context.registerSingleton(DeadlineLogRequests.class,requests);
            context.start();server=context.getBean(EmbeddedServer.class).start();
        }
        HttpResponse<String> post(String path,byte[] body,String... headers) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getPort()+"/api/v1/dev-logs/"+path)).timeout(Duration.ofSeconds(8))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
            for(int i=0;i<headers.length;i+=2)request.header(headers[i],headers[i+1]);
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
        public void close(){client.close();server.close();context.close();requests.close();}
    }
    byte[] batch(){return LogIngestionJsonTest.batch(LogIngestionJsonTest.event(Map.of("phase","capture")));}
    @Test void realHttpCommitAndScopedReceiptsShareOriginalAdmittedDeadlineAndFixedHeaders() throws Exception {
        try(var fixture=new Fixture()) {
            var response=fixture.post("batches",batch(),"Authorization","Bearer producer");assertEquals(200,response.statusCode(),response.body());
            assertTrue(response.body().contains("COMMITTED"));assertTrue(response.body().contains("receivedAt"));assertFalse(response.body().contains("capture"));
            assertSame(admitted.get(),catalogDeadline.get());assertSame(admitted.get(),storeDeadline.get());
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            var receipt=fixture.post("receipts",LogIngestionJsonTest.wire(Map.of("eventIds",List.of(LogIngestionJsonTest.EVENT.toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()))),"Authorization","bearer producer");
            assertEquals(200,receipt.statusCode());assertSame(admitted.get(),storeDeadline.get());
            var rows=(List<?>)LogIngestionJsonTest.JSON.readValue(receipt.body(),Map.class).get("receipts");
            assertTrue(((Map<?,?>)rows.getFirst()).containsKey("receivedAt"));
            assertFalse(((Map<?,?>)rows.get(1)).containsKey("receivedAt"));assertFalse(((Map<?,?>)rows.get(2)).containsKey("receivedAt"));
        }
    }
    @Test void authenticatesBeforeParserAndRejectsUntrustedOrUnboundedBodies() throws Exception {
        try(var fixture=new Fixture()) {
            for(String token:List.of("Bearer operator","Bearer reader","Basic producer"))
                assertEquals(401,fixture.post("batches",new byte[]{(byte)0xff},"Authorization",token).statusCode());
            assertEquals(401,fixture.post("batches",batch()).statusCode());
            assertEquals(401,fixture.post("batches",batch(),"Authorization","Bearer producer","Authorization","Bearer producer").statusCode());
            assertEquals(403,fixture.post("batches",batch(),"Authorization","Bearer expired").statusCode());
            assertNull(storeDeadline.get());
            assertEquals(400,fixture.post("batches",new byte[]{(byte)0xff},"Authorization","Bearer producer").statusCode());
            assertEquals(400,fixture.post("batches?tenant=forged",batch(),"Authorization","Bearer producer").statusCode());
            assertEquals(400,fixture.post("batches",batch(),"Authorization","Bearer producer","Content-Encoding","identity").statusCode());
            assertEquals(413,fixture.post("batches",new byte[32769],"Authorization","Bearer producer").statusCode());
            var unknown=fixture.post("batches",LogIngestionJsonTest.batch(LogIngestionJsonTest.event(Map.of("phase","private-secret"))),"Authorization","Bearer producer");
            assertEquals(400,unknown.statusCode());assertTrue(unknown.body().contains("UNREGISTERED_DIAGNOSTIC"));assertFalse(unknown.body().contains("private-secret"));
            for(var charset:List.of(java.nio.charset.StandardCharsets.UTF_16BE,java.nio.charset.StandardCharsets.UTF_16LE))
                assertEquals(400,fixture.post("batches",new String(batch(),java.nio.charset.StandardCharsets.UTF_8).getBytes(charset),"Authorization","Bearer producer").statusCode());
            assertEquals(400,fixture.post("receipts",LogIngestionJsonTest.wire(Map.of("eventIds",List.of())),"Authorization","Bearer producer").statusCode());
        }
    }
    @Test void distinguishesEventTombstone410FromGrant403WithoutClaimingPersistenceOnFailure() throws Exception {
        try(var fixture=new Fixture()) {
            var statuses=Map.ofEntries(Map.entry(LogFailure.Kind.INVALID,400),Map.entry(LogFailure.Kind.UNAUTHENTICATED,401),Map.entry(LogFailure.Kind.FORBIDDEN,403),
                Map.entry(LogFailure.Kind.EXPIRED,403),Map.entry(LogFailure.Kind.EVENT_EXPIRED,410),Map.entry(LogFailure.Kind.CONFLICT,409),Map.entry(LogFailure.Kind.UNREGISTERED,400),
                Map.entry(LogFailure.Kind.TOO_LARGE,413),Map.entry(LogFailure.Kind.RATE_LIMITED,429),Map.entry(LogFailure.Kind.CAPACITY,503),Map.entry(LogFailure.Kind.UNAVAILABLE,503));
            for(var status:statuses.entrySet()) {
                failure.set(status.getKey());var response=fixture.post("batches",batch(),"Authorization","Bearer producer");
                assertEquals(status.getValue().intValue(),response.statusCode(),response.body());assertFalse(response.body().contains("COMMITTED"));
                assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                if(status.getKey()==LogFailure.Kind.EVENT_EXPIRED)assertTrue(response.body().contains("LOG_EVENT_EXPIRED"));
                if(status.getKey()==LogFailure.Kind.EXPIRED)assertTrue(response.body().contains("LOG_GRANT_EXPIRED"));
            }
        }
    }
}
