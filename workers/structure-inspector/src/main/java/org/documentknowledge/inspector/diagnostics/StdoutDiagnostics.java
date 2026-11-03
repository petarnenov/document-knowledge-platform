package org.documentknowledge.inspector.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.documentknowledge.inspector.diagnostics.InspectorDiagnostics.*;

/** Replaceable local adapter. Business callbacks perform bounded capture/queue admission, never I/O. */
public final class StdoutDiagnostics implements InspectorDiagnostics,AutoCloseable {
    public static final String RESOURCE="/META-INF/dkp-logging/manifest.json";
    public static final String PREFIX="DKP-INSPECTOR-LOG/1 ";
    private static final long MAX=9_007_199_254_740_991L;
    private final WorkerLogManifest manifest;
    private final String runId,instanceId;
    private final OutputStream output;
    private final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(128);
    private final Object admission=new Object();
    private final AtomicBoolean closed=new AtomicBoolean(),inFlight=new AtomicBoolean(),writeUncertain=new AtomicBoolean();
    private final AtomicLong dropped=new AtomicLong(),rejected=new AtomicLong(),writerFailures=new AtomicLong(),written=new AtomicLong();
    private final Thread writer;
    public record Status(boolean closed,boolean writerAlive,int queued,boolean inFlight,boolean writeUncertain,
                         long dropped,long rejected,long writerFailures,long recordsWritten) { }

