package org.documentknowledge.devlogging.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.devlogging.domain.LogFailure;

/** One latest trusted observation, never a database write or recursive logging dependency. */
public final class ObservedVolumeAdmission implements StorageAdmission {
    private static final long MIN_FREE = 2L * 1024 * 1024 * 1024;
    private static final long MAX_WIRE_INTEGER = 9007199254740991L;
    private final String volumeIdentity;
    private final Clock serverClock;
    private final AtomicReference<Observation> latest = new AtomicReference<>();

    public ObservedVolumeAdmission(String volumeIdentity, Clock serverClock) {
        if (volumeIdentity == null || !volumeIdentity.matches("[0-9a-f]{64}")) throw new LogFailure(LogFailure.Kind.INVALID);
        this.volumeIdentity = volumeIdentity;
        this.serverClock = Objects.requireNonNull(serverClock);
    }

    /** Inbound adapter MUST authenticate the separate observer credential before calling this port. */
    public void observe(String observedVolume, long freeBytes, long totalBytes) {
        if (!volumeIdentity.equals(observedVolume)) throw new LogFailure(LogFailure.Kind.FORBIDDEN);
        if (freeBytes < 0 || totalBytes < 1 || freeBytes > totalBytes || totalBytes > MAX_WIRE_INTEGER) {
            observationFailed();
            throw new LogFailure(LogFailure.Kind.INVALID);
        }
        latest.set(new Observation(freeBytes, totalBytes, serverClock.instant()));
    }

    /** Only a trusted observer failure may invalidate health; producer requests cannot call this. */
    public void observationFailed() { latest.set(null); }

    @Override
    public void requireWritable() {
        var observation = latest.get();
        if (observation == null) throw unavailable();
        Instant now = serverClock.instant();
        long relativeMinimum = observation.totalBytes() / 10 + (observation.totalBytes() % 10 == 0 ? 0 : 1);
        if (now.isBefore(observation.receivedAt())
                || Duration.between(observation.receivedAt(), now).compareTo(Duration.ofSeconds(5)) > 0
                || observation.freeBytes() < MIN_FREE || observation.freeBytes() < relativeMinimum) throw unavailable();
    }

    private static LogFailure unavailable() { return new LogFailure(LogFailure.Kind.UNAVAILABLE); }
    private record Observation(long freeBytes, long totalBytes, Instant receivedAt) { }
}
