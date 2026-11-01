package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.io.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Complete only after bounded bytes arrive; the caller independently bounds the whole exchange. */
final class BoundedInspectionResponse implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody(){return body;}
    @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
    @Override public void onNext(List<ByteBuffer> buffers) {
        for(ByteBuffer buffer:buffers) {
            if(bytes.size()+buffer.remaining()>4096){subscription.cancel();body.completeExceptionally(new IOException("Inspection response limit"));return;}
            byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable failure){body.completeExceptionally(failure);}
    @Override public void onComplete(){body.complete(bytes.toByteArray());}
}
