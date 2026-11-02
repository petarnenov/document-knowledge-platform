package org.documentknowledge.browserbff.adapters.outbound;
import java.io.*;
import java.util.concurrent.*;
/** Streaming transport deadline includes blocked reads; no whole-file buffer and no retryable supplier. */
final class BoundedStream extends FilterInputStream {
 private static final ScheduledExecutorService TIMER=StreamDeadlineTimer.create();
 private final long expected;private final Runnable firstByte;private final StreamReadLifecycle ownership;private boolean started;private volatile boolean timedOut;private volatile org.documentknowledge.browserbff.application.BffFailure rejection;private long count;
 private final ScheduledFuture<?> total;private ScheduledFuture<?> idle;
 BoundedStream(InputStream source,long expected,Runnable firstByte){this(source,expected,firstByte,300_000,30_000);}
 // Java-only short protocol-test deadlines. Runtime always uses300s total/30s idle.
 BoundedStream(InputStream source,long expected,Runnable firstByte,long totalMillis,long idleMillis){this(source,expected,firstByte,totalMillis,idleMillis,()->{});}
 BoundedStream(InputStream source,long expected,Runnable firstByte,long totalMillis,long idleMillis,Runnable settled){this(source,expected,firstByte,totalMillis,idleMillis,settled,TIMER);}
 // Package-only scheduler seam for deterministic constructor-rejection tests; runtime uses TIMER.
 BoundedStream(InputStream source,long expected,Runnable firstByte,long totalMillis,long idleMillis,Runnable settled,ScheduledExecutorService timer){
  super(source);this.expected=expected;this.firstByte=firstByte;ownership=new StreamReadLifecycle(settled);this.idleMillis=idleMillis;
  ScheduledFuture<?> scheduledTotal=null;
  try{
   synchronized(this){
    try{total=timer.schedule(this::timeout,totalMillis,TimeUnit.MILLISECONDS);scheduledTotal=total;idle=timer.schedule(this::timeout,idleMillis,TimeUnit.MILLISECONDS);}
    catch(RuntimeException|Error failure){if(scheduledTotal!=null)scheduledTotal.cancel(false);ownership.beginClose();throw failure;}
   }
  }catch(RuntimeException|Error failure){
   boolean succeeded=false;
   try{in.close();succeeded=true;}
   catch(IOException|RuntimeException|Error closeFailure){if(closeFailure!=failure)failure.addSuppressed(closeFailure);}
   finally{ownership.finishClose(succeeded);}
   throw failure;
  }
 }

 private final long idleMillis;
 private void timeout(){synchronized(this){timedOut=true;}try{close();}catch(IOException ignored){}}
 @Override public int read()throws IOException{byte[] bytes=new byte[1];int n=read(bytes,0,1);return n<0?-1:Byte.toUnsignedInt(bytes[0]);}
 @Override public int read(byte[] bytes,int offset,int length)throws IOException{
  if(length==0)return 0;if(timedOut)throw new IOException("Stream deadline");if(!ownership.beginRead())return -1;
  try{
  if(!started){started=true;try{firstByte.run();}catch(RuntimeException denied){if(denied instanceof org.documentknowledge.browserbff.application.BffFailure failure)rejection=failure;close();throw denied;}}
  int n;
  try{n=in.read(bytes,offset,Math.min(length,65536));}
  catch(IOException|RuntimeException failure){
   if(totalLengthExceeded(failure)){
    rejection=new org.documentknowledge.browserbff.application.BffFailure(413,"PAYLOAD_TOO_LARGE");
    try{close();}catch(IOException closeFailure){failure.addSuppressed(closeFailure);}
   }
   throw failure;
  }
  if(timedOut)throw new IOException("Stream deadline");
  if(n<0){if(expected>=0&&count!=expected){rejection=new org.documentknowledge.browserbff.application.BffFailure(422,"SIZE_MISMATCH");close();throw new IOException("Truncated stream");}close();return -1;}
  count+=n;if(count>100_000_000||expected>=0&&count>expected){rejection=new org.documentknowledge.browserbff.application.BffFailure(count>100_000_000?413:422,count>100_000_000?"PAYLOAD_TOO_LARGE":"SIZE_MISMATCH");close();throw new IOException("Stream size limit");}
  synchronized(this){idle.cancel(false);if(!ownership.closing())idle=TIMER.schedule(this::timeout,idleMillis,TimeUnit.MILLISECONDS);}return n;
  }finally{ownership.endRead();}
 }
 private static boolean totalLengthExceeded(Throwable failure){
  // The framework's total-body exception is distinct from a queue overflow or dependency failure.
  // Inspect types only, never provider messages; bounded traversal also handles cyclic cause chains.
  for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause())
   if(failure instanceof io.micronaut.http.exceptions.ContentLengthExceededException)return true;
  return false;
 }
 org.documentknowledge.browserbff.application.BffFailure rejection(){return rejection;}
 StreamReadLifecycle.Snapshot ownershipSnapshot(){return ownership.snapshot();}
 @Override public void close()throws IOException{if(ownership.beginClose()){total.cancel(false);synchronized(this){if(idle!=null)idle.cancel(false);}boolean succeeded=false;try{super.close();succeeded=true;}finally{ownership.finishClose(succeeded);}}}
}
