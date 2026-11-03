package org.documentknowledge.documentmanagement.adapters.outbound.verification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.documentmanagement.application.upload.UploadDiagnosticsPort;

/** Raw boundary assertions only; never selected by production composition. */
final class VerificationObservation implements UploadDiagnosticsPort.FailureObserver {
    final List<Throwable> failures=new CopyOnWriteArrayList<>();
    final List<Integer> statuses=new CopyOnWriteArrayList<>();
    final AtomicInteger cancellations=new AtomicInteger();
    public void failure(Throwable failure){failures.add(failure);}
    public void status(int status){statuses.add(status);}
    public void cancelRequested(){cancellations.incrementAndGet();}
}
