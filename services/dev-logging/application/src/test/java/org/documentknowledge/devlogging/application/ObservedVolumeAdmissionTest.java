package org.documentknowledge.devlogging.application;

import java.time.*;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObservedVolumeAdmissionTest {
    static final String VOLUME = "a".repeat(64);
    static final long GIB = 1024L * 1024 * 1024;
    final TestClock clock = new TestClock();
    final ObservedVolumeAdmission admission = new ObservedVolumeAdmission(VOLUME, clock);

    void rejects() { assertEquals(LogFailure.Kind.UNAVAILABLE, assertThrows(LogFailure.class, admission::requireWritable).kind()); }

    @Test void missingAndFailedObservationsFailClosed() {
        rejects();
        admission.observe(VOLUME, 4 * GIB, 8 * GIB);
        admission.requireWritable();
        admission.observationFailed();
        rejects();
    }

    @Test void absoluteAndRelativeFreeSpaceThresholdsBothApply() {
        admission.observe(VOLUME, 2 * GIB - 1, 4 * GIB); rejects();
        admission.observe(VOLUME, 2 * GIB, 4 * GIB); admission.requireWritable();
        admission.observe(VOLUME, 2 * GIB, 20 * GIB + 1); rejects();
        admission.observe(VOLUME, 2 * GIB + 1, 20 * GIB + 1); admission.requireWritable();
        admission.observe(VOLUME, 2 * GIB, 20 * GIB); admission.requireWritable();
    }

    @Test void freshnessUsesServerReceiveTimeAndFailsOnClockReversal() {
        admission.observe(VOLUME, 4 * GIB, 8 * GIB);
        var received = clock.now;
        clock.now = received.plusSeconds(5); admission.requireWritable();
        clock.now = received.plusSeconds(5).plusNanos(1); rejects();
        clock.now = received.minusNanos(1); rejects();
        clock.now = received.plusSeconds(20);
        admission.observe(VOLUME, 4 * GIB, 8 * GIB); admission.requireWritable();
    }

    @Test void wrongVolumeCannotReplaceGoodObservation() {
        admission.observe(VOLUME, 4 * GIB, 8 * GIB);
        assertEquals(LogFailure.Kind.FORBIDDEN, assertThrows(LogFailure.class,
            () -> admission.observe("b".repeat(64), 0, 1)).kind());
        admission.requireWritable();
    }

    @Test void invalidTrustedMeasurementsInvalidatePreviouslyGoodHealth() {
        for (var values : new long[][] {{-1, 1}, {0, 0}, {2, 1}, {1, 9007199254740992L}}) {
            admission.observe(VOLUME, 4 * GIB, 8 * GIB);
            assertEquals(LogFailure.Kind.INVALID, assertThrows(LogFailure.class,
                () -> admission.observe(VOLUME, values[0], values[1])).kind());
            rejects();
        }
        admission.observe(VOLUME, 9007199254740991L, 9007199254740991L);
        admission.requireWritable();
    }

    @Test void configurationRejectsUnboundedOrMissingVolumeIdentity() {
        for (var id : new String[] {"", "a".repeat(63), "A".repeat(64), "a".repeat(65)}) {
            assertThrows(LogFailure.class, () -> new ObservedVolumeAdmission(id, clock));
        }
        assertThrows(LogFailure.class, () -> new ObservedVolumeAdmission(null, clock));
        assertThrows(NullPointerException.class, () -> new ObservedVolumeAdmission(VOLUME, null));
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-02T15:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
