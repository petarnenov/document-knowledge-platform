package org.documentknowledge.documentmanagement.application.upload;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.documentknowledge.documentmanagement.application.DmFailure;

/** Per-COMPLETE revocable execution capability. A timeout never means a remote write rolled back. */
public final class CompleteBudget {
 public enum Kind { STORAGE, SCAN, INSPECT }
 private final Clock clock;private final LongSupplier nano;private final Instant started;private final long start,total,storageLimit;
 private long storageSpent;private boolean cancelled;private Phase active;private Thread owner;
 public CompleteBudget(Clock clock){this(clock,System::nanoTime,Duration.ofSeconds(240),Duration.ofSeconds(60));}
 // Java-only deterministic/short-duration mechanism seam. Runtime always uses the public fixed limits.
 CompleteBudget(Clock clock,LongSupplier nano,Duration total,Duration storage){this.clock=Objects.requireNonNull(clock);this.nano=Objects.requireNonNull(nano);started=clock.instant();start=nano.getAsLong();this.total=total.toNanos();storageLimit=storage.toNanos();}
 private long elapsed(long from,Instant at){return Math.max(Math.max(0,nano.getAsLong()-from),Math.max(0,Duration.between(at,clock.instant()).toNanos()));}
 private synchronized long remaining(){long value=total-elapsed(start,started);if(active!=null)value=Math.min(value,active.left());return value;}
 public synchronized void check(){if(cancelled||remaining()<=0)throw unavailable();}
 public synchronized Phase phase(Kind kind){check();if(active!=null)throw new IllegalStateException("Overlapping complete phase");active=new Phase(kind,kind==Kind.STORAGE?storageLimit-storageSpent:Duration.ofSeconds(kind==Kind.SCAN?120:60).toNanos());check();notifyAll();return active;}
 /** The worker retains admission/resources until its finally block actually runs, even after caller timeout. */
 public <T>T execute(Callable<T> action){
  var result=new CompletableFuture<T>();
  synchronized(this){check();if(owner!=null)throw new IllegalStateException("Budget already executing");owner=Thread.ofVirtual().unstarted(()->{try{result.complete(action.call());}catch(Throwable failure){result.completeExceptionally(failure);}finally{synchronized(this){notifyAll();}}});owner.start();}
  try {
   synchronized(this){while(!result.isDone()){check();long left=remaining();TimeUnit.NANOSECONDS.timedWait(this,left);}check();}
   return result.join();
  }catch(InterruptedException interrupted){cancel();Thread.currentThread().interrupt();throw unavailable();}
  catch(CompletionException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;if(failed.getCause() instanceof Error error)throw error;throw unavailable();}
  catch(RuntimeException failed){cancel();throw failed;}
 }
 public void cancel(){List<Runnable> hooks;Thread thread;synchronized(this){if(cancelled)return;cancelled=true;thread=owner;hooks=active==null?List.of():List.copyOf(active.hooks);notifyAll();}if(thread!=null)thread.interrupt();for(var hook:hooks)Thread.ofVirtual().start(()->{try{hook.run();}catch(RuntimeException ignored){}});}
 public final class Phase implements AutoCloseable {
  private final Kind kind;private final long from=nano.getAsLong(),limit;private final Instant at=clock.instant();private final Set<Registration> hooks=new HashSet<>();private boolean closed;
  private Phase(Kind kind,long limit){this.kind=kind;this.limit=limit;}
  private long left(){return limit-elapsed(from,at);}
  public void check(){synchronized(CompleteBudget.this){if(closed||active!=this)throw unavailable();CompleteBudget.this.check();}}
  public Duration remaining(){synchronized(CompleteBudget.this){check();return Duration.ofNanos(CompleteBudget.this.remaining());}}
  /** Hooks must abort only this invocation, never a singleton/shared client. */
  public Registration onCancel(Runnable hook){synchronized(CompleteBudget.this){check();var registration=new Registration(Objects.requireNonNull(hook));hooks.add(registration);return registration;}}
  public final class Registration implements AutoCloseable,Runnable {
   private final Runnable hook;private boolean enabled=true;private Registration(Runnable hook){this.hook=hook;}
   public void run(){synchronized(CompleteBudget.this){if(!enabled)return;enabled=false;hooks.remove(this);}hook.run();}
   public void close(){synchronized(CompleteBudget.this){enabled=false;hooks.remove(this);}}
  }
  public void close(){synchronized(CompleteBudget.this){if(closed)return;if(left()<=0)cancelled=true;closed=true;if(kind==Kind.STORAGE)storageSpent=Math.addExact(storageSpent,elapsed(from,at));if(active==this)active=null;for(var hook:List.copyOf(hooks))hook.close();CompleteBudget.this.notifyAll();}}
 }
 private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
