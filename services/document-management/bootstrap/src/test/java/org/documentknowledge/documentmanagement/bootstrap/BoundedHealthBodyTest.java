package org.documentknowledge.documentmanagement.bootstrap;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BoundedHealthBodyTest {
 static class Subscription implements Flow.Subscription {long requested;boolean cancelled;public void request(long n){requested+=n;}public void cancel(){cancelled=true;}}
 @Test void segmentedBodyCompletesAndDuplicateSubscriptionIsCancelled()throws Exception {var body=new BoundedHealthBody();var subscription=new Subscription();body.onSubscribe(subscription);var duplicate=new Subscription();body.onSubscribe(duplicate);assertTrue(duplicate.cancelled);body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3})));body.onComplete();assertEquals(2,subscription.requested);assertArrayEquals(new byte[]{1,2,3},body.getBody().toCompletableFuture().get());}
 @Test void OversizeAndPeerFailureNeverReturnPartialHealth() {var body=new BoundedHealthBody();var subscription=new Subscription();body.onSubscribe(subscription);body.onNext(List.of(ByteBuffer.wrap(new byte[65])));assertTrue(subscription.cancelled);assertThrows(CompletionException.class,()->body.getBody().toCompletableFuture().join());var failed=new BoundedHealthBody();failed.onError(new IllegalStateException("peer down"));assertThrows(CompletionException.class,()->failed.getBody().toCompletableFuture().join());}
}
