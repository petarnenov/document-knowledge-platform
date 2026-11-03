package org.documentknowledge.loggingclient;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.documentknowledge.observability.*;
import tools.jackson.databind.json.JsonMapper;

/** Disposable isolated API proof only; never an application entrypoint or build attestation. */
public final class DiagnosticSdkAcceptanceMain {
    private static IOException failedRead() throws IOException {
        Path owned=Files.createTempDirectory("dkp-diagnostic-fixture-");
        try {
            try {Files.readAllBytes(owned.resolve("private-content-canary"));throw new AssertionError("Fixture path unexpectedly exists");}
            catch(IOException actual) {return new IOException("private-wrapper-canary",actual);}
        } finally {Files.delete(owned);}
    }
    public static void main(String[] args) {
        try {run(args);}catch(Exception failure) {System.err.println("{\"result\":\"FAIL\",\"code\":\"DIAGNOSTIC_ACCEPTANCE_FAILED\"}");System.exit(1);}
    }
    private static void run(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException();
        var json=JsonMapper.builder().build();byte[] raw=Files.readAllBytes(Path.of(args[0]));
        var manifest=json.readValue(raw,Map.class);var catalog=(Map<?,?>)manifest.get("vocabulary");
        var runtime=ApplicationLogRuntime.open("runtime-tools",new ByteArrayInputStream(raw),Path.of(args[1]));
        Map<?,?> expected;
        try {
            long started=System.nanoTime();var actual=failedRead();long durationMs=(System.nanoTime()-started)/1_000_000;
            var capture=runtime.capture(actual);
            if(!IOException.class.getName().equals(capture.failure().exceptionClass())
                    || !List.of(NoSuchFileException.class.getName()).equals(capture.failure().causeClasses())
                    || capture.failure().stackFrames().isEmpty())throw new IllegalStateException();
            var metadata=runtime.metadata(Severity.ERROR,EventCode.OPERATION_FAILED,UUID.randomUUID(),new TraceContext("a".repeat(32),"b".repeat(16)));
            var dependency=new LogDiagnosticCatalog.Dependency("jdk",System.getProperty("java.version"),null,LogDiagnosticCatalog.Provenance.UNKNOWN);
            var diagnostic=new LogDiagnostics(null,null,"RESOURCE_UNAVAILABLE",null,dependency,capture.failure(),
                null,capture.counts());
            var event=new LifecycleEvent(metadata,"READ",LifecycleEvent.Outcome.FAILED,durationMs,1,1,true,diagnostic);
            var vocabulary=new LogVocabulary(Set.of(EventCode.PROCESS_STARTED,EventCode.PROCESS_STOPPED,EventCode.OPERATION_FAILED),
                Set.of("STARTUP","SHUTDOWN","READ"),LogDiagnosticCatalog.fromManifest(catalog));
            expected=json.readValue(LogWire.prepare(event,runtime.execution(),vocabulary).bytes(),Map.class);
            if(runtime.emit(event)!=LogProducer.Admission.ACCEPTED)throw new IllegalStateException();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(runtime.status().committed()!=1 && System.nanoTime()<deadline)Thread.sleep(10);
            if(runtime.status().committed()!=1 || runtime.status().pendingEvents()!=0)throw new IllegalStateException();
        } finally {runtime.close();if(!runtime.awaitClosed(Duration.ofSeconds(2)))throw new IllegalStateException();}
        System.out.println(json.writeValueAsString(Map.of("result","PASS","applicationBuildProvenance","FIXTURE_ONLY","event",expected)));
    }
}
