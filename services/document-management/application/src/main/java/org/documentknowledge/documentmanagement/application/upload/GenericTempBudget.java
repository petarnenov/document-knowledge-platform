package org.documentknowledge.documentmanagement.application.upload;

import java.io.InputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.documentknowledge.documentmanagement.application.DmFailure;

/** One revocable TRANSFER/PREAPPEND owner. Cancellation never proves physical or remote settlement. */
public final class GenericTempBudget {
 private final GenericTempBudget parent;private final GenericTempPort.Role role;private final boolean idleEnabled;private final Clock clock;private final LongSupplier nano;private final long start,limit;private final Instant at;
 private final Set<Registration> hooks=new HashSet<>();private boolean cancelled,reading;private long readFrom;private Instant readAt;private Thread owner;
 public GenericTempBudget(Clock clock,GenericTempPort.Role role){this(clock,System::nanoTime,Duration.ofSeconds(role==GenericTempPort.Role.TRANSFER?300:60),role);}
 GenericTempBudget(Clock clock,LongSupplier nano,Duration limit){this(clock,nano,limit,GenericTempPort.Role.TRANSFER);}
 private GenericTempBudget(Clock clock,LongSupplier nano,Duration limit,GenericTempPort.Role role){this(clock,nano,limit,role,null);}
 private GenericTempBudget(Clock clock,LongSupplier nano,Duration limit,GenericTempPort.Role role,GenericTempBudget parent){this.parent=parent;this.role=Objects.requireNonNull(role);this.idleEnabled=role==GenericTempPort.Role.TRANSFER;this.clock=Objects.requireNonNull(clock);this.nano=Objects.requireNonNull(nano);this.limit=limit.toNanos();start=nano.getAsLong();at=clock.instant();}
 private long elapsed(long from,Instant instant){return Math.max(Math.max(0,nano.getAsLong()-from),Math.max(0,Duration.between(instant,clock.instant()).toNanos()));}
 private long left(){long value=limit-elapsed(start,at);if(reading&&idleEnabled)value=Math.min(value,Duration.ofSeconds(30).toNanos()-elapsed(readFrom,readAt));return parent==null?value:Math.min(value,parent.remaining().toNanos());}
 /** Fixed60s recovery scope; every check/remaining call also checks the original role owner. */
 public synchronized GenericTempBudget recovery(){check();return new GenericTempBudget(clock,nano,Duration.ofSeconds(60),GenericTempPort.Role.PREAPPEND,this);}
 public GenericTempPort.Role role(){return role;}
 public synchronized void check(){if(cancelled||left()<=0)throw unavailable();}
 public synchronized Duration remaining(){check();return Duration.ofNanos(left());}
 public synchronized Registration onCancel(Runnable hook){check();var registration=new Registration(Objects.requireNonNull(hook));hooks.add(registration);return registration;}
 public InputRead reading(InputStream input){synchronized(this){check();if(reading)throw unavailable();reading=true;readFrom=nano.getAsLong();readAt=clock.instant();notifyAll();return new InputRead(onCancel(()->{try{input.close();}catch(java.io.IOException ignored){}}));}}
 public final class InputRead implements AutoCloseable {
  private final Registration registration;private boolean closed;private InputRead(Registration registration){this.registration=registration;}
  public void progress(){synchronized(GenericTempBudget.this){check();if(closed)throw unavailable();readFrom=nano.getAsLong();readAt=clock.instant();GenericTempBudget.this.notifyAll();}}
  public void close(){synchronized(GenericTempBudget.this){if(!closed){closed=true;reading=false;registration.close();GenericTempBudget.this.notifyAll();}}}
 }
 public final class Registration implements AutoCloseable,Runnable {
  private final Runnable hook;private boolean enabled=true;private Registration(Runnable hook){this.hook=hook;}
  public void run(){synchronized(GenericTempBudget.this){if(!enabled)return;enabled=false;hooks.remove(this);}hook.run();}
  public void close(){synchronized(GenericTempBudget.this){enabled=false;hooks.remove(this);}}
 }
 public <T>T execute(Callable<T> action){
  var result=new CompletableFuture<T>();synchronized(this){check();if(owner!=null)throw unavailable();owner=Thread.ofVirtual().unstarted(()->{try{result.complete(action.call());}catch(Throwable failure){result.completeExceptionally(failure);}finally{synchronized(this){notifyAll();}}});owner.start();}
  try{synchronized(this){while(!result.isDone()){check();TimeUnit.NANOSECONDS.timedWait(this,left());}check();}return result.join();}
  catch(InterruptedException interrupted){cancel();Thread.currentThread().interrupt();throw unavailable();}
  catch(CompletionException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;if(failed.getCause() instanceof Error error)throw error;throw unavailable();}
  catch(RuntimeException failed){cancel();throw failed;}
 }
 public void cancel(){Thread thread;List<Registration> pending;synchronized(this){if(cancelled)return;cancelled=true;thread=owner;pending=List.copyOf(hooks);notifyAll();}if(thread!=null)thread.interrupt();for(var hook:pending)Thread.ofVirtual().start(()->{try{hook.run();}catch(RuntimeException ignored){}});}
 private static DmFailure unavailable(){return new DmFailure(503,"UNAVAILABLE");}
}
