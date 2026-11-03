package org.documentknowledge.inspector;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics;
import org.documentknowledge.inspector.diagnostics.InspectorDiagnostics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StructureDiagnosticsTest {
    @TempDir Path temp;
    static final class Seen implements Observation {
        final Context context;final Phase phase;volatile Throwable failure;volatile Outcome outcome;volatile Facts facts;
        Seen(Context context,Phase phase){this.context=context;this.phase=phase;}
        public void failure(Throwable value){if(failure==null)failure=value;}
        public void completed(Outcome value,Facts facts){assertNull(outcome,"single terminal");this.facts=facts;this.outcome=value;}
    }
    static final class Recording implements InspectorDiagnostics {
        final List<Seen> seen=new java.util.concurrent.CopyOnWriteArrayList<>();
        public Observation started(Context context,Phase phase){var event=new Seen(context,phase);seen.add(event);return event;}
        Seen one(Phase phase){return seen.stream().filter(e->e.phase==phase).reduce((a,b)->{throw new AssertionError("duplicate phase");}).orElseThrow();}
    }
    @Test void actualFourFormatParsingAndCleanupShareExplicitContext() throws Exception {
        for(Format format:Format.values()) {
            var port=new Recording();var context=Context.fresh();var inspector=new StructureInspector(temp,port);
            byte[] bytes=format==Format.PDF?Fixtures.pdf(1):Fixtures.zip(Fixtures.office(format));
            assertEquals(bytes.length,inspector.inspect(new ByteArrayInputStream(bytes),format,context).sizeBytes());
            var operation=port.one(Phase.OPERATION);assertEquals(context,operation.context);assertEquals(Outcome.SUCCEEDED,operation.outcome);assertEquals(bytes.length,operation.facts.count());assertFalse(operation.facts.cleanupSettled());assertNull(operation.failure);
            var cleanup=port.one(Phase.CLEANUP);assertEquals(context,cleanup.context);assertEquals(Outcome.SUCCEEDED,cleanup.outcome);assertTrue(cleanup.facts.cleanupSettled());assertEquals(2,cleanup.facts.count());
            try(var paths=Files.list(temp)){assertEquals(0,paths.count());}
        }
    }
    @Test void capturesExactInputIoBeforeMappingAndStillCleans() throws Exception {
        var port=new Recording();var original=new IOException("PRIVATE_DOCUMENT_AND_PATH");
        var input=new InputStream(){public int read() throws IOException{throw original;}};
        var failure=assertThrows(InspectionFailure.class,()->new StructureInspector(temp,port).inspect(input,Format.PDF,Context.fresh()));
        assertEquals("UNAVAILABLE",failure.code());assertSame(original,failure.getCause());assertSame(original,port.one(Phase.OPERATION).failure);assertEquals(Outcome.FAILED,port.one(Phase.OPERATION).outcome);assertEquals(InspectorDiagnostics.Error.OPERATION_FAILED,port.one(Phase.OPERATION).facts.error());assertTrue(port.one(Phase.CLEANUP).facts.cleanupSettled());
    }
    @Test void originalParserFailureAndMissingTemporaryRootAreObservedWithoutChangingMapping() throws Exception {
        var port=new Recording();
        var mapped=assertThrows(InspectionFailure.class,()->new StructureInspector(temp,port).inspect(new ByteArrayInputStream("%PDF-malformed".getBytes()),Format.PDF,Context.fresh()));
        assertEquals("INVALID_FILE",mapped.code());assertNotNull(mapped.getCause());assertSame(mapped.getCause(),port.one(Phase.OPERATION).failure);assertEquals(Outcome.SUCCEEDED,port.one(Phase.CLEANUP).outcome);
        var missing=new Recording();var original=assertThrows(NoSuchFileException.class,()->new StructureInspector(temp.resolve("absent"),missing).inspect(InputStream.nullInputStream(),Format.PDF,Context.fresh()));
        assertSame(original,missing.one(Phase.OPERATION).failure);assertEquals(1,missing.seen.size());
    }
    @Test void cleanupFailureReportsActualFailureAndDoesNotInventSuccessfulDeletion() throws Exception {
        var port=new Recording();var bytes=Fixtures.pdf(1);var source=new ByteArrayInputStream(bytes){boolean replaced;
            @Override public synchronized int read(byte[] b,int off,int len){int count=super.read(b,off,len);if(count==-1&&!replaced){replaced=true;try(var dirs=Files.list(temp)){Path file=dirs.findFirst().orElseThrow().resolve("source");Files.delete(file);Files.createDirectory(file);Files.writeString(file.resolve("private"),"PRIVATE");}catch(IOException failure){throw new UncheckedIOException(failure);}}return count;}};
        var failure=assertThrows(DirectoryNotEmptyException.class,()->new StructureInspector(temp,port).inspect(source,Format.PDF,Context.fresh()));
        var cleanup=port.one(Phase.CLEANUP);assertSame(failure,cleanup.failure);assertEquals(Outcome.FAILED,cleanup.outcome);assertFalse(cleanup.facts.cleanupSettled());assertEquals(0,cleanup.facts.count());assertEquals(InspectorDiagnostics.Error.CLEANUP_FAILED,cleanup.facts.error());
        try(var dirs=Files.list(temp)){assertEquals(1,dirs.count(),"existing ordered cleanup stops on first deletion failure");}
    }
    @Test void brokenDiagnosticCallbacksCannotChangeSuccessfulBusinessResult() throws Exception {
        InspectorDiagnostics broken=(context,phase)->{throw new IllegalStateException("PRIVATE_DIAGNOSTIC_FAILURE");};
        assertEquals(1,new StructureInspector(temp,broken).inspect(new ByteArrayInputStream(Fixtures.pdf(1)),Format.PDF,Context.fresh()).pageCount());
    }
}
