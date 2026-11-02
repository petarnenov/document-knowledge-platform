package org.documentknowledge.devlogging.adapters.inbound.requests;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.concurrent.*;

/** Dedicated HTTP response/offload capacity, independent of the dependency-worker ownership. */
@Factory
public final class LogResponseExecutor {
    public static final String NAME = "dkp-log-responses";

    @Bean(preDestroy = "shutdownNow")
    @Singleton
    @Named(NAME)
    public ExecutorService executor() {
        return new ThreadPoolExecutor(32, 32, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
            var thread = new Thread(runnable, NAME);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
