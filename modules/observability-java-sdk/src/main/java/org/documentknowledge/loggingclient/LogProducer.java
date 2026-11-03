package org.documentknowledge.loggingclient;

import java.time.Duration;
import java.util.*;
import org.documentknowledge.observability.ExecutionIdentity;

/** One bounded in-memory producer; caller never waits for HTTP or disk. Durable spool follows separately. */
public final class LogProducer implements AutoCloseable {
    public static final int MAX_EVENTS=2048,MAX_BYTES=8*1024*1024;
    public enum Admission { ACCEPTED, DUPLICATE, REJECTED, FULL, CLOSED }
    public record Status(int pendingEvents,long pendingBytes,long unknownEvents,long committed,
                         long rejected,long dropped,long expired,boolean closed,boolean dispatcherAlive) {}
    private static final class Pending {
        final LogWire.Prepared event;
        boolean unknown;
        Pending(LogWire.Prepared event) {this.event=event;}
    }
    private final Object lock=new Object();
    private final ExecutionIdentity execution;
    private final LogVocabulary vocabulary;
    private final LogTransport transport;
    private final LinkedHashMap<UUID,Pending> pending=new LinkedHashMap<>();
    private final Thread dispatcher;
    private long bytes,committed,rejected,dropped,expired;
    private boolean closed;

    public LogProducer(ExecutionIdentity execution,LogVocabulary vocabulary,LogTransport transport) {
        this.execution=Objects.requireNonNull(execution);this.vocabulary=Objects.requireNonNull(vocabulary);
        this.transport=Objects.requireNonNull(transport);
        dispatcher=Thread.ofPlatform().daemon().name("dev-log-dispatch").unstarted(this::dispatch);
        dispatcher.start();
    }

    public Admission emit(LifecycleEvent event) {
        LogWire.Prepared prepared;
        try {prepared=LogWire.prepare(Objects.requireNonNull(event),execution,vocabulary);}
        catch(RuntimeException invalid) {synchronized(lock) {rejected++;}return Admission.REJECTED;}
        synchronized(lock) {
            if(closed) {dropped++;return Admission.CLOSED;}
            var prior=pending.get(prepared.id());
            if(prior!=null) {
                if(Arrays.equals(prior.event.bytes(),prepared.bytes()))return Admission.DUPLICATE;
                rejected++;return Admission.REJECTED;
            }
            if(pending.size()>=MAX_EVENTS || bytes>MAX_BYTES-prepared.bytes().length) {dropped++;return Admission.FULL;}
            pending.put(prepared.id(),new Pending(prepared));bytes+=prepared.bytes().length;
            lock.notifyAll();return Admission.ACCEPTED;
        }
    }

    public Status status() {
        synchronized(lock) {return new Status(pending.size(),bytes,pending.values().stream().filter(item->item.unknown).count(),
            committed,rejected,dropped,expired,closed,dispatcher.isAlive());}
    }

    private List<Pending> next() {
        synchronized(lock) {
            var result=new ArrayList<Pending>();int total=32;
            for(var item:pending.values()) {
                if(result.size()==LogWire.BATCH_EVENTS || total+item.event.bytes().length+1>LogWire.BATCH_BYTES)break;
                result.add(item);total+=item.event.bytes().length+1;
            }
            return result;
        }
    }

    private void dispatch() {
        try {
            while(true) {
                synchronized(lock) {
                    while(!closed && pending.isEmpty())lock.wait();
                    if(closed)break;
                }
                var selected=next();var events=selected.stream().map(item->item.event).toList();
                boolean progressed=false;
                try {
                    if(selected.stream().anyMatch(item->item.unknown)) {
                        var lookup=transport.post(LogTransport.Route.RECEIPTS,LogWire.lookup(events));
                        if(lookup.status()==200) {
                            var receipts=LogWire.receipts(lookup.body(),events,false);
                            synchronized(lock) {
                                for(var item:selected) {
                                    var state=receipts.get(item.event.id());
                                    if(state==LogWire.Receipt.COMMITTED || state==LogWire.Receipt.EXPIRED) {
                                        remove(item);if(state==LogWire.Receipt.COMMITTED)committed++;else expired++;
                                        progressed=true;
                                    }
                                }
                            }
                        }
                        selected=next();events=selected.stream().map(item->item.event).toList();
                    }
                    if(!events.isEmpty()) {
                        synchronized(lock) {if(closed)break;}
                        var reply=transport.post(LogTransport.Route.BATCHES,LogWire.batch(events));
                        if(reply.status()==200) {
                            LogWire.receipts(reply.body(),events,true);
                            synchronized(lock) {for(var item:selected) {remove(item);committed++;}progressed=true;}
                        } else if(reply.status()==400 || reply.status()==409 || reply.status()==413) {
                            synchronized(lock) {
                                for(var item:selected) {
                                    if(!item.unknown || reply.status()==409) {remove(item);rejected++;progressed=true;}
                                    else item.unknown=true;
                                }
                            }
                        } else markUnknown(selected);
                    }
                } catch(InterruptedException interrupted) {markUnknown(selected);Thread.currentThread().interrupt();break;}
                  catch(Exception unavailable) {markUnknown(selected);}
                synchronized(lock) {if(!closed && !progressed)lock.wait(1000);}
            }
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        finally {try {transport.close();}catch(RuntimeException ignored) { /* No recursive logging. */ }}
    }

    private void remove(Pending item) {if(pending.remove(item.event.id(),item))bytes-=item.event.bytes().length;}
    private void markUnknown(List<Pending> selected) {
        synchronized(lock) {for(var item:selected)if(pending.get(item.event.id())==item)item.unknown=true;}
    }

    /** Does not flush or label unconfirmed events lost/committed; status remains available. */
    @Override public void close() {synchronized(lock) {closed=true;lock.notifyAll();}dispatcher.interrupt();}
    public boolean awaitClosed(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        if(timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("INVALID_LOG_CLOSE_BUDGET");
        if(!timeout.isZero())dispatcher.join(timeout);
        return !dispatcher.isAlive();
    }
}
