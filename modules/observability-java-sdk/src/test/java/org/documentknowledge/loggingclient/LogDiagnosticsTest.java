package org.documentknowledge.loggingclient;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogDiagnosticsTest {
    private static final String SYMBOL=LogDiagnosticsTest.class.getName()+".caught";
    private static final String SOURCE="modules/observability-java-sdk/src/test/java/org/documentknowledge/loggingclient/LogDiagnosticsTest.java";
    private static final LogDiagnosticCatalog.Dependency DEPENDENCY=new LogDiagnosticCatalog.Dependency("postgresql","42.7.7",null,LogDiagnosticCatalog.Provenance.UNKNOWN);
    private static final LogDiagnosticCatalog CATALOG=new LogDiagnosticCatalog(Set.of("DB_UNAVAILABLE"),Map.of("postgresql",DEPENDENCY),
        Set.of(IOException.class.getName(),IllegalStateException.class.getName()),Set.of(new LogDiagnosticCatalog.Frame(SYMBOL,SOURCE)));
    private static IOException caught() {
        try {throw new IOException("private-document-token-canary",new IllegalStateException("private-cause-canary"));}
        catch(IOException actual) {return actual;}
    }
    @Test void capturesRealFailureAndCauseWithoutReadingMessagesOrRegisteringUnknownSymbols() {
        var actual=caught();var capture=LogExceptionCapture.capture(actual,CATALOG);
        assertEquals(IOException.class.getName(),capture.failure().exceptionClass());
        assertEquals(List.of(IllegalStateException.class.getName()),capture.failure().causeClasses());
        assertEquals(1,capture.failure().stackFrames().size());
        var frame=capture.failure().stackFrames().getFirst();assertEquals(SYMBOL,frame.symbol());assertEquals(SOURCE,frame.source());assertTrue(frame.line()>0);
        assertEquals(2,capture.counts().redactedFields());assertTrue(capture.counts().unregisteredSymbols()>0);
        assertFalse(capture.toString().contains("canary"));
        var hostile=new RuntimeException() {
            @Override public String getMessage() {throw new AssertionError("Messages must never be read");}
            @Override public String getLocalizedMessage() {throw new AssertionError("Messages must never be read");}
            @Override public String toString() {throw new AssertionError("Throwable must never be serialized");}
        };
        assertNull(LogExceptionCapture.capture(hostile,CATALOG).failure().exceptionClass());
        assertEquals(1,LogExceptionCapture.capture(hostile,CATALOG).counts().redactedFields());
    }
    @Test void rootFrameBoundsSourceMismatchAndNativeFramesStaySafe() {
        var actual=caught();
        actual.setStackTrace(new StackTraceElement[]{new StackTraceElement(LogDiagnosticsTest.class.getName(),"caught","private-canary.java",99),
            new StackTraceElement(LogDiagnosticsTest.class.getName(),"caught",null,-2)});
        var captured=LogExceptionCapture.capture(actual,CATALOG);
        assertEquals(1,captured.counts().unregisteredSymbols());assertEquals(1,captured.failure().stackFrames().size());
        assertNull(captured.failure().stackFrames().getFirst().source());assertNull(captured.failure().stackFrames().getFirst().line());
        var frame=new StackTraceElement(LogDiagnosticsTest.class.getName(),"caught","LogDiagnosticsTest.java",10);
        var many=new StackTraceElement[40];Arrays.fill(many,frame);actual.setStackTrace(many);
        captured=LogExceptionCapture.capture(actual,CATALOG);assertEquals(32,captured.failure().stackFrames().size());assertEquals(8,captured.counts().truncatedFields());
        Throwable chain=null;for(int index=0;index<7;index++)chain=new IOException("private",chain);
        chain.setStackTrace(new StackTraceElement[0]);
        captured=LogExceptionCapture.capture(chain,CATALOG);assertEquals(4,captured.failure().causeClasses().size());assertEquals(1,captured.counts().truncatedFields());
        var first=new IOException();var second=new IOException();first.initCause(second);second.initCause(first);
        first.setStackTrace(new StackTraceElement[0]);
        assertEquals(1,LogExceptionCapture.capture(first,CATALOG).counts().truncatedFields());
    }
    @Test void registeredSuperclassDoesNotReplaceActualModeledSubclass() {
        var failure=new java.io.FileNotFoundException("private-modeled-failure-canary");
        failure.setStackTrace(new StackTraceElement[0]);
        var before=LogExceptionCapture.capture(failure,CATALOG);
        assertNull(before.failure().exceptionClass());
        assertEquals(1,before.counts().unregisteredSymbols());
        var selected=new LogDiagnosticCatalog(Set.of(),Map.of(),
            Set.of(IOException.class.getName(),java.io.FileNotFoundException.class.getName()),Set.of());
        var after=LogExceptionCapture.capture(failure,selected);
        assertEquals(java.io.FileNotFoundException.class.getName(),after.failure().exceptionClass());
        assertEquals(0,after.counts().unregisteredSymbols());
        assertFalse(after.toString().contains("canary"));
        assertFalse(CATALOG.exceptionClasses().contains(java.io.FileNotFoundException.class.getName()));
    }
    @Test void nativeAndSourceLessFramesKeepOnlyRegisteredSymbols() {
        var failure=caught();
        failure.setStackTrace(new StackTraceElement[]{
            new StackTraceElement(LogDiagnosticsTest.class.getName(),"caught","LogDiagnosticsTest.java",-2),
            new StackTraceElement(LogDiagnosticsTest.class.getName(),"caught",null,10)});
        var frames=LogExceptionCapture.capture(failure,CATALOG).failure().stackFrames();
        assertEquals(List.of(new LogDiagnostics.Frame(SYMBOL,null,null),new LogDiagnostics.Frame(SYMBOL,null,null)),frames);
    }
    @Test void typedContextAndCatalogInputsAreBoundedAndImmutable() {
        var causes=new ArrayList<>(List.of(IOException.class.getName()));var frames=new ArrayList<LogDiagnostics.Frame>();
        var failure=new LogDiagnostics.Failure(IOException.class.getName(),causes,frames);causes.clear();frames.add(new LogDiagnostics.Frame(SYMBOL,null,null));
        assertEquals(1,failure.causeClasses().size());assertTrue(failure.stackFrames().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->failure.causeClasses().clear());
        assertThrows(UnsupportedOperationException.class,()->CATALOG.frames().clear());
        var context=new LogDiagnostics(503,1000L,"DB_UNAVAILABLE","08006",DEPENDENCY,failure,new LogDiagnostics.Attributes(100L,2L,true),null);
        assertEquals(LogDiagnostics.Counts.ZERO,context.counts());assertEquals(DEPENDENCY,CATALOG.dependencies().get("postgresql"));
        assertEquals(Set.of("DB_UNAVAILABLE"),CATALOG.errorCodes());
        for(Integer status:List.of(99,600))assertThrows(IllegalArgumentException.class,()->new LogDiagnostics(status,null,null,null,null,null,null,null));
        for(Long count:List.of(-1L,9007199254740992L)) {
            assertThrows(IllegalArgumentException.class,()->new LogDiagnostics(null,count,null,null,null,null,null,null));
            assertThrows(IllegalArgumentException.class,()->new LogDiagnostics.Counts(count,0,0));
            assertThrows(IllegalArgumentException.class,()->new LogDiagnostics.Attributes(null,count,null));
        }
        assertThrows(IllegalArgumentException.class,()->new LogDiagnostics(null,null,"private text",null,null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnostics(null,null,null,"private text",null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnostics.Failure(null,Collections.nCopies(5,"java.io.IOException"),List.of()));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnostics.Failure(null,List.of(),Collections.nCopies(33,new LogDiagnostics.Frame(SYMBOL,null,null))));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnostics.Frame(SYMBOL,"/private/canary",1L));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnosticCatalog.Frame(SYMBOL,"../private"));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnosticCatalog.Frame(SYMBOL,"a//b"));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnosticCatalog.Dependency("db","1","not-a-digest",LogDiagnosticCatalog.Provenance.UNKNOWN));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnosticCatalog(Set.of(),Map.of("wrong",DEPENDENCY),Set.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnosticCatalog(Set.of(),Map.of(),Set.of(),Set.of(new LogDiagnosticCatalog.Frame(SYMBOL,"a.java"),new LogDiagnosticCatalog.Frame(SYMBOL,"b.java"))));
    }
    @Test void originalManifestCatalogIsClosedAndDuplicateAware() {
        Map<String,Object> catalog=new HashMap<>(Map.of("events",List.of(),"phases",List.of(),"errorCodes",List.of("DB_UNAVAILABLE"),
            "exceptionClasses",List.of("java.io.IOException"),"frames",List.of(Map.of("symbol",SYMBOL,"source",SOURCE)),
            "dependencies",List.of(Map.of("name","postgresql","version","42.7.7","vendorBuildProvenance","UNKNOWN"))));
        assertEquals(DEPENDENCY,LogDiagnosticCatalog.fromManifest(catalog).dependencies().get("postgresql"));
        for(String name:List.of("errorCodes","exceptionClasses","frames","dependencies")) {
            Object good=catalog.get(name);var duplicate=new ArrayList<Object>((List<?>)good);duplicate.add(duplicate.getFirst());catalog.put(name,duplicate);
            assertThrows(IllegalArgumentException.class,()->LogDiagnosticCatalog.fromManifest(catalog));catalog.put(name,good);
        }
        catalog.put("unknown","private-canary");assertThrows(IllegalArgumentException.class,()->LogDiagnosticCatalog.fromManifest(catalog));
        catalog.remove("unknown");catalog.put("frames",List.of(Map.of("symbol",SYMBOL,"source",SOURCE,"unknown","private")));
        assertThrows(IllegalArgumentException.class,()->LogDiagnosticCatalog.fromManifest(catalog));
    }
}
