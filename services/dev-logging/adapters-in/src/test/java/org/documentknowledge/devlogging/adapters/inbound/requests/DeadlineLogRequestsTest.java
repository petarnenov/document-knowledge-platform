package org.documentknowledge.devlogging.adapters.inbound.requests;

import java.util.ArrayList;
import java.util.concurrent.*;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DeadlineLogRequestsTest {
    @Test void suppliedAdmissionBudgetIsNotReplaced() {
        try (var requests = new DeadlineLogRequests()) {
            var admitted = org.documentknowledge.devlogging.application.RequestDeadline.start();
            assertSame(admitted, requests.submit(admitted, deadline -> deadline).await());
        }
    }
    void fails(LogFailure.Kind kind, org.junit.jupiter.api.function.Executable work) {
        assertEquals(kind, assertThrows(LogFailure.class, work).kind());
    }
    static void holdDespiteInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try { latch.await(); } catch (InterruptedException signal) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    static void drained(DeadlineLogRequests requests) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (requests.health().activeWorkers() != 0 && System.nanoTime() < end) Thread.sleep(5);
        assertEquals(0, requests.health().activeWorkers());
        assertEquals(0, requests.health().pendingAlarms());
    }

    @Test void successfulWorkAndSanitizedFailuresHaveOneResponseConsumer() throws Exception {
        try (var requests = new DeadlineLogRequests()) {
            var success = requests.submit(deadline -> { deadline.requireTime(); return "committed"; });
            assertEquals("committed", success.await());
            assertThrows(IllegalStateException.class, success::await);
            fails(LogFailure.Kind.FORBIDDEN, () -> requests.submit(deadline -> { throw new LogFailure(LogFailure.Kind.FORBIDDEN); }).await());
            var unknown = requests.submit(deadline -> { throw new IllegalArgumentException("PRIVATE_SECRET"); });
            var failure = assertThrows(LogFailure.class, unknown::await);
            assertEquals(LogFailure.Kind.UNAVAILABLE, failure.kind());
            assertFalse(failure.getMessage().contains("PRIVATE_SECRET"));
            assertNull(failure.getCause());
            assertThrows(NullPointerException.class, () -> requests.submit(null));
            drained(requests);
        }
    }

    @Test void allThirtyTwoDeadlinesRespondWithoutReleasingBusyWorkers() throws Exception {
        var original = org.documentknowledge.devlogging.application.RequestDeadline.start();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(32);
        try (var requests = new DeadlineLogRequests()) {
            try {
                var handles = new ArrayList<DeadlineLogRequests.Request<String>>();
                for (int i = 0; i < 32; i++) handles.add(requests.submit(deadline -> {
                    entered.countDown(); holdDespiteInterruption(release); return "late success";
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(32, requests.health().activeWorkers());
                fails(LogFailure.Kind.RATE_LIMITED, () -> requests.submit(deadline -> "must not run").await());
                handles.getFirst().cancel();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
                while (handles.stream().anyMatch(handle -> !handle.responseDecided()) && System.nanoTime() < end) Thread.sleep(5);
                assertTrue(handles.stream().allMatch(DeadlineLogRequests.Request::responseDecided),
                    "All timer decisions must complete independently before any response await");
                assertEquals(0, requests.health().pendingAlarms());
                for (var handle : handles) fails(LogFailure.Kind.UNAVAILABLE, handle::await);
                assertEquals(32, requests.health().activeWorkers(), "Deadline/response cancellation must not recycle workers");
                assertEquals(32, requests.health().expiredWorkers());
                fails(LogFailure.Kind.UNAVAILABLE, () -> requests.submit(original, deadline -> {
                    fail("An expired route must never start another operation"); return "unreachable";
                }).await());
                fails(LogFailure.Kind.RATE_LIMITED, () -> requests.submit(deadline -> "must still not run").await());
                // In-memory health remains available while every ingestion worker is occupied.
                assertFalse(requests.health().closed());
                release.countDown();
                drained(requests);
                assertEquals("recovered", requests.submit(deadline -> "recovered").await());
                for (var handle : handles) assertThrows(IllegalStateException.class, handle::await);
            } finally { release.countDown(); }
        }
    }

    @Test void shutdownReportsActualOccupancyUntilUninterruptibleWorkExits() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        try (var requests = new DeadlineLogRequests()) {
            try {
                var handle = requests.submit(deadline -> { entered.countDown(); holdDespiteInterruption(release); return "late"; });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                requests.close();
                fails(LogFailure.Kind.UNAVAILABLE, handle::await);
                assertTrue(requests.health().closed());
                assertEquals(1, requests.health().activeWorkers());
                fails(LogFailure.Kind.UNAVAILABLE, () -> requests.submit(deadline -> "never").await());
            } finally { release.countDown(); drained(requests); }
        }
    }

    @Test void interruptedResponseConsumerDoesNotReleaseItsActualWorker() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        try (var requests = new DeadlineLogRequests()) {
            try {
                var handle = requests.submit(deadline -> { entered.countDown(); holdDespiteInterruption(release); return "late"; });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                Thread.currentThread().interrupt();
                try {
                    fails(LogFailure.Kind.UNAVAILABLE, handle::await);
                    assertTrue(Thread.currentThread().isInterrupted());
                } finally { Thread.interrupted(); }
                assertEquals(1, requests.health().activeWorkers());
            } finally { release.countDown(); drained(requests); }
        }
    }
}
