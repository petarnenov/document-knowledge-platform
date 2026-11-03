package org.documentknowledge.documentmanagement.bootstrap;

import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;
import org.documentknowledge.documentmanagement.application.upload.UploadDiagnosticsPort;
import org.documentknowledge.loggingclient.ApplicationLogRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LoggingUploadDiagnosticsTest {
    @TempDir Path temporary;
    @Test void originalRuntimeKeepsConcurrentCallerContextsAndDoesNotWaitForRealTlsCollector() throws Exception {
        assertSame(UploadDiagnosticsPort.NOOP,LoggingUploadDiagnostics.compose(null));
        try(var collector=new DatabaseLogCollector(temporary.toRealPath());
            var runtime=ApplicationLogRuntime.open("document-management",Application.class.getResourceAsStream(ApplicationLogRuntime.RESOURCE),collector.configuration)) {
            var diagnostics=LoggingUploadDiagnostics.compose(runtime);
            var contexts=List.of(new DiagnosticContext(UUID.randomUUID(),"a".repeat(32),"b".repeat(16)),DiagnosticContext.fresh());
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks=new ArrayList<Future<?>>();
                for(int index=0;index<2;index++) {
                    int current=index;
                    tasks.add(executor.submit(()->{
                        var call=diagnostics.started(contexts.get(current),current==0?UploadDiagnosticsPort.Operation.PROVIDER_STORAGE_PUT:UploadDiagnosticsPort.Operation.PROVIDER_SCAN,UploadDiagnosticsPort.Facts.EMPTY);
                        call.failure(new java.net.ConnectException("credential-canary"));
                        if(current==0){call.status(429);call.status(0);call.cancelRequested();}
                        call.completed(UploadDiagnosticsPort.Outcome.FAILED,new IllegalStateException("mapped-canary"),
                            new UploadDiagnosticsPort.Facts(503,100L,1,1,false,null,UploadDiagnosticsPort.Error.PROVIDER_UNAVAILABLE));
                    }));
                }
                assertTimeoutPreemptively(Duration.ofSeconds(2),()->{for(var task:tasks)task.get();});
            }
            assertTrue(collector.entered.await(3,TimeUnit.SECONDS));assertEquals(1,collector.release.getCount());assertEquals(0,runtime.status().committed());
            collector.release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(runtime.status().committed()<4 && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(4,runtime.status().committed());
            for(var context:contexts) {
                var rows=collector.rows.stream().filter(row->context.correlationId().toString().equals(row.get("correlationId"))).toList();assertEquals(2,rows.size());
                var terminal=(Map<?,?>)rows.getLast().get("context");assertEquals("java.net.ConnectException",terminal.get("exceptionClass"));
                assertEquals(context.traceId()!=null?429:503,terminal.get("status"));assertEquals(100,terminal.get("remainingDeadlineMs"));
                assertEquals(context.traceId(),rows.getLast().get("traceId"));assertEquals(runtime.execution().build().buildId().toString(),rows.getLast().get("buildId"));
                if(context.traceId()!=null){assertEquals("software.amazon.awssdk.s3",terminal.get("dependency"));assertEquals(true,((Map<?,?>)terminal.get("attributes")).get("cancelRequested"));}
                else assertFalse(terminal.containsKey("dependency"));
            }
            assertFalse(DatabaseLogCollector.JSON.writeValueAsString(collector.rows).contains("canary"));
        }
    }
}
