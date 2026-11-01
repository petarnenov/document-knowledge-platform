package org.documentknowledge.browserbff.application;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
/** One owner for session validation, body, KB transport and final serialization. */
public final class KnowledgeDeadline {
    private final long until=System.nanoTime()+9_000_000_000L;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final AtomicReference<Future<?>> pending=new AtomicReference<>();
    public long remainingNanos(){check();return until-System.nanoTime();}
    public void check(){if(cancelled.get()||System.nanoTime()>=until)throw new BffFailure(503,"UNAVAILABLE");}
    public void own(Future<?> future){pending.set(future);if(cancelled.get()||System.nanoTime()>=until){future.cancel(true);check();}check();}
    public void cancel(){cancelled.set(true);Future<?> future=pending.get();if(future!=null)future.cancel(true);}
}
