package org.documentknowledge.documentmanagement.bootstrap;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadCleanupSchedulerTest {
 @Test void sweepsDoNotOverlapAndFailureReleasesOnlyLocalAdmission()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  DocumentUnitOfWork transactions=new DocumentUnitOfWork(){public <T>T execute(Function<DocumentRepository,T> work){calls.incrementAndGet();entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}throw new DmFailure(503,"UNAVAILABLE");}};
  CleanupStoragePort storage=new CleanupStoragePort(){public ObjectIdentity put(ObjectKey k,Path f,long s,String h){throw new AssertionError();}public InputStream open(ObjectIdentity o){throw new AssertionError();}public ObjectIdentity copy(ObjectIdentity o,ObjectKey k){throw new AssertionError();}public List<ObjectIdentity> versions(ObjectKey k){throw new AssertionError();}public UploadStoragePort.DeletionResult deleteOnce(DeletionFence f){throw new AssertionError();}};
  var scheduler=new UploadCleanupScheduler(new UploadCleanupService(transactions,storage,Clock.systemUTC()));
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){var first=executor.submit(scheduler::sweep);assertTrue(entered.await(5,TimeUnit.SECONDS));scheduler.sweep();assertEquals(1,calls.get());release.countDown();first.get(5,TimeUnit.SECONDS);scheduler.sweep();assertEquals(2,calls.get());}
 }
}