    private StdoutDiagnostics(WorkerLogManifest manifest,String runId,String instanceId,OutputStream output) {
        this.manifest=manifest;this.runId=WorkerLogManifest.id(runId);this.instanceId=WorkerLogManifest.id(instanceId);
        WorkerLogManifest.require(!manifest.buildId.equals(runId));this.output=Objects.requireNonNull(output);
        writer=Thread.ofPlatform().daemon().name("dkp-inspector-log-writer").start(this::drain);
    }
    public static StdoutDiagnostics open(InputStream embeddedManifest,String manifestSha256,String runId,String instanceId,OutputStream output) throws IOException {
        try{return new StdoutDiagnostics(WorkerLogManifest.read(embeddedManifest,manifestSha256),runId,instanceId,output);}
        catch(Exception invalid){throw new IOException("INSPECTOR_LOG_CONFIGURATION_INVALID");}
    }
    @Override public void rejected() {increment(rejected,1);}
    private static void increment(AtomicLong counter,long amount) {counter.updateAndGet(value->Math.min(MAX,value+amount));}
    @Override public Observation started(Context context,Phase phase) {
        Objects.requireNonNull(context);Objects.requireNonNull(phase);long started=System.nanoTime();
        emit(context,phase,phase==Phase.STARTUP?"process.started":"operation.started","STARTED",0,Facts.EMPTY,null);
        return new Observation() {
            private boolean terminal;
            private WorkerExceptionCapture.Captured captured;
            public synchronized void failure(Throwable failure) {
                if(terminal || captured!=null)return;
                try{captured=WorkerExceptionCapture.capture(failure,manifest);}catch(RuntimeException invalid){rejected();}
            }
            public synchronized void completed(Outcome outcome,Facts facts) {
                if(terminal)return;terminal=true;
                String event=phase==Phase.SHUTDOWN && outcome==Outcome.SUCCEEDED?"process.stopped":
                    (outcome==Outcome.FAILED || outcome==Outcome.UNKNOWN)?"operation.failed":outcome==Outcome.CANCELLED?"operation.cancelled":"operation.succeeded";
                emit(context,phase,event,Objects.requireNonNull(outcome).name(),Math.max(0,(System.nanoTime()-started)/1_000_000),Objects.requireNonNull(facts),captured);
            }
        };
    }
    private void emit(Context context,Phase phase,String name,String outcome,long duration,Facts facts,WorkerExceptionCapture.Captured captured) {
        try {
            var event=new LinkedHashMap<String,Object>();event.put("eventId",UUID.randomUUID().toString());event.put("timestamp",Instant.now().toString());
            event.put("severity",outcome.equals("FAILED")?"ERROR":"DEBUG");event.put("eventName",name);
            event.put("correlationId",context.correlationId().toString());event.put("phase",phase.name());event.put("outcome",outcome);
            event.put("durationMs",duration);event.put("attempt",0);event.put("count",facts.count());event.put("cleanupSettled",facts.cleanupSettled());
            if(context.traceId()!=null)event.put("traceId",context.traceId());if(context.spanId()!=null)event.put("spanId",context.spanId());
            var diagnostics=new HashMap<String,Object>();
            if(facts.status()!=null)diagnostics.put("status",facts.status());
            if(facts.remainingDeadlineMs()!=null)diagnostics.put("remainingDeadlineMs",facts.remainingDeadlineMs());
            if(facts.error()!=null)diagnostics.put("errorCode",facts.error().name());
            if(captured!=null){diagnostics.put("failure",new HashMap<>(captured.failure()));diagnostics.put("counts",new HashMap<>(captured.counts()));}
            if(!diagnostics.isEmpty())event.put("diagnostics",diagnostics);
            trim(event,diagnostics);byte[] payload=WorkerLogManifest.JSON.writeValueAsBytes(event);WorkerLogManifest.require(payload.length<=6144);
            var record=new LinkedHashMap<String,Object>();record.put("schemaVersion",1);record.put("buildId",manifest.buildId);record.put("runId",runId);
            record.put("instanceId",instanceId);record.put("manifestSha256",manifest.sha256);record.put("event",event);
            synchronized(admission) {
                // Snapshot counters in the same order as queue admission. No output I/O holds this lock.
                record.put("loss",Map.of("dropped",dropped.get(),"rejected",rejected.get(),"writerFailures",writerFailures.get()));
                byte[] bytes=(PREFIX+WorkerLogManifest.JSON.writeValueAsString(record)+"\n").getBytes(StandardCharsets.UTF_8);WorkerLogManifest.require(bytes.length<=8192);
                if(closed.get() || !queue.offer(bytes))increment(dropped,1);
            }
        }catch(IOException | RuntimeException invalid){rejected();}
    }
    @SuppressWarnings("unchecked")
    private static void trim(Map<String,Object> event,Map<String,Object> diagnostics) throws IOException {
        if(!(diagnostics.get("failure") instanceof Map<?,?>))return;
        var failure=(Map<String,Object>)diagnostics.get("failure");
        var frames=new ArrayList<>((List<Map<String,Object>>)failure.get("stackFrames"));failure.put("stackFrames",frames);
        var counts=(Map<String,Object>)diagnostics.get("counts");long truncated=((Number)counts.get("truncatedFields")).longValue();
        while(WorkerLogManifest.JSON.writeValueAsBytes(event).length>6144 && !frames.isEmpty()) {
            frames.removeLast();counts.put("truncatedFields",++truncated);
        }
    }
    private void drain() {
        try {
            while(!closed.get()) {
                byte[] bytes;
                try{bytes=queue.poll(100,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){continue;}
                if(bytes==null)continue;
                inFlight.set(true);
                try {
                    output.write(bytes);output.flush();
                    if(output instanceof PrintStream stream && stream.checkError())throw new IOException();
                    increment(written,1);
                }catch(IOException | RuntimeException unavailable) {
                    writeUncertain.set(true);increment(writerFailures,1);close();return;
                }finally{inFlight.set(false);}
            }
        }finally{closed.set(true);}
    }
    public Status status() {return new Status(closed.get(),writer.isAlive(),queue.size(),inFlight.get(),writeUncertain.get(),dropped.get(),rejected.get(),writerFailures.get(),written.get());}
    /** Bounded nonflushing close. A written record is never called COMMITTED here. */
    @Override public void close() {
        synchronized(admission){if(closed.compareAndSet(false,true)){increment(dropped,queue.size());queue.clear();}}
        writer.interrupt();
    }
    public boolean awaitClosed(Duration timeout) throws InterruptedException {
        if(timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(1))>0)throw new IllegalArgumentException("INVALID_LOG_WAIT");
        return timeout.isZero()?!writer.isAlive():writer.join(timeout);
    }
}
