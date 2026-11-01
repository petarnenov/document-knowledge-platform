package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.*;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.session.*;
import jakarta.inject.Singleton;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.browserbff.adapters.inbound.VerifiedOpenIdMapper;
import org.documentknowledge.browserbff.adapters.outbound.FixedKnowledgeGateway;
import org.documentknowledge.browserbff.application.*;
import org.documentknowledge.browserbff.domain.BrowserIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real browser RST -> real fixed JDK HTTP transport -> real stalled private HTTP peer. */
class KnowledgeDisconnectTest {
    private static final String ID="11111111-1111-4111-8111-111111111111";
    static final AtomicReference<KnowledgeDeadline> OWNER=new AtomicReference<>();
    static final AtomicInteger SUCCESSES=new AtomicInteger();
    static CountDownLatch FINISHED;
    @Factory @Requires(property="dkp.test.kb-disconnect",value="true")
    static class Observation {
        @Singleton @Replaces(FixedKnowledgeGateway.class)
        KnowledgeGateway observed(@Value("${dkp.bff.upstreams.kb}") String upstream,JsonMapper json){
            var actual=new FixedKnowledgeGateway(upstream,json,new KnowledgeWireFactory().wire());
            // Observation only: no identity, authorization, result or transport substitution.
            return (method,path,query,body,key,identity,tenant,deadline)->{
                OWNER.set(deadline);
                try{var response=actual.exchange(method,path,query,body,key,identity,tenant,deadline);if(response.status()<400)SUCCESSES.incrementAndGet();return response;}
                finally{FINISHED.countDown();}
            };
        }
    }
    @Test void physicalBrowserResetCancelsLiveOwnerAndUpstreamBeforeNineSecondsAndRejectsLateSuccess()throws Exception{
        OWNER.set(null);SUCCESSES.set(0);FINISHED=new CountDownLatch(1);
        var accepted=new CountDownLatch(1);var upstreamCancelled=new CountDownLatch(1);var release=new CountDownLatch(1);var attemptedLate=new CountDownLatch(1);var peerFailure=new AtomicReference<Throwable>();
        try(var upstream=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var peers=Executors.newSingleThreadExecutor()){
            var peer=peers.submit(()->{try(var connection=upstream.accept()){
                connection.setSoTimeout(2500);var input=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=input.readLine())!=null&&!line.isEmpty()){}accepted.countDown();
                try{if(input.read()==-1)upstreamCancelled.countDown();}catch(SocketException reset){upstreamCancelled.countDown();}catch(SocketTimeoutException stillLive){/* A live connection is not cancellation. */}
                assertTrue(release.await(3,TimeUnit.SECONDS));attemptedLate.countDown();
                String body="{\"collectionId\":\""+ID+"\",\"revision\":1,\"metadata\":{\"title\":\"Synthetic\",\"description\":\"\"},\"status\":\"ACTIVE\",\"membershipRevision\":0,\"audiencePolicyRevision\":1,\"classificationCeiling\":\"development-restricted\",\"classificationPolicyRevision\":1}";
                try{connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: "+body.length()+"\r\nConnection: close\r\n\r\n"+body).getBytes(StandardCharsets.US_ASCII));connection.getOutputStream().flush();}catch(IOException closed){/* A cancelled transport cannot receive a protected success. */}
            }catch(Throwable failure){peerFailure.set(failure);}});
            try(var server=ApplicationContext.run(EmbeddedServer.class,Map.of("micronaut.server.port",-1,"micronaut.server.host","127.0.0.1","dkp.test.kb-disconnect",true,"dkp.bff.portal-origin","https://localhost:18443","dkp.bff.upstreams.kb","http://127.0.0.1:"+upstream.getLocalPort()))){
                String cookie=seed(server);
                try{
                    try(var browser=new Socket("127.0.0.1",server.getPort())){
                        browser.getOutputStream().write(("GET /api/v1/kb/collections/"+ID+" HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: "+cookie+"\r\nAccept: application/json\r\n\r\n").getBytes(StandardCharsets.US_ASCII));browser.getOutputStream().flush();assertTrue(accepted.await(2,TimeUnit.SECONDS));assertNotNull(OWNER.get());assertTrue(OWNER.get().remainingNanos()>TimeUnit.SECONDS.toNanos(6));browser.setSoLinger(true,0);
                    }
                    assertTrue(FINISHED.await(1,TimeUnit.SECONDS),"Physical browser RST must cancel the pending KB transport before its9s deadline");assertThrows(BffFailure.class,OWNER.get()::check);assertTrue(upstreamCancelled.await(1,TimeUnit.SECONDS),"Real HTTP peer must observe EOF/RST before releasing its delayed success");assertEquals(0,SUCCESSES.get());
                }finally{release.countDown();assertTrue(attemptedLate.await(3,TimeUnit.SECONDS));peer.get(3,TimeUnit.SECONDS);assertNull(peerFailure.get());assertTrue(FINISHED.await(3,TimeUnit.SECONDS));}
                assertEquals(0,SUCCESSES.get(),"A delayed valid protected result cannot become a new success after browser cancellation");
            }finally{release.countDown();}
        }
    }
    @SuppressWarnings("unchecked") private static String seed(EmbeddedServer server)throws Exception{
        SessionStore<Session> store=server.getApplicationContext().getBean(SessionStore.class);Session session=store.newSession();var identity=new BrowserIdentity("https://issuer.example","staff-1","Synthetic",List.of("tenant-a"),true,"synthetic-access",Instant.now().plusSeconds(120));session.put(SecurityFilter.AUTHENTICATION,Authentication.build("staff-1",Map.of(VerifiedOpenIdMapper.IDENTITY_ATTRIBUTE,identity)));session.put("csrfToken",".fixture-csrf");session.put("dkp.selected.tenant","tenant-a");store.save(session).get();return "__Host-DKP-SESSION="+session.getId();
    }
}
