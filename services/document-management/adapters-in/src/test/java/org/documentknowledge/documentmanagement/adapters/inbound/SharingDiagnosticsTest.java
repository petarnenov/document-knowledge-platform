package org.documentknowledge.documentmanagement.adapters.inbound;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import io.micronaut.http.*;
import org.documentknowledge.documentmanagement.application.DmFailure;
import org.documentknowledge.documentmanagement.application.sharing.SharingDeadline;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharingDiagnosticsTest {
    static final String PRIVATE="private-token tenant document SQL https://private.invalid/\nforged-log";
    static final class Capture implements AutoCloseable {
        final Logger logger=Logger.getLogger("org.documentknowledge.documentmanagement.sharing");
        final List<String> lines=new ArrayList<>();final List<Throwable> thrown=new ArrayList<>();
        final boolean parents=logger.getUseParentHandlers();final Level level=logger.getLevel();
        final Handler handler=new Handler(){public void publish(LogRecord r){if(logger.getName().equals(r.getLoggerName())){lines.add(r.getMessage());thrown.add(r.getThrown());}}public void flush(){}public void close(){}};
        Capture(){logger.setUseParentHandlers(false);logger.setLevel(Level.ALL);logger.addHandler(handler);}
        public void close(){logger.removeHandler(handler);logger.setUseParentHandlers(parents);logger.setLevel(level);}
    }
    @Test void captureIncludesOnlyExactBoundaryLoggerWithoutChangingChildDelivery(){
        var child=Logger.getLogger("org.documentknowledge.documentmanagement.sharing.authority");
        var childLines=new ArrayList<String>();
        var handler=new Handler(){public void publish(LogRecord record){childLines.add(record.getMessage());}public void flush(){}public void close(){}};
        var previousLevel=child.getLevel();var previousParents=child.getUseParentHandlers();
        child.setLevel(Level.ALL);child.setUseParentHandlers(true);child.addHandler(handler);
        try(var capture=new Capture()){
            child.warning("synthetic child event");
            SharingDiagnostics.controller(new DmFailure(404,PRIVATE));
            assertEquals(List.of("synthetic child event"),childLines);
            assertEquals(List.of("dm_sharing stage=CONTROLLER_FAILURE category=NOT_FOUND"),capture.lines);
            assertEquals(Collections.singletonList(null),capture.thrown);
        }finally{child.removeHandler(handler);child.setLevel(previousLevel);child.setUseParentHandlers(previousParents);}
    }
    @Test void closedEventsNeverRenderPrivateExceptionData(){
        try(var c=new Capture()){
            SharingDiagnostics.controller(new DmFailure(404,PRIVATE));SharingDiagnostics.controller(new DmFailure(503,PRIVATE));SharingDiagnostics.controller(new DmFailure(409,PRIVATE));SharingDiagnostics.unexpected();
            SharingDiagnostics.response(200);SharingDiagnostics.response(404);SharingDiagnostics.response(503);SharingDiagnostics.response(Integer.MIN_VALUE);
            SharingDiagnostics.failure(new TimeoutException(PRIVATE));SharingDiagnostics.failure(new CancellationException(PRIVATE));SharingDiagnostics.failure(new DmFailure(503,PRIVATE));
            SharingDiagnostics.failure(new RuntimeException(PRIVATE){public String toString(){throw new AssertionError("must not render");}public synchronized Throwable getCause(){throw new AssertionError("must not traverse");}});SharingDiagnostics.failure(null);
            assertEquals(List.of("dm_sharing stage=CONTROLLER_FAILURE category=NOT_FOUND","dm_sharing stage=CONTROLLER_FAILURE category=UNAVAILABLE","dm_sharing stage=CONTROLLER_FAILURE category=OTHER","dm_sharing stage=CONTROLLER_FAILURE category=UNEXPECTED","dm_sharing stage=FILTER_RESPONSE category=NOT_FOUND","dm_sharing stage=FILTER_RESPONSE category=UNAVAILABLE","dm_sharing stage=FILTER_RESPONSE category=OTHER","dm_sharing stage=FILTER_FAILURE category=TIMEOUT","dm_sharing stage=FILTER_FAILURE category=CANCELLED","dm_sharing stage=FILTER_FAILURE category=DOMAIN_FAILURE","dm_sharing stage=FILTER_FAILURE category=UNEXPECTED","dm_sharing stage=FILTER_FAILURE category=UNEXPECTED"),c.lines);
            assertTrue(c.thrown.stream().allMatch(Objects::isNull));
        }
    }
    @Test void failingSinkCannotReplaceEstablishedControlFlow(){
        var lines=new ArrayList<String>();
        SharingDiagnostics.emit(SharingDiagnostics.Stage.FILTER_FAILURE,SharingDiagnostics.Kind.TIMEOUT,lines::add);
        assertEquals(List.of("dm_sharing stage=FILTER_FAILURE category=TIMEOUT"),lines);
        assertDoesNotThrow(()->SharingDiagnostics.emit(SharingDiagnostics.Stage.FILTER_FAILURE,SharingDiagnostics.Kind.TIMEOUT,line->{throw new IllegalStateException(PRIVATE);}));
        assertDoesNotThrow(()->SharingDiagnostics.emit(SharingDiagnostics.Stage.FILTER_FAILURE,SharingDiagnostics.Kind.TIMEOUT,null));
        assertDoesNotThrow(()->SharingDiagnostics.emit(null,SharingDiagnostics.Kind.TIMEOUT,lines::add));
        assertDoesNotThrow(()->SharingDiagnostics.emit(SharingDiagnostics.Stage.FILTER_FAILURE,null,lines::add));
    }
    @Test void realFilterBoundaryDistinguishesResponseFromDeadlineFallback(){
        try(var c=new Capture()){
            var filter=new SharingRequestDeadlineFilter();
            var allowed=HttpRequest.POST("/api/v1/documents/knowledge-share-assessments","");
            var denied=Mono.from(filter.doFilter(allowed,r->Mono.just(HttpResponse.notFound()))).block(Duration.ofSeconds(2));assertEquals(404,denied.code());
            var cancelled=HttpRequest.POST("/api/v1/documents/knowledge-share-assessments","");
            var unavailable=Mono.from(filter.doFilter(cancelled,r->{r.getAttribute(SharingRequestDeadlineFilter.ATTRIBUTE,SharingDeadline.class).orElseThrow().cancel();return Mono.just(HttpResponse.notFound());})).block(Duration.ofSeconds(2));
            assertEquals(503,unavailable.code());assertEquals(Map.of("code","UNAVAILABLE"),unavailable.body());assertEquals("no-store",unavailable.header("Cache-Control"));
            assertEquals(List.of("dm_sharing stage=FILTER_RESPONSE category=NOT_FOUND","dm_sharing stage=FILTER_RESPONSE category=NOT_FOUND","dm_sharing stage=FILTER_FAILURE category=DOMAIN_FAILURE"),c.lines);
        }
    }
}
