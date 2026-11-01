package org.documentknowledge.knowledgebase.adapters.inbound;

import io.micronaut.http.*;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.documentknowledge.knowledgebase.application.Deadline;
import org.documentknowledge.knowledgebase.domain.KbFailure;

/** Current validation, body construction, encoding and HTTP construction share one 500 ms child. */
@Singleton
public class KnowledgeFinalResponse {
    private final ThreadPoolExecutor encoders=new ThreadPoolExecutor(0,16,30,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("kb-response-",0).factory());
    public HttpResponse<?> finish(Deadline phase,Runnable current,Supplier<Object> body,int status){
        Future<HttpResponse<?>> task;
        try{phase.check();task=encoders.submit(()->{phase.check();current.run();phase.check();Object value=body.get();phase.check();byte[] bytes=encode(value);phase.check();HttpResponse<?> response=HttpResponse.status(HttpStatus.valueOf(status)).body(bytes).contentType(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store");phase.check();return response;});}
        catch(RejectedExecutionException unavailable){throw KbFailure.unavailable();}
        try{return task.get(phase.remainingNanos(),TimeUnit.NANOSECONDS);}
        catch(InterruptedException cancelled){Thread.currentThread().interrupt();phase.cancel();task.cancel(true);throw KbFailure.unavailable();}
        catch(TimeoutException expired){phase.cancel();task.cancel(true);throw KbFailure.unavailable();}
        catch(ExecutionException failed){if(failed.getCause() instanceof KbFailure safe)throw safe;throw KbFailure.unavailable();}
        catch(RuntimeException expired){task.cancel(true);throw expired;}
    }
    protected byte[] encode(Object value){return KnowledgeWire.encode(value);}
    @PreDestroy public void close(){encoders.shutdownNow();}
}
