package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.documentknowledge.documentmanagement.application.upload.UploadCleanupService;

/** One bounded local sweep; durable database claims arbitrate across replicas. */
@Singleton
@Requires(property="dkp.dm.upload.enabled",value="true")
final class UploadCleanupScheduler {
    private final UploadCleanupService cleanup;
    private final UUID executor=UUID.randomUUID();
    private final AtomicBoolean running=new AtomicBoolean();
    UploadCleanupScheduler(UploadCleanupService cleanup){this.cleanup=cleanup;}
    @Scheduled(fixedDelay="10s",initialDelay="10s")
    void sweep(){if(!running.compareAndSet(false,true))return;try{cleanup.sweep(executor,50);}catch(RuntimeException unavailable){/* Persisted work remains charged for the next authorized sweep. */}finally{running.set(false);}}
}
