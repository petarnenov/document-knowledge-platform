package org.documentknowledge.documentmanagement.application.upload;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.CancellationException;
import org.documentknowledge.documentmanagement.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.documentknowledge.documentmanagement.application.upload.UploadDiagnosticsPort.*;
import static org.junit.jupiter.api.Assertions.*;

class UploadObservationsTest {
    @TempDir Path directory;
    @Test void manyChunksHaveOneAggregateReadAndActualCloseWithOriginalDelegateBehavior()throws Exception {
        var recorder=new RecordingUploadDiagnostics();var context=DiagnosticContext.fresh();
        var observations=new UploadObservations(recorder,context);byte[] bytes=new byte[65536];
        try(var stream=observations.open(()->Facts.EMPTY,observer->new ByteArrayInputStream(bytes))) {
            assertTrue(stream.markSupported());stream.mark(100);assertEquals(65536,stream.available());assertEquals(0,stream.read());stream.reset();
            assertEquals(10,stream.skip(10));assertEquals(0,stream.read(new byte[0]));
            var buffer=new byte[7];int total=10,count;while((count=stream.read(buffer,0,buffer.length))>=0)total+=count;
            assertEquals(bytes.length,total);assertEquals(-1,stream.read());
        }
        assertEquals(3,recorder.entries.size());
        assertEquals(java.util.List.of(Operation.PROVIDER_STORAGE_OPEN,Operation.PROVIDER_STORAGE_READ,Operation.PROVIDER_STORAGE_CLOSE),recorder.entries.stream().map(e->e.operation).toList());
        for(var entry:recorder.entries){assertSame(context,entry.context);assertEquals(Outcome.SUCCEEDED,entry.outcome);assertEquals(1,entry.completions.get());}
    }
    @Test void earlyCloseIsUnknownAndRealReadFailureKeepsExactIOException()throws Exception {
        var recorder=new RecordingUploadDiagnostics();var observations=new UploadObservations(recorder,DiagnosticContext.fresh());
        try(var stream=observations.stream(new ByteArrayInputStream(new byte[8]),()->Facts.EMPTY)){assertEquals(0,stream.read());}
        assertEquals(Outcome.UNKNOWN,recorder.entries.getFirst().outcome);assertNull(recorder.entries.getFirst().failure);assertNull(recorder.entries.getFirst().facts.error());assertNull(recorder.entries.getFirst().facts.cancelRequested());
        Path file=directory.resolve("owned");Files.write(file,new byte[]{1});var raw=Files.newInputStream(file);raw.close();
        var stream=observations.stream(raw,()->Facts.EMPTY);var failed=assertThrows(IOException.class,stream::read);
        var entry=recorder.entries.get(2);assertSame(failed,entry.failure);assertEquals(Outcome.FAILED,entry.outcome);stream.close();assertEquals(1,entry.completions.get());
    }
    @Test void closeAndBulkReadFailuresRemainOriginalAndDoNotBecomeSuccessfulConsumption()throws Exception {
        var recorder=new RecordingUploadDiagnostics();var observations=new UploadObservations(recorder,DiagnosticContext.fresh());var original=new IOException("owned close failure");
        var raw=new InputStream(){public int read(){return 1;}public void close()throws IOException{throw original;}};
        var stream=observations.stream(raw,()->Facts.EMPTY);assertSame(original,assertThrows(IOException.class,stream::close));
        assertEquals(Outcome.UNKNOWN,recorder.entries.getFirst().outcome);assertEquals(Outcome.FAILED,recorder.entries.getLast().outcome);assertSame(original,recorder.entries.getLast().failure);
        var bulk=observations.stream(new InputStream(){public int read()throws IOException{throw original;}},()->Facts.EMPTY);
        assertSame(original,assertThrows(IOException.class,()->bulk.read(new byte[8],0,8)));bulk.close();
        var skipped=observations.stream(new InputStream(){public int read()throws IOException{throw original;}},()->Facts.EMPTY);
        assertSame(original,assertThrows(IOException.class,()->skipped.skip(2)));skipped.close();
        var noOp=new UploadObservations(UploadDiagnosticsPort.NOOP,DiagnosticContext.fresh());assertSame(raw,noOp.stream(raw,()->Facts.EMPTY));
    }
    @Test void callKeepsOriginalBusinessOutcomesAndUnknownWriteRetainsActualFailureNote() {
        var recorder=new RecordingUploadDiagnostics();var observations=new UploadObservations(recorder,DiagnosticContext.fresh());
        var unavailable=new IllegalStateException("original");assertSame(unavailable,assertThrows(IllegalStateException.class,()->observations.call(Operation.PROVIDER_SCAN,()->Facts.EMPTY,observer->{throw unavailable;})));
        var rejection=new DmFailure(422,"MALWARE_DETECTED");assertSame(rejection,assertThrows(DmFailure.class,()->observations.call(Operation.PROVIDER_SCAN,()->Facts.EMPTY,observer->{throw rejection;})));
        assertEquals(Outcome.REJECTED,recorder.entries.getLast().outcome);assertNull(recorder.entries.getLast().facts.error());
        var cancelled=new CancellationException();assertSame(cancelled,assertThrows(CancellationException.class,()->observations.call(Operation.CANCELLATION,()->Facts.EMPTY,observer->{throw cancelled;})));
        assertEquals(Outcome.CANCELLED,recorder.entries.getLast().outcome);
        var failure=new java.net.SocketTimeoutException("response lost");
        Boolean unknownResult=observations.call(Operation.PROVIDER_STORAGE_DELETE,()->Facts.EMPTY,observer->{observer.failure(failure);return false;},value->Outcome.UNKNOWN);assertFalse(unknownResult.booleanValue());
        assertEquals(Outcome.UNKNOWN,recorder.entries.getLast().outcome);assertSame(failure,recorder.entries.getLast().failure);
        assertEquals("done",observations.call(Operation.CLEANUP,()->Facts.EMPTY,observer->"done"));assertTrue(recorder.entries.getLast().facts.cleanupSettled());
        assertEquals("done",observations.call(Operation.PROVIDER_SCAN,()->{throw new IllegalStateException("diagnostic snapshot");},observer->"done"));assertEquals(2,recorder.rejected.get());
        assertNull(observations.call(Operation.CLEANUP,()->Facts.EMPTY,observer->null,result->Outcome.RETRYING));assertFalse(recorder.entries.getLast().facts.cleanupSettled());
        assertEquals("unchanged",observations.call(Operation.PROVIDER_SCAN,()->Facts.EMPTY,observer->"unchanged",value->{throw new IllegalStateException("diagnostic classifier");}));
        assertEquals(Outcome.UNKNOWN,recorder.entries.getLast().outcome);assertEquals(3,recorder.rejected.get());
        assertEquals("unchanged",observations.call(Operation.PROVIDER_SCAN,()->Facts.EMPTY,observer->"unchanged",value->null));assertEquals(4,recorder.rejected.get());
        observations.decision(Operation.RETRY,Outcome.REJECTED,new Facts(null,null,3,3,false,null,null));assertEquals(3,recorder.entries.getLast().facts.attempt());
        assertThrows(NullPointerException.class,()->new UploadObservations(null,DiagnosticContext.fresh()));
        assertThrows(NullPointerException.class,()->new UploadObservations(recorder,null));
        assertThrows(NullPointerException.class,()->observations.stream(null,()->Facts.EMPTY));
    }
}
