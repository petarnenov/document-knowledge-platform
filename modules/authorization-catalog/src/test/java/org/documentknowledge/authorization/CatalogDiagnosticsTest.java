package org.documentknowledge.authorization;

import java.io.IOException;
import java.util.*;
import java.util.logging.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogDiagnosticsTest {
    static final String PRIVATE="private-token tenant document SQL https://private.invalid/\nforged-log";
    static final class Capture implements AutoCloseable {
        final Logger logger=Logger.getLogger("org.documentknowledge.authorization.catalog");
        final List<String> lines=new ArrayList<>();final List<Throwable> thrown=new ArrayList<>();
        final boolean parents=logger.getUseParentHandlers();final Level level=logger.getLevel();
        final Handler handler=new Handler(){public void publish(LogRecord r){lines.add(r.getMessage());thrown.add(r.getThrown());}public void flush(){}public void close(){}};
        Capture(){logger.setUseParentHandlers(false);logger.setLevel(Level.ALL);logger.addHandler(handler);}
        public void close(){logger.removeHandler(handler);logger.setUseParentHandlers(parents);logger.setLevel(level);}
    }
    @Test void onlyFiniteFallbackKindSurvivesWithoutRenderingOrCauseTraversal(){
        try(var c=new Capture()){
            CatalogDiagnostics.failure(new IOException(PRIVATE));
            CatalogDiagnostics.failure(assertThrows(JacksonException.class,()->JsonMapper.builder().build().readValue(PRIVATE,Object.class)));
            CatalogDiagnostics.failure(new IllegalArgumentException(PRIVATE));
            CatalogDiagnostics.failure(new RuntimeException(PRIVATE){public String toString(){throw new AssertionError();}public synchronized Throwable getCause(){throw new AssertionError();}});
            CatalogDiagnostics.failure(null);
            assertEquals(List.of("grant_catalog_failure category=IO","grant_catalog_failure category=JSON","grant_catalog_failure category=INVALID","grant_catalog_failure category=OTHER","grant_catalog_failure category=OTHER"),c.lines);
            assertTrue(c.thrown.stream().allMatch(Objects::isNull));
        }
    }
    @Test void sinkFailureCannotReplaceFailClosedDecision(){
        var lines=new ArrayList<String>();CatalogDiagnostics.emit(CatalogDiagnostics.Kind.IO,lines::add);assertEquals(List.of("grant_catalog_failure category=IO"),lines);
        assertDoesNotThrow(()->CatalogDiagnostics.emit(CatalogDiagnostics.Kind.IO,line->{throw new IllegalStateException(PRIVATE);}));
        assertDoesNotThrow(()->CatalogDiagnostics.emit(CatalogDiagnostics.Kind.IO,null));assertDoesNotThrow(()->CatalogDiagnostics.emit(null,lines::add));
    }
}
