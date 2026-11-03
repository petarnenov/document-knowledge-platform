package org.documentknowledge.browserbff.adapters.inbound;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.concurrent.*;
import org.documentknowledge.browserbff.application.BrowserLogGateway;

/** Separate bounded HTTP waiter/serialization path, never the business executor or deadline thread. */
@Factory
@Requires(beans = BrowserLogGateway.class)
public final class BrowserLogResponseExecutor {
    public static final String NAME = "bff-log-responses";
    @Bean(preDestroy = "shutdownNow") @Singleton @Named(NAME)
    public ExecutorService executor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name(NAME + "-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
}
