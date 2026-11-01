package org.documentknowledge.knowledgebase.application;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.documentknowledge.knowledgebase.domain.KbFailure;

/** Monotonic request ownership. A late result never renews its owner's admission. */
public final class Deadline {
    private final LongSupplier ticker;
    private final long expires;
    private volatile boolean cancelled;
    private final Deadline parent;
    public Deadline(LongSupplier ticker,Duration duration) { this.ticker=ticker; expires=Math.addExact(ticker.getAsLong(),duration.toNanos()); parent=null; }
    private Deadline(LongSupplier ticker,long expires,Deadline parent) { this.ticker=ticker; this.expires=expires; this.parent=parent; }
    public static Deadline request() { return new Deadline(System::nanoTime,Duration.ofSeconds(8)); }
    public Deadline child(Duration cap) { check(); return new Deadline(ticker,Math.min(expires,Math.addExact(ticker.getAsLong(),cap.toNanos())),this); }
    public long remainingNanos() { check(); long remaining=expires-ticker.getAsLong(); if(remaining<=0) throw KbFailure.unavailable(); return remaining; }
    public void check() { if(parent!=null) parent.check(); if(cancelled || Thread.currentThread().isInterrupted() || ticker.getAsLong()>=expires) throw KbFailure.unavailable(); }
    public void cancel() { cancelled=true; }
}
