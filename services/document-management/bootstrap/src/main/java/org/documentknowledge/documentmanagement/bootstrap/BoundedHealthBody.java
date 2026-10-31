package org.documentknowledge.documentmanagement.bootstrap;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Dependency health is bounded even when a peer advertises an unbounded body. */
final class BoundedHealthBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody(){return result;}
    @Override public void onSubscribe(Flow.Subscription value){if(subscription!=null){value.cancel();return;}subscription=value;value.request(1);}
    @Override public void onNext(List<ByteBuffer> buffers){try{for(var buffer:buffers){if(buffer.remaining()>64-bytes.size())throw new IllegalStateException("Health response limit");while(buffer.hasRemaining())bytes.write(buffer.get());}subscription.request(1);}catch(RuntimeException failure){subscription.cancel();result.completeExceptionally(failure);}}
    @Override public void onError(Throwable failure){result.completeExceptionally(failure);}
    @Override public void onComplete(){result.complete(bytes.toByteArray());}
}
