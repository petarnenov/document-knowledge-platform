package org.documentknowledge.documentmanagement.adapters.outbound.storage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.documentmanagement.application.upload.UploadDiagnosticsPort;

/** Test-only raw boundary recorder; production observers sanitize synchronously instead. */
final class ProviderObservation implements UploadDiagnosticsPort.FailureObserver {
    final List<Throwable> failures=new CopyOnWriteArrayList<>();
    final List<Integer> statuses=new CopyOnWriteArrayList<>();
    final AtomicInteger cancellations=new AtomicInteger();
    public void failure(Throwable failure){failures.add(failure);}
    public void status(int status){statuses.add(status);}
    public void cancelRequested(){cancellations.incrementAndGet();}
}
