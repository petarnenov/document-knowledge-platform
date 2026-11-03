package org.documentknowledge.loggingclient;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Original catalog policy applies both before queue admission and after spool recovery. */
class LogWireDiagnosticsTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String SYMBOL="org.example.Work.execute", SOURCE="src/main/java/org/example/Work.java";
    static final LogDiagnosticCatalog.Dependency DEP=new LogDiagnosticCatalog.Dependency("postgresql","42.7.7","sha256:"+"a".repeat(64),LogDiagnosticCatalog.Provenance.VERIFIED);
    static final LogDiagnosticCatalog CATALOG=new LogDiagnosticCatalog(Set.of("DB_UNAVAILABLE"),Map.of(DEP.name(),DEP),
        Set.of("java.io.IOException"),Set.of(new LogDiagnosticCatalog.Frame(SYMBOL,SOURCE)));
    static final LogVocabulary VOCAB=new LogVocabulary(LogWireTest.VOCABULARY.events(),LogWireTest.VOCABULARY.phases(),CATALOG);
    static LifecycleEvent event(LogDiagnostics diagnostics) {
        var base=LogWireTest.event(UUID.randomUUID());
        return new LifecycleEvent(base.metadata(),base.phase(),base.outcome(),4,2,1,true,diagnostics);
    }
    static LogDiagnostics rich() {
        return new LogDiagnostics(503,1500L,"DB_UNAVAILABLE","08006",DEP,
            new LogDiagnostics.Failure("java.io.IOException",List.of("java.io.IOException"),List.of(new LogDiagnostics.Frame(SYMBOL,SOURCE,42L))),
            new LogDiagnostics.Attributes(123L,2L,true),new LogDiagnostics.Counts(2,1,3));
    }
    static LogWire.Prepared prepare(LogDiagnostics context) {return LogWire.prepare(event(context),LogWireTest.EXECUTION,VOCAB);}
    @SuppressWarnings("unchecked") static Map<String,Object> row(byte[] bytes) {return JSON.readValue(bytes,Map.class);}
    @SuppressWarnings("unchecked") static Map<String,Object> context(Map<String,Object> row) {return (Map<String,Object>)row.get("context");}
    @Test void richContextRestoresExactOriginalBytesAndOldLifecycleStillRestores() {
        var prepared=prepare(rich());var restored=LogWire.restore(prepared.bytes(),LogWireTest.EXECUTION,VOCAB);
        assertEquals(prepared.id(),restored.id());assertArrayEquals(prepared.bytes(),restored.bytes());
        var context=context(row(restored.bytes()));assertEquals(503,context.get("status"));assertEquals("08006",context.get("sqlState"));
        assertEquals(DEP.imageDigest(),context.get("dependencyImageDigest"));assertEquals(DEP.version(),context.get("dependencyVersion"));
        assertEquals(Map.of("symbol",SYMBOL,"source",SOURCE,"line",42),((List<?>)context.get("stackFrames")).getFirst());
        assertEquals(Map.of("count",1,"cleanupSettled",true,"bytes",123,"queueDepth",2,"cancelRequested",true,"vendorBuildProvenance","VERIFIED"),context.get("attributes"));
        var legacy=LogWire.prepare(LogWireTest.event(UUID.randomUUID()),LogWireTest.EXECUTION,LogWireTest.VOCABULARY);
        assertArrayEquals(legacy.bytes(),LogWire.restore(legacy.bytes(),LogWireTest.EXECUTION,LogWireTest.VOCABULARY).bytes());
        assertThrows(IllegalArgumentException.class,()->LogWire.restore(prepared.bytes(),LogWireTest.EXECUTION,LogWireTest.VOCABULARY));
    }
    @Test void unknownSymbolsAreOmittedAndCountedBeforeSerialization() {
        var failure=new LogDiagnostics.Failure("private.Unknown",List.of("java.io.IOException","private.Cause"),
            List.of(new LogDiagnostics.Frame("private.Frame",null,null),new LogDiagnostics.Frame(SYMBOL,"private/Canary.java",1L),new LogDiagnostics.Frame(SYMBOL,null,null)));
        var prepared=prepare(new LogDiagnostics(null,null,null,null,null,failure,null,new LogDiagnostics.Counts(0,0,2)));
        String text=new String(prepared.bytes(),StandardCharsets.UTF_8);assertFalse(text.contains("private"));
        var context=context(row(prepared.bytes()));assertFalse(context.containsKey("exceptionClass"));assertEquals(6,context.get("unregisteredSymbols"));
        assertEquals(List.of("java.io.IOException"),context.get("causeClasses"));assertEquals(List.of(Map.of("symbol",SYMBOL)),context.get("stackFrames"));
        assertArrayEquals(prepared.bytes(),LogWire.restore(prepared.bytes(),LogWireTest.EXECUTION,VOCAB).bytes());
        assertThrows(IllegalArgumentException.class,()->prepare(new LogDiagnostics(null,null,null,null,null,failure,null,new LogDiagnostics.Counts(0,0,LifecycleEvent.MAX_INTEGER))));
    }
    @Test void unregisteredErrorsAndDependencyVersionsCannotBeSmuggledIntoQueue() {
        assertThrows(IllegalArgumentException.class,()->prepare(new LogDiagnostics(null,null,"OTHER",null,null,null,null,null)));
        for(var dependency:List.of(new LogDiagnosticCatalog.Dependency("postgresql","other",null,LogDiagnosticCatalog.Provenance.UNKNOWN),
                new LogDiagnosticCatalog.Dependency("other","42.7.7",null,LogDiagnosticCatalog.Provenance.UNKNOWN)))
            assertThrows(IllegalArgumentException.class,()->prepare(new LogDiagnostics(null,null,null,null,dependency,null,null,null)));
    }
    @Test void trustedManifestVersionTextUsesExistingV1BoundsAndStillRequiresExactEquality() {
        for(String version:List.of("25.0.5 (build 8)","vendor-"+"λ".repeat(40),"😀".repeat(128))) {
            var dependency=new LogDiagnosticCatalog.Dependency("jdk",version,null,LogDiagnosticCatalog.Provenance.UNKNOWN);
            var catalog=new LogDiagnosticCatalog(Set.of(),Map.of("jdk",dependency),Set.of(),Set.of());
            var vocabulary=new LogVocabulary(LogWireTest.VOCABULARY.events(),LogWireTest.VOCABULARY.phases(),catalog);
            var diagnostic=new LogDiagnostics(null,null,null,null,dependency,null,null,null);
            var prepared=LogWire.prepare(event(diagnostic),LogWireTest.EXECUTION,vocabulary);
            assertEquals(version,context(row(prepared.bytes())).get("dependencyVersion"));
            assertArrayEquals(prepared.bytes(),LogWire.restore(prepared.bytes(),LogWireTest.EXECUTION,vocabulary).bytes());
            var different=new LogDiagnosticCatalog.Dependency("jdk","different version",null,LogDiagnosticCatalog.Provenance.UNKNOWN);
            assertThrows(IllegalArgumentException.class,()->LogWire.prepare(event(new LogDiagnostics(null,null,null,null,different,null,null,null)),LogWireTest.EXECUTION,vocabulary));
        }
        assertThrows(IllegalArgumentException.class,()->new LogDiagnosticCatalog.Dependency("jdk","",null,LogDiagnosticCatalog.Provenance.UNKNOWN));
        assertThrows(IllegalArgumentException.class,()->new LogDiagnosticCatalog.Dependency("jdk","λ".repeat(129),null,LogDiagnosticCatalog.Provenance.UNKNOWN));
    }
    @Test void restoredSnapshotsRejectRawFieldsNumbersAndCatalogChanges() {
        var prepared=prepare(rich());
        for(String key:List.of("message","tenantId","authorization","requestBody")) {
            var row=row(prepared.bytes());context(row).put(key,"private-content-canary");
            assertThrows(IllegalArgumentException.class,()->LogWire.restore(JSON.writeValueAsBytes(row),LogWireTest.EXECUTION,VOCAB));
        }
        for(Object invalid:List.of(503.0,-1,600,"503")) {
            var row=row(prepared.bytes());context(row).put("status",invalid);
            assertThrows(IllegalArgumentException.class,()->LogWire.restore(JSON.writeValueAsBytes(row),LogWireTest.EXECUTION,VOCAB));
        }
        for(String key:List.of("exceptionClass","errorCode","dependencyVersion")) {
            var row=row(prepared.bytes());context(row).put(key,key.equals("errorCode")?"UNKNOWN":"private.Canary");
            assertThrows(IllegalArgumentException.class,()->LogWire.restore(JSON.writeValueAsBytes(row),LogWireTest.EXECUTION,VOCAB));
        }
        var row=row(prepared.bytes());context(row).put("stackFrames",List.of(Map.of("symbol",SYMBOL,"source",SOURCE,"line",42,"message","private")));
        assertThrows(IllegalArgumentException.class,()->LogWire.restore(JSON.writeValueAsBytes(row),LogWireTest.EXECUTION,VOCAB));
        var missing=row(prepared.bytes());context(missing).remove("causeClasses");
        assertThrows(IllegalArgumentException.class,()->LogWire.restore(JSON.writeValueAsBytes(missing),LogWireTest.EXECUTION,VOCAB));
    }
}
