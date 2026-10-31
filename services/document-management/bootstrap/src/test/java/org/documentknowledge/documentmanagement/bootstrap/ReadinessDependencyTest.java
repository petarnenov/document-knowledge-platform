package org.documentknowledge.documentmanagement.bootstrap;

import org.documentknowledge.documentmanagement.application.Readiness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessDependencyTest {
    @Test void admissionAndDependencyHealthMustBothAllowReadiness() {
        var dependency=new java.util.concurrent.atomic.AtomicBoolean(true);var readiness=new Readiness(dependency::get);
        assertTrue(readiness.isAcceptingWork());readiness.markUnavailable();assertFalse(readiness.isAcceptingWork());readiness.markAvailable();assertTrue(readiness.isAcceptingWork());
        dependency.set(false);assertFalse(readiness.isAcceptingWork());readiness.markAvailable();assertFalse(readiness.isAcceptingWork());
        assertFalse(new Readiness(()->{throw new IllegalStateException("dependency down");}).isAcceptingWork());
        assertTrue(new Readiness().isAcceptingWork());assertThrows(NullPointerException.class,()->new Readiness(null));
    }
}
