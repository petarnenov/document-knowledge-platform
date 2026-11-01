package org.documentknowledge.documentmanagement.application.sharing;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.documentmanagement.application.DmFailure;

/** Shared strict+eligibility single-process actor1/global16 pool, no queue. */
public final class SharingAdmission {
    private final ReentrantLock gate=new ReentrantLock();
    private final Set<Actor> actors=new HashSet<>();
    public Permit acquire(Actor actor) {
        if(!gate.tryLock())throw new DmFailure(503,"UNAVAILABLE");
        try { if(actors.size()>=16||!actors.add(actor))throw new DmFailure(503,"UNAVAILABLE");return new Permit(actor); }
        finally { gate.unlock(); }
    }
    public final class Permit implements AutoCloseable {
        private final Actor actor;private boolean closed;
        private Permit(Actor actor){this.actor=actor;}
        public void close(){gate.lock();try{if(!closed){actors.remove(actor);closed=true;}}finally{gate.unlock();}}
    }
}
