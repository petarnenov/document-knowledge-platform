package org.documentknowledge.browserbff.adapters.outbound;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/** One daemon deadline worker; cancelled stream tasks must not retain their owners. */
final class StreamDeadlineTimer {
    private StreamDeadlineTimer() { }

    static ScheduledThreadPoolExecutor create() {
        var timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "dkp-bff-stream-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
