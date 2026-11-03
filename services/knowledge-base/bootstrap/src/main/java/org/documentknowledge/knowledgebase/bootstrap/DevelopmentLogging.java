package org.documentknowledge.knowledgebase.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import org.documentknowledge.loggingclient.ApplicationLogRuntime;

/** Optional bootstrap composition; never supplies domain or tenant authority. */
@Context
@Requires(property="dkp.logging.producer-config")
public final class DevelopmentLogging {
    private final ApplicationLogRuntime runtime;
    public DevelopmentLogging(@Value("${dkp.logging.producer-config}") String configuration) throws IOException {
        runtime=ApplicationLogRuntime.open("knowledge-base",Application.class.getResourceAsStream(ApplicationLogRuntime.RESOURCE),Path.of(configuration));
    }
    @EventListener public void started(ServerStartupEvent event) {runtime.started();}
    @EventListener public void stopping(ServerShutdownEvent event) {runtime.stopping();}
    public ApplicationLogRuntime runtime() {return runtime;}
    @PreDestroy public void close() {runtime.close();}
}
