package org.documentknowledge.browserbff.adapters.outbound;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.http.exceptions.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TransferLengthFailureTest {
    @Test void directAndWrappedTypedTotalLengthFailurePreserve413AndCloseOnlyOwnedSource()throws Exception {
        for(Throwable typed:List.of(new ContentLengthExceededException(100000000,100000001),
            new IOException("private message must never be classified",new IOException(new ContentLengthExceededException(100000000,100000001))))) {
            var closed=new AtomicBoolean();
            try(var stream=new BoundedStream(source(typed,closed),-1,()->{})) {
                assertThrows(Exception.class,()->stream.read(new byte[1]));
                assertNotNull(stream.rejection());assertEquals(413,stream.rejection().status());
                assertEquals("PAYLOAD_TOO_LARGE",stream.rejection().code());assertTrue(closed.get());
            }
        }
    }
    @Test void queueOverflowTransportFailureMessagesAndCyclicOrDeepChainsRemainUnavailable()throws Exception {
        Throwable cyclic=new IOException("private"){@Override public synchronized Throwable getCause(){return this;}};
        Throwable deep=new ContentLengthExceededException(100000000,100000001);
        for(int i=0;i<8;i++)deep=new IOException(deep);
        for(Throwable other:List.of(new BufferLengthExceededException(131072,131073),
            new IOException("ContentLengthExceededException PAYLOAD_TOO_LARGE private URL"),
            new IOException("private dependency"),cyclic,deep)) {
            try(var stream=new BoundedStream(source(other,new AtomicBoolean()),-1,()->{})) {
                assertThrows(Exception.class,()->stream.read(new byte[1]));assertNull(stream.rejection());
            }
        }
    }
    @Test void sourceCloseFailureDoesNotEraseTheTypedRejection()throws Exception {
        var source=new InputStream(){public int read(){throw new ContentLengthExceededException(100000000,100000001);}
            public void close()throws IOException{throw new IOException("private cleanup");}};
        var stream=new BoundedStream(source,-1,()->{});
        var failure=assertThrows(ContentLengthExceededException.class,stream::read);
        assertEquals(1,failure.getSuppressed().length);assertEquals(413,stream.rejection().status());stream.close();
    }
    static InputStream source(Throwable failure,AtomicBoolean closed) {
        return new InputStream(){public int read()throws IOException{
            if(failure instanceof IOException io)throw io;throw (RuntimeException)failure;
        }
        public void close(){closed.set(true);}};
    }
}
