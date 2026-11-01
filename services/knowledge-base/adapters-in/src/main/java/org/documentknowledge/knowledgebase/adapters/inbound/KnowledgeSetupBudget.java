package org.documentknowledge.knowledgebase.adapters.inbound;

import java.util.concurrent.*;
import java.util.function.Supplier;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;

/** Cumulative identity/current-authority time, excluding body, SQL and remote business work. */
public final class KnowledgeSetupBudget implements AutoCloseable {
    public static final String ATTRIBUTE="dkp.kb.setup-budget";
    private static final ThreadLocal<KnowledgeSetupBudget> CURRENT=new ThreadLocal<>();
    private final ScheduledExecutorService timer;private final Deadline whole;
    private final CompletableFuture<Void> expired=new CompletableFuture<>();
    private long remaining=2_000_000_000L,started,generation;private int depth;private Thread worker;
    private ScheduledFuture<?> alarm;private boolean running,closed,failed;
    public KnowledgeSetupBudget(ScheduledExecutorService timer,Deadline whole){this.timer=timer;this.whole=whole;start(null);}
    public CompletableFuture<Void> expiration(){return expired;}
    public static KnowledgeSetupBudget current(){return CURRENT.get();}
    public synchronized void identityComplete(){stop();check();}
    private synchronized void check(){whole.check();if(closed||failed||remaining-(running?System.nanoTime()-started:0)<=0)throw KbFailure.unavailable();}
    private synchronized void start(Thread target){check();if(running)throw KbFailure.unavailable();running=true;started=System.nanoTime();worker=target;long attempt=++generation;alarm=timer.schedule(()->expire(attempt),Math.min(remaining,whole.remainingNanos()),TimeUnit.NANOSECONDS);}
    private synchronized void stop(){if(!running)return;remaining-=System.nanoTime()-started;running=false;worker=null;if(alarm!=null)alarm.cancel(false);generation++;if(remaining<=0)fail();}
    private synchronized void expire(long attempt){if(!running||attempt!=generation)return;fail();}
    private void fail(){failed=true;whole.cancel();Thread target=worker;if(target!=null)target.interrupt();// Complete outside this monitor: transport disposal also closes this meter.
        timer.execute(()->expired.complete(null));}
    public <T>T call(Supplier<T> action){
        KnowledgeSetupBudget previous=CURRENT.get();boolean outer;
        synchronized(this){check();outer=depth==0;if(outer)start(Thread.currentThread());else if(worker!=Thread.currentThread())throw KbFailure.unavailable();depth++;}
        CURRENT.set(this);
        try{T result=action.get();synchronized(this){check();}return result;}
        finally{CURRENT.set(previous);synchronized(this){if(--depth==0)stop();}}
    }
    public void run(Runnable action){call(()->{action.run();return null;});}
    @Override public synchronized void close(){closed=true;running=false;worker=null;if(alarm!=null)alarm.cancel(false);}
}
