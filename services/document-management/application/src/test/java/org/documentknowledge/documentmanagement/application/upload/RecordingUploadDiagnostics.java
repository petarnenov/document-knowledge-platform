package org.documentknowledge.documentmanagement.application.upload;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.documentknowledge.documentmanagement.application.DiagnosticContext;

/** Unit observer only. Raw exceptions are inspected here, never an assertion of persisted production logs. */
public final class RecordingUploadDiagnostics implements UploadDiagnosticsPort {
    public static final class Entry {
        public final DiagnosticContext context;
        public final Operation operation;
        public final Facts initial;
        public volatile Throwable failure;
        public volatile Integer status;
        public volatile boolean cancelRequested;
        public volatile Outcome outcome;
        public volatile Facts facts;
        public final AtomicInteger completions=new AtomicInteger();
        Entry(DiagnosticContext context,Operation operation,Facts initial){this.context=context;this.operation=operation;this.initial=initial;}
    }
    public final List<Entry> entries=new CopyOnWriteArrayList<>();
    public final AtomicInteger rejected=new AtomicInteger();
    public Observation started(DiagnosticContext context,Operation operation,Facts facts) {
        var entry=new Entry(context,operation,facts);entries.add(entry);
        return new Observation() {
            public synchronized void failure(Throwable failure){if(entry.failure==null)entry.failure=failure;}
            public void status(int status){entry.status=status;}
            public void cancelRequested(){entry.cancelRequested=true;}
            public void completed(Outcome outcome,Throwable failure,Facts facts){failure(failure);entry.facts=facts;entry.outcome=outcome;entry.completions.incrementAndGet();}
        };
    }
    public void rejected(){rejected.incrementAndGet();}
}
