package org.documentknowledge.knowledgesearch.application;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process readiness only; it is never an authorization decision. */
public final class Readiness {
    private final AtomicBoolean acceptingWork = new AtomicBoolean(true);

    public boolean isAcceptingWork() {
        return acceptingWork.get();
    }

    public void markUnavailable() {
        acceptingWork.set(false);
    }

    public void markAvailable() {
        acceptingWork.set(true);
    }
}
