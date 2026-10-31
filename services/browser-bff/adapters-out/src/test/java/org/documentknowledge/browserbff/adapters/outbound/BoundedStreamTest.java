package org.documentknowledge.browserbff.adapters.outbound;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.browserbff.application.BffFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BoundedStreamTest {
 @Test void streamsInBoundedChunksChecksExactLengthAndAuthorizesOnlyOnce()throws Exception{
  var authorization=new AtomicInteger();try(var stream=new BoundedStream(new ByteArrayInputStream(new byte[]{1,2,3}),3,authorization::incrementAndGet)){assertEquals(0,stream.read(new byte[1],0,0));assertEquals(1,stream.read());assertArrayEquals(new byte[]{2,3},stream.readAllBytes());assertEquals(-1,stream.read());assertEquals(1,authorization.get());assertNull(stream.rejection());}
  try(var shortBody=new BoundedStream(new ByteArrayInputStream(new byte[1]),2,()->{})){assertThrows(IOException.class,shortBody::readAllBytes);assertEquals(422,shortBody.rejection().status());}
  try(var longBody=new BoundedStream(new ByteArrayInputStream(new byte[2]),1,()->{})){assertThrows(IOException.class,longBody::readAllBytes);assertEquals("SIZE_MISMATCH",longBody.rejection().code());}
 }
 @Test void unknownLengthCannotExceed100MBBoundedTransport()throws Exception{
  InputStream source=new InputStream(){long remaining=100_000_001;public int read(){throw new AssertionError("Useboundedbulkread");}public int read(byte[] b,int offset,int count){if(remaining==0)return -1;int n=(int)Math.min(remaining,count);remaining-=n;return n;}};
  try(var stream=new BoundedStream(source,-1,()->{})){byte[] buffer=new byte[65536];assertThrows(IOException.class,()->{while(stream.read(buffer)!=-1){}});assertEquals(413,stream.rejection().status());assertEquals("PAYLOAD_TOO_LARGE",stream.rejection().code());}
 }
 @Test void firstByteRevocationClosesBeforeAnyReadAndKeepsTypedFailure()throws Exception{
  var closed=new AtomicInteger();InputStream source=new InputStream(){public int read(){throw new AssertionError("No bytes afterrevocation");}public void close(){closed.incrementAndGet();}};
  try(var stream=new BoundedStream(source,1,()->{throw new BffFailure(401,"AUTHENTICATION_REQUIRED");})){assertThrows(BffFailure.class,stream::read);assertEquals(401,stream.rejection().status());assertEquals(1,closed.get());}
 }
 @Test void idleAndTotalDeadlinesCloseBlockedReaders()throws Exception{
  for(boolean idle:java.util.List.of(true,false)){
   var entered=new CountDownLatch(1);InputStream source=new InputStream(){boolean closed;public synchronized int read()throws IOException{entered.countDown();while(!closed)try{wait();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException(failure);}return -1;}public synchronized int read(byte[] b,int o,int l)throws IOException{return read();}public synchronized void close(){closed=true;notifyAll();}};
   try(var stream=new BoundedStream(source,1,()->{},idle?1000:40,idle?40:1000);var executor=Executors.newVirtualThreadPerTaskExecutor()){var read=executor.submit(()->assertThrows(IOException.class,stream::read));assertTrue(entered.await(1,TimeUnit.SECONDS));read.get(2,TimeUnit.SECONDS);assertThrows(IOException.class,stream::read);}
  }
 }
}
