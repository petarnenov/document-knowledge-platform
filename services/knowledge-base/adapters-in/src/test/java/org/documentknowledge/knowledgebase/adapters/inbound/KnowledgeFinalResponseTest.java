package org.documentknowledge.knowledgebase.adapters.inbound;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeFinalResponseTest {
    Deadline phase(){return Deadline.request().child(Duration.ofMillis(500));}
    @Test void phaseIncludesValidationConstructionAndSerialization(){
        // Initialize framework/serializer classes before measuring the response phase itself.
        KnowledgeWire.encode(Map.of("revision",0));io.micronaut.http.HttpResponse.status(io.micronaut.http.HttpStatus.CREATED);
        var response=new KnowledgeFinalResponse();try{
            var guard=new AtomicBoolean();var body=new AtomicBoolean();var result=response.finish(phase(),()->guard.set(true),()->{assertTrue(guard.get());body.set(true);return Map.of("revision",0);},201);
            assertEquals(201,result.code());assertTrue(body.get());assertEquals("no-store",result.getHeaders().get("Cache-Control"));
            assertEquals(404,assertThrows(KbFailure.class,()->response.finish(phase(),()->{throw new KbFailure(404,"NOT_FOUND");},()->{fail("Hidden body must not be constructed");return null;},200)).status());
            assertEquals(503,assertThrows(KbFailure.class,()->response.finish(phase(),()->{},()->{throw new IllegalStateException("private diagnostics");},200)).status());
        }finally{response.close();}
    }
    @Test void inheritedWholeBudgetCannotBeRenewedByStartingResponsePhase(){
        AtomicLong time=new AtomicLong();Deadline whole=new Deadline(time::get,Duration.ofMillis(10));Deadline child=whole.child(Duration.ofMillis(500));time.set(Duration.ofMillis(11).toNanos());
        var response=new KnowledgeFinalResponse();try{assertEquals(503,assertThrows(KbFailure.class,()->response.finish(child,()->fail("Expired guard"),()->Map.of(),200)).status());}finally{response.close();}
    }
    @Test void callerInterruptionCancelsEncodingAndRetainsInterrupt()throws Exception{
        var entered=new CountDownLatch(1);var done=new CountDownLatch(1);var interrupt=new AtomicBoolean();var failure=new AtomicReference<KbFailure>();
        var response=new KnowledgeFinalResponse(){protected byte[] encode(Object value){entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw KbFailure.unavailable();}return super.encode(value);}};
        try{var caller=Thread.ofPlatform().start(()->{try{response.finish(phase(),()->{},()->Map.of(),200);}catch(KbFailure unavailable){failure.set(unavailable);interrupt.set(Thread.currentThread().isInterrupted());}finally{done.countDown();}});assertTrue(entered.await(1,TimeUnit.SECONDS));caller.interrupt();assertTrue(done.await(1,TimeUnit.SECONDS));assertEquals(503,failure.get().status());assertTrue(interrupt.get());}finally{response.close();}
    }
    @Test void closedExecutorFailsWithoutExecutingCurrentGuard(){var response=new KnowledgeFinalResponse();response.close();assertEquals(503,assertThrows(KbFailure.class,()->response.finish(phase(),()->fail("Must not execute closed service"),()->Map.of(),200)).status());}
}
