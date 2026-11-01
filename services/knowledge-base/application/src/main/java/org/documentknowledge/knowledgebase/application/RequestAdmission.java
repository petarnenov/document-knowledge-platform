package org.documentknowledge.knowledgebase.application;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.documentknowledge.knowledgebase.domain.KbFailure;
import org.documentknowledge.knowledgebase.domain.KbModels.Principal;

/** Atomic single-process actor1/global16 admission. Contention fails immediately; no queue. */
public final class RequestAdmission {
    private final Set<Principal> actors=new HashSet<>();
    private final ReentrantLock gate=new ReentrantLock();
    public Permit acquire(Principal actor) {
        if(!gate.tryLock()) throw KbFailure.unavailable();
        try {
            if(actors.size()>=16 || !actors.add(actor)) throw KbFailure.unavailable();
            return new Permit(actor);
        } finally { gate.unlock(); }
    }
    public final class Permit implements AutoCloseable {
        private final Principal actor;
        private boolean closed;
        private Permit(Principal actor) { this.actor=actor; }
        @Override public void close() {
            gate.lock();
            try { if(!closed) { actors.remove(actor); closed=true; } }
            finally { gate.unlock(); }
        }
    }
}
