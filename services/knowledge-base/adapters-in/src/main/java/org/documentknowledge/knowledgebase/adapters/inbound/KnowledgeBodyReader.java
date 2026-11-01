package org.documentknowledge.knowledgebase.adapters.inbound;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.io.*;
import java.util.concurrent.*;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import io.micronaut.http.exceptions.ContentLengthExceededException;

/** No queue: a stalled transport cannot consume an unbounded number of request workers. */
@Singleton
public final class KnowledgeBodyReader {
    private final ThreadPoolExecutor reads=new ThreadPoolExecutor(0,16,30,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("kb-body-",0).factory());
    public byte[] read(InputStream input,Deadline deadline){
        Future<byte[]> task;
        try { task=reads.submit(()->{try(input){return input.readNBytes(KnowledgeWire.REQUEST_LIMIT+1);}catch(IOException failure){throw failure(failure);}}); }
        catch(RejectedExecutionException unavailable){throw KbFailure.unavailable();}
        try {return task.get(deadline.remainingNanos(),TimeUnit.NANOSECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();deadline.cancel();task.cancel(true);throw KbFailure.unavailable();}
        catch(TimeoutException expired){deadline.cancel();task.cancel(true);throw KbFailure.unavailable();}
        catch(ExecutionException failure){if(failure.getCause() instanceof KbFailure typed)throw typed;throw failure(failure.getCause());}
        catch(RuntimeException expired){task.cancel(true);throw expired;}
    }
    private static KbFailure failure(Throwable failure){for(int n=0;failure!=null&&n<8;n++,failure=failure.getCause())if(failure instanceof ContentLengthExceededException)return new KbFailure(413,"PAYLOAD_TOO_LARGE");return KnowledgeWire.invalid();}
    @PreDestroy public void close(){reads.shutdownNow();}
}
