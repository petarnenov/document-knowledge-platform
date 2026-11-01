package org.documentknowledge.knowledgebase.adapters.inbound;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBodyReaderTest {
    Deadline budget(){return new Deadline(System::nanoTime,Duration.ofSeconds(2));}
    @Test void boundedReadClosesInputAndNeverAllocatesUntrustedDeclaredSize(){
        try(var reader=new ClosingReader()){
            AtomicBoolean closed=new AtomicBoolean();var input=new ByteArrayInputStream(new byte[100000]){public void close(){closed.set(true);}};
            assertEquals(16385,reader.value.read(input,budget()).length);assertTrue(closed.get());
        }
    }
    @Test void stalledReadCannotRenewDeadlineOrRunAnyLaterRequestOperation()throws Exception{
        try(var reader=new ClosingReader()){
            var entered=new CountDownLatch(1);var finished=new CountDownLatch(1);var closed=new CountDownLatch(1);
            InputStream stalled=new InputStream(){public int read()throws IOException{entered.countDown();try{new CountDownLatch(1).await();return -1;}catch(InterruptedException cancelled){throw new IOException(cancelled);}finally{finished.countDown();}}public void close(){closed.countDown();}};
            long started=System.nanoTime();var deadline=new Deadline(System::nanoTime,Duration.ofMillis(80));
            assertEquals(503,assertThrows(KbFailure.class,()->reader.value.read(stalled,deadline)).status());assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(finished.await(1,TimeUnit.SECONDS));assertThrows(KbFailure.class,deadline::check);assertTrue(System.nanoTime()-started<Duration.ofSeconds(1).toNanos());
            assertTrue(closed.await(1,TimeUnit.SECONDS));
        }
    }
    @Test void interruptedCallerRetainsInterruptAndCancelsRead(){
        try(var reader=new ClosingReader()){
            Thread.currentThread().interrupt();try{assertEquals(503,assertThrows(KbFailure.class,()->reader.value.read(new ByteArrayInputStream(new byte[1]),budget())).status());assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
        }
    }
    @Test void malformedIoAndOversizeFrameworkCausesRemainFinite(){
        try(var reader=new ClosingReader()){
            InputStream malformed=new InputStream(){public int read()throws IOException{throw new IOException("private input data");}};
            var failure=assertThrows(KbFailure.class,()->reader.value.read(malformed,budget()));assertEquals("BAD_REQUEST",failure.code());
            reader.value.close();assertEquals(503,assertThrows(KbFailure.class,()->reader.value.read(new ByteArrayInputStream(new byte[1]),budget())).status());
        }
    }
    @Test void frameworkOversizeIs413WhetherRuntimeOrWrappedIo(){
        try(var reader=new ClosingReader()){
            for(boolean wrapped:new boolean[]{false,true}){
                InputStream input=new InputStream(){public int read()throws IOException{var exceeded=new io.micronaut.http.exceptions.ContentLengthExceededException(16384,16385);if(wrapped)throw new IOException(exceeded);throw exceeded;}};
                var failure=assertThrows(KbFailure.class,()->reader.value.read(input,budget()));assertEquals(413,failure.status());assertEquals("PAYLOAD_TOO_LARGE",failure.code());
            }
        }
    }
    @Test void interruptDuringAwaitCancelsTheOwnedTransportRead()throws Exception{
        try(var reader=new ClosingReader()){
            var entered=new CountDownLatch(1);var done=new CountDownLatch(1);var result=new java.util.concurrent.atomic.AtomicReference<KbFailure>();var interrupt=new AtomicBoolean();
            InputStream blocked=new InputStream(){public int read()throws IOException{entered.countDown();try{new CountDownLatch(1).await();return -1;}catch(InterruptedException cancelled){throw new IOException(cancelled);}}};
            var caller=Thread.ofPlatform().start(()->{try{reader.value.read(blocked,budget());}catch(KbFailure expected){result.set(expected);interrupt.set(Thread.currentThread().isInterrupted());}finally{done.countDown();}});
            assertTrue(entered.await(1,TimeUnit.SECONDS));caller.interrupt();assertTrue(done.await(1,TimeUnit.SECONDS));assertEquals(503,result.get().status());assertTrue(interrupt.get());
        }
    }
    static class ClosingReader implements AutoCloseable{final KnowledgeBodyReader value=new KnowledgeBodyReader();public void close(){value.close();}}
}
