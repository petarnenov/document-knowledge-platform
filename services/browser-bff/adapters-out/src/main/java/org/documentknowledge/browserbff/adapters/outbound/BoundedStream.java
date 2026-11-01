package org.documentknowledge.browserbff.adapters.outbound;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Streaming transport deadline includes blocked reads; no whole-file buffer and no retryable supplier. */
final class BoundedStream extends FilterInputStream {
 private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"dkp-bff-stream-deadlines");t.setDaemon(true);return t;});
 private final long expected;private final Runnable firstByte;private final AtomicBoolean closed=new AtomicBoolean();private boolean started;private volatile boolean timedOut;private volatile org.documentknowledge.browserbff.application.BffFailure rejection;private long count;
 private final ScheduledFuture<?> total;private ScheduledFuture<?> idle;
 BoundedStream(InputStream source,long expected,Runnable firstByte){this(source,expected,firstByte,300_000,30_000);}
 // Java-only short protocol-test deadlines. Runtime always uses300s total/30s idle.
 BoundedStream(InputStream source,long expected,Runnable firstByte,long totalMillis,long idleMillis){super(source);this.expected=expected;this.firstByte=firstByte;total=TIMER.schedule(this::timeout,totalMillis,TimeUnit.MILLISECONDS);idle=TIMER.schedule(this::timeout,idleMillis,TimeUnit.MILLISECONDS);this.idleMillis=idleMillis;}
 private final long idleMillis;
 private void timeout(){timedOut=true;try{close();}catch(IOException ignored){}}
 @Override public int read()throws IOException{byte[] bytes=new byte[1];int n=read(bytes,0,1);return n<0?-1:Byte.toUnsignedInt(bytes[0]);}
 @Override public int read(byte[] bytes,int offset,int length)throws IOException{
  if(length==0)return 0;if(timedOut)throw new IOException("Stream deadline");if(closed.get())return -1;
  if(!started){started=true;try{firstByte.run();}catch(RuntimeException denied){if(denied instanceof org.documentknowledge.browserbff.application.BffFailure failure)rejection=failure;close();throw denied;}}
  int n=in.read(bytes,offset,Math.min(length,65536));if(timedOut)throw new IOException("Stream deadline");
  if(n<0){if(expected>=0&&count!=expected){rejection=new org.documentknowledge.browserbff.application.BffFailure(422,"SIZE_MISMATCH");close();throw new IOException("Truncated stream");}close();return -1;}
  count+=n;if(count>100_000_000||expected>=0&&count>expected){rejection=new org.documentknowledge.browserbff.application.BffFailure(count>100_000_000?413:422,count>100_000_000?"PAYLOAD_TOO_LARGE":"SIZE_MISMATCH");close();throw new IOException("Stream size limit");}
  synchronized(this){idle.cancel(false);if(!closed.get())idle=TIMER.schedule(this::timeout,idleMillis,TimeUnit.MILLISECONDS);}return n;
 }
 org.documentknowledge.browserbff.application.BffFailure rejection(){return rejection;}
 @Override public void close()throws IOException{if(closed.compareAndSet(false,true)){total.cancel(false);synchronized(this){idle.cancel(false);}super.close();}}
}
