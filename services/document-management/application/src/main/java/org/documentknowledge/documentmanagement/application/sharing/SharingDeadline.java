package org.documentknowledge.documentmanagement.application.sharing;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.documentknowledge.documentmanagement.application.DmFailure;

/** Request ownership begins before authentication and never renews on a late callback. */
public final class SharingDeadline {
    private final LongSupplier ticker;
    private final long expires;
    private final SharingDeadline parent;
    private volatile boolean cancelled;
    public SharingDeadline(LongSupplier ticker,Duration maximum) { this.ticker=ticker;this.expires=Math.addExact(ticker.getAsLong(),maximum.toNanos());parent=null; }
    private SharingDeadline(SharingDeadline parent,Duration cap) { this.parent=parent;ticker=parent.ticker;expires=Math.min(parent.expires,Math.addExact(ticker.getAsLong(),cap.toNanos())); }
    public static SharingDeadline request() { return new SharingDeadline(System::nanoTime,Duration.ofMillis(1500)); }
    public SharingDeadline child(Duration cap) { check();return new SharingDeadline(this,cap); }
    public void cancel() { cancelled=true; }
    public long remainingNanos() { check();long value=expires-ticker.getAsLong();if(value<=0)throw unavailable();return value; }
    public void check() { if(parent!=null)parent.check();if(cancelled||Thread.currentThread().isInterrupted()||ticker.getAsLong()>=expires)throw unavailable(); }
    private static DmFailure unavailable() { return new DmFailure(503,"UNAVAILABLE"); }
}
