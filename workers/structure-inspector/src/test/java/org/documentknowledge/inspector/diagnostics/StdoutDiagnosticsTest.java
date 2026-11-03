package org.documentknowledge.inspector.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.documentknowledge.inspector.diagnostics.InspectorDiagnostics.*;

/** Explicit unit manifests/frames; original compiler/image provenance is accepted separately. */
class StdoutDiagnosticsTest {
    private static final String SOURCE="workers/structure-inspector/src/test/java/org/documentknowledge/inspector/diagnostics/StdoutDiagnosticsTest.java";
    private static final String SYMBOL=StdoutDiagnosticsTest.class.getName()+".actualFailure";
    static Map<String,Object> fixture() {
        var catalog=new LinkedHashMap<String,Object>();
        catalog.put("events",List.of("process.started","process.stopped","operation.started","operation.succeeded","operation.failed","operation.cancelled"));
        catalog.put("phases",Arrays.stream(Phase.values()).map(Enum::name).toList());
        catalog.put("errorCodes",Arrays.stream(InspectorDiagnostics.Error.values()).map(Enum::name).toList());
        catalog.put("exceptionClasses",List.of("java.lang.IllegalStateException","java.io.IOException"));
        catalog.put("frames",List.of(Map.of("symbol",SYMBOL,"source",SOURCE)));
        catalog.put("dependencies",List.of(Map.of("name","AWS_S3","version","unit-only","vendorBuildProvenance","UNKNOWN","imageDigest","sha256:"+"a".repeat(64))));
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("buildId",UUID.randomUUID().toString());manifest.put("component","structure-inspector");
        manifest.put("gitSha","a".repeat(40));manifest.put("sourceFingerprintAlgorithm","dkp-public-inputs-v1");manifest.put("dirty",true);manifest.put("compiledAt","2026-11-03T21:00:00Z");
        for(String name:List.of("sourceFingerprint","toolchainFingerprint","dependencyFingerprint","vocabularySha256"))manifest.put(name,"b".repeat(64));
        manifest.put("vocabulary",catalog);return manifest;
    }
    static byte[] bytes(Map<String,Object> fixture) throws IOException {return WorkerLogManifest.JSON.writeValueAsBytes(fixture);}
    static String sha(byte[] raw) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    static StdoutDiagnostics open(Map<String,Object> fixture,OutputStream output) throws Exception {byte[] raw=bytes(fixture);return StdoutDiagnostics.open(new ByteArrayInputStream(raw),sha(raw),UUID.randomUUID().toString(),UUID.randomUUID().toString(),output);}
    @SuppressWarnings("unchecked") static Map<String,Object> catalog(Map<String,Object> fixture) {return (Map<String,Object>)fixture.get("vocabulary");}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> records(ByteArrayOutputStream output) throws Exception {
        var result=new ArrayList<Map<String,Object>>();
        for(String line:output.toString(StandardCharsets.UTF_8).split("\n")) {
            assertTrue(line.startsWith(StdoutDiagnostics.PREFIX));assertTrue(line.getBytes(StandardCharsets.UTF_8).length+1<=8192);
            result.add(WorkerLogManifest.JSON.readValue(line.substring(StdoutDiagnostics.PREFIX.length()),Map.class));
        }
        return result;
    }
    static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)fail("Bounded diagnostics condition expired");Thread.sleep(2);}
    }
    private static IllegalStateException actualFailure() {return new IllegalStateException("PRIVATE_CONTENT_CANARY",new IOException("PRIVATE_AUTHORITY_CANARY"));}

    @Test void actualFailureIsSanitizedAndOriginalIdentityTraceAndSingleTerminalSurviveOutput() throws Exception {
        var fixture=fixture();var output=new ByteArrayOutputStream();var context=new Context(UUID.randomUUID(),"a".repeat(32),"b".repeat(16));
        try(var adapter=open(fixture,output)) {
            var observation=adapter.started(context,Phase.OPERATION);observation.failure(actualFailure());
            observation.failure(new UnsupportedOperationException("must not replace first original"));
            observation.completed(Outcome.FAILED,new Facts(422,11L,17,true,InspectorDiagnostics.Error.OPERATION_FAILED));
            observation.completed(Outcome.SUCCEEDED,Facts.EMPTY);observation.failure(actualFailure());
            until(()->adapter.status().recordsWritten()==2);
            var rows=records(output);assertEquals(2,rows.size());
            var row=rows.getLast();assertEquals(fixture.get("buildId"),row.get("buildId"));assertNotEquals(row.get("buildId"),row.get("runId"));
            assertEquals(sha(bytes(fixture)),row.get("manifestSha256"));assertNotEquals(((Map<?,?>)rows.getFirst().get("event")).get("eventId"),((Map<?,?>)row.get("event")).get("eventId"));
            var event=(Map<?,?>)row.get("event");assertEquals(context.correlationId().toString(),event.get("correlationId"));assertEquals(context.traceId(),event.get("traceId"));assertEquals(context.spanId(),event.get("spanId"));assertEquals("operation.failed",event.get("eventName"));assertEquals("ERROR",event.get("severity"));
            var diagnostic=(Map<?,?>)event.get("diagnostics");var failure=(Map<?,?>)diagnostic.get("failure");
            assertEquals("java.lang.IllegalStateException",failure.get("exceptionClass"));assertEquals(List.of("java.io.IOException"),failure.get("causeClasses"));
            assertTrue(((List<?>)failure.get("stackFrames")).stream().anyMatch(frame->SOURCE.equals(((Map<?,?>)frame).get("source")) && ((Number)((Map<?,?>)frame).get("line")).intValue()>0));
            assertEquals(422,diagnostic.get("status"));assertEquals(11,diagnostic.get("remainingDeadlineMs"));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("CANARY"));assertFalse(output.toString(StandardCharsets.UTF_8).contains("must not replace"));
            assertTrue(WorkerLogManifest.JSON.writeValueAsBytes(event).length<=6144);
        }
    }
    @Test void lifecycleUnknownCancellationAndNullTraceUseTruthfulExistingEvents() throws Exception {
        var output=new ByteArrayOutputStream();var adapter=open(fixture(),new PrintStream(output));var context=Context.fresh();
        try {
            adapter.started(context,Phase.STARTUP).completed(Outcome.SUCCEEDED,Facts.EMPTY);
            adapter.started(context,Phase.SHUTDOWN).completed(Outcome.SUCCEEDED,Facts.EMPTY);
            adapter.started(context,Phase.OPERATION).completed(Outcome.UNKNOWN,Facts.EMPTY);
            adapter.started(context,Phase.DEADLINE).completed(Outcome.CANCELLED,Facts.EMPTY);
            until(()->adapter.status().recordsWritten()==8);var rows=records(output);
            assertEquals("process.started",((Map<?,?>)rows.getFirst().get("event")).get("eventName"));
            assertEquals("process.stopped",((Map<?,?>)rows.get(3).get("event")).get("eventName"));
            var unknown=(Map<?,?>)rows.get(5).get("event");assertEquals("operation.failed",unknown.get("eventName"));assertEquals("UNKNOWN",unknown.get("outcome"));assertEquals("DEBUG",unknown.get("severity"));assertFalse(unknown.containsKey("traceId"));
            assertEquals("operation.cancelled",((Map<?,?>)rows.getLast().get("event")).get("eventName"));
            adapter.rejected();assertEquals(1,adapter.status().rejected());
        }finally{adapter.close();assertTrue(adapter.awaitClosed(Duration.ofSeconds(1)));}
        assertTrue(adapter.awaitClosed(Duration.ZERO));assertThrows(IllegalArgumentException.class,()->adapter.awaitClosed(Duration.ofSeconds(-1)));assertThrows(IllegalArgumentException.class,()->adapter.awaitClosed(Duration.ofSeconds(2)));
    }
    @Test void boundedQueueAndNonflushingCloseNeverWaitForBlockedOutput() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var output=new OutputStream(){public void write(int value){entered.countDown();while(true)try{release.await();return;}catch(InterruptedException ignored){}}};
        var adapter=open(fixture(),output);var executor=Executors.newSingleThreadExecutor();
        try {
            adapter.started(Context.fresh(),Phase.REQUEST);assertTrue(entered.await(1,TimeUnit.SECONDS));
            executor.submit(()->{for(int index=0;index<160;index++)adapter.started(Context.fresh(),Phase.REQUEST);}).get(3,TimeUnit.SECONDS);
            assertEquals(128,adapter.status().queued());assertTrue(adapter.status().dropped()>0);
            executor.submit(adapter::close).get(1,TimeUnit.SECONDS);assertTrue(adapter.status().writerAlive());assertTrue(adapter.status().inFlight());assertFalse(adapter.awaitClosed(Duration.ZERO));assertEquals(0,adapter.status().queued());
            long before=adapter.status().dropped();adapter.started(Context.fresh(),Phase.REQUEST);assertTrue(adapter.status().dropped()>before);
        }finally{release.countDown();adapter.close();assertTrue(adapter.awaitClosed(Duration.ofSeconds(1)));executor.shutdownNow();}
    }
    @Test void concurrentCallbacksKeepCumulativeLossMonotonicInActualOutputOrder() throws Exception {
        var output=new ByteArrayOutputStream();
        try(var adapter=open(fixture(),output);var executor=Executors.newFixedThreadPool(8)) {
            var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<?>>();
            for(int thread=0;thread<8;thread++)jobs.add(executor.submit(()->{
                try{gate.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                for(int index=0;index<300;index++){adapter.rejected();adapter.started(Context.fresh(),Phase.REQUEST);}
            }));
            gate.countDown();for(var job:jobs)job.get(10,TimeUnit.SECONDS);
            long expected=2400-adapter.status().dropped();until(()->adapter.status().recordsWritten()==expected);assertTrue(expected>0);assertEquals(2400,adapter.status().rejected());
            var prior=new HashMap<String,Long>();for(String key:List.of("dropped","rejected","writerFailures"))prior.put(key,0L);
            var rows=records(output);assertEquals(expected,rows.size());
            for(var row:rows){var loss=(Map<?,?>)row.get("loss");for(String key:prior.keySet()){long value=((Number)loss.get(key)).longValue();assertTrue(value>=prior.get(key),"cumulative "+key+" must not decrease in stdout order");prior.put(key,value);}}
        }
    }
    @Test void brokenRawAndPrintStreamOutputsExposeUnknownWriteAndLossWithoutRawErrors() throws Exception {
        for(boolean print:new boolean[]{false,true}) {
            OutputStream broken=new OutputStream(){public void write(int value) throws IOException{throw new IOException("PRIVATE_WRITE_SECRET");}};
            var adapter=open(fixture(),print?new PrintStream(broken):broken);
            adapter.started(Context.fresh(),Phase.REQUEST);until(()->adapter.status().writerFailures()==1);
            assertTrue(adapter.awaitClosed(Duration.ofSeconds(1)));assertTrue(adapter.status().closed());assertTrue(adapter.status().writeUncertain());assertEquals(0,adapter.status().recordsWritten());assertFalse(adapter.status().toString().contains("SECRET"));adapter.close();
        }
    }
    @Test void captureOmissionCyclesDeepCausesAndTruncationAreBounded() throws Exception {
        byte[] raw=bytes(fixture());var manifest=WorkerLogManifest.read(new ByteArrayInputStream(raw),sha(raw));
        class PrivateFailure extends IllegalStateException {PrivateFailure(){super("PRIVATE_MESSAGE",new IOException("PRIVATE_CAUSE"));}}
        var failure=new PrivateFailure();failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(StdoutDiagnosticsTest.class.getName(),"actualFailure","wrong.java",1),new StackTraceElement("unknown.Class","secret",null,1),new StackTraceElement(StdoutDiagnosticsTest.class.getName(),"actualFailure",null,-1),new StackTraceElement(StdoutDiagnosticsTest.class.getName(),"actualFailure","native.java",-2)});
        var captured=WorkerExceptionCapture.capture(failure,manifest);assertFalse(captured.failure().containsKey("exceptionClass"));assertEquals(3L,captured.counts().get("unregisteredSymbols"));assertEquals(2,((List<?>)captured.failure().get("stackFrames")).size());
        var cyclic=new IllegalStateException(){@Override public Throwable getCause(){return this;}};cyclic.setStackTrace(new StackTraceElement[0]);assertEquals(1L,WorkerExceptionCapture.capture(cyclic,manifest).counts().get("truncatedFields"));
        Throwable chain=null;for(int index=0;index<8;index++)chain=new IOException("PRIVATE_CHAIN",chain);
        chain.setStackTrace(new StackTraceElement[0]);var deep=WorkerExceptionCapture.capture(chain,manifest);assertEquals(4,((List<?>)deep.failure().get("causeClasses")).size());assertEquals(1L,deep.counts().get("truncatedFields"));
        StackTraceElement[] frames=new StackTraceElement[40];Arrays.fill(frames,new StackTraceElement(StdoutDiagnosticsTest.class.getName(),"actualFailure","StdoutDiagnosticsTest.java",-1));failure.setStackTrace(frames);
        assertEquals(8L,WorkerExceptionCapture.capture(failure,manifest).counts().get("truncatedFields"));
        assertThrows(NullPointerException.class,()->WorkerExceptionCapture.capture(null,manifest));
    }
    @Test void largeRegisteredStackIsTrimmedToRecordBudgetWithVisibleTruncation() throws Exception {
        var fixture=fixture();String type="x".repeat(200),source="a".repeat(240)+"/Long.java";
        catalog(fixture).put("frames",List.of(Map.of("symbol",type+".method","source",source)));
        var failure=actualFailure();var frames=new StackTraceElement[40];Arrays.fill(frames,new StackTraceElement(type,"method","Long.java",1));failure.setStackTrace(frames);
        var output=new ByteArrayOutputStream();try(var adapter=open(fixture,output)) {
            var observation=adapter.started(Context.fresh(),Phase.OPERATION);observation.failure(failure);observation.completed(Outcome.FAILED,Facts.EMPTY);
            until(()->adapter.status().recordsWritten()==2);var event=(Map<?,?>)records(output).getLast().get("event");var diagnostics=(Map<?,?>)event.get("diagnostics");
            assertTrue(((Number)((Map<?,?>)diagnostics.get("counts")).get("truncatedFields")).intValue()>8);assertTrue(WorkerLogManifest.JSON.writeValueAsBytes(event).length<=6144);
        }
    }
    @Test void malformedOriginalManifestsAndExecutionBindingsFailClosedWithFixedMessages() throws Exception {
        var fixture=fixture();byte[] raw=bytes(fixture());
        for(byte[] invalid:new byte[][]{new byte[0],new byte[]{(byte)255},new byte[1048577],"{} {}".getBytes(StandardCharsets.UTF_8),"{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8)}) {
            var failure=assertThrows(IOException.class,()->StdoutDiagnostics.open(new ByteArrayInputStream(invalid),sha(invalid),UUID.randomUUID().toString(),UUID.randomUUID().toString(),new ByteArrayOutputStream()));assertEquals("INSPECTOR_LOG_CONFIGURATION_INVALID",failure.getMessage());assertNull(failure.getCause());
        }
        for(String digest:new String[]{"bad","a".repeat(64)})assertThrows(IOException.class,()->StdoutDiagnostics.open(new ByteArrayInputStream(raw),digest,UUID.randomUUID().toString(),UUID.randomUUID().toString(),new ByteArrayOutputStream()));
        for(String run:new String[]{"bad","00000000-0000-0000-0000-000000000000",(String)fixture.get("buildId")})assertThrows(IOException.class,()->StdoutDiagnostics.open(new ByteArrayInputStream(bytes(fixture)),sha(bytes(fixture)),run,UUID.randomUUID().toString(),new ByteArrayOutputStream()));
        assertThrows(IOException.class,()->StdoutDiagnostics.open(new ByteArrayInputStream(raw),sha(raw),UUID.randomUUID().toString(),"bad",new ByteArrayOutputStream()));
        assertThrows(IOException.class,()->StdoutDiagnostics.open(new ByteArrayInputStream(raw),sha(raw),UUID.randomUUID().toString(),UUID.randomUUID().toString(),null));
        assertThrows(IOException.class,()->StdoutDiagnostics.open(null,sha(raw),UUID.randomUUID().toString(),UUID.randomUUID().toString(),new ByteArrayOutputStream()));
    }
    @Test void dependencyVersionsUseTheOriginalCatalogCodePointLimit() throws Exception {
        String supplementary=new String(Character.toChars(0x1f642));
        for(int length:new int[]{65,128}) {
            var fixture=fixture();catalog(fixture).put("dependencies",List.of(Map.of("name","java","version",supplementary.repeat(length),"vendorBuildProvenance","UNKNOWN")));
            byte[] raw=bytes(fixture);assertNotNull(WorkerLogManifest.read(new ByteArrayInputStream(raw),sha(raw)));
        }
        var fixture=fixture();catalog(fixture).put("dependencies",List.of(Map.of("name","java","version",supplementary.repeat(129),"vendorBuildProvenance","UNKNOWN")));
        byte[] raw=bytes(fixture);assertThrows(IOException.class,()->WorkerLogManifest.read(new ByteArrayInputStream(raw),sha(raw)));
    }
    @Test void invalidCatalogShapesAndSymbolsCannotPopulateSanitizer() throws Exception {
        List<Consumer<Map<String,Object>>> corrupt=List.of(m->m.put("component","foreign"),m->m.put("schemaVersion",true),m->m.put("dirty","bad"),m->m.put("gitSha","bad"),m->m.put("compiledAt","bad"),m->m.put("extra","PRIVATE_SECRET"),m->m.put("vocabulary",false),m->catalog(m).put("phases",List.of()),m->catalog(m).put("errorCodes",List.of()),m->catalog(m).put("events",List.of("unknown.event")),m->catalog(m).put("events",List.of()),m->catalog(m).put("phases",List.of("bad phase")),m->catalog(m).put("exceptionClasses",List.of("duplicate","duplicate")),m->catalog(m).put("exceptionClasses",List.of(true)),m->catalog(m).put("frames",List.of(Map.of("symbol","x","source","../private"))),m->catalog(m).put("frames",List.of(Map.of("symbol","x","source","/private"))),m->catalog(m).put("frames",List.of(Map.of("symbol","x","source","a//b"))),m->catalog(m).put("frames",List.of(Map.of("symbol","x","source","a/"))),m->catalog(m).put("frames",List.of(Map.of("symbol","x","source","a"),Map.of("symbol","x","source","b"))),m->catalog(m).put("dependencies",List.of(Map.of("name","x","version","1","vendorBuildProvenance","FORGED"))),m->catalog(m).put("dependencies",List.of(false)),m->catalog(m).put("dependencies",false),m->catalog(m).put("phases",Collections.nCopies(257,"STARTUP")));
        for(var change:corrupt) {var fixture=fixture();change.accept(fixture);byte[] raw=bytes(fixture);assertThrows(IOException.class,()->WorkerLogManifest.read(new ByteArrayInputStream(raw),sha(raw)));}
    }
}
