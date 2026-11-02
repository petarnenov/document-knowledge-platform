package org.documentknowledge.observability;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class EventIdentityTest {
    private static final String SHA = "a".repeat(40);
    private static final String SOURCE = "b".repeat(64);
    private static final UUID NIL = new UUID(0, 0);

    private static UUID id(long value) { return new UUID(1, value); }

    private static BuildIdentity build(long build, String component) {
        return new BuildIdentity(id(build), component, SHA, SOURCE);
    }

    private static EventMetadata event(long eventId, ExecutionIdentity execution, EventCode code, UUID correlation) {
        return new EventMetadata(id(eventId), execution, Instant.parse("2026-11-02T14:00:00Z"),
            Severity.DEBUG, code, correlation, new TraceContext(null, null));
    }

    @Test
    void sameSourceCompilationsRemainDistinctAndRepeatedRunsRetainTheirArtifact() {
        var firstBuild = build(1, "document-management");
        var secondBuild = build(2, "document-management");
        var firstRun = new ExecutionIdentity(firstBuild, id(3), id(4));
        var nextRun = new ExecutionIdentity(firstBuild, id(5), id(6));
        var otherBuildRun = new ExecutionIdentity(secondBuild, id(7), id(8));

        assertEquals(firstBuild.gitSha(), secondBuild.gitSha());
        assertEquals(firstBuild.sourceFingerprint(), secondBuild.sourceFingerprint());
        assertNotEquals(firstBuild.buildId(), secondBuild.buildId());
        assertSame(firstBuild, firstRun.build());
        assertSame(firstBuild, nextRun.build());
        assertNotEquals(firstRun.runId(), nextRun.runId());
        assertEquals(secondBuild.buildId(), otherBuildRun.build().buildId());
        assertEquals(id(4), firstRun.instanceId());
    }

    @Test
    void frontendBackendAndInfrastructureCanJoinAnOperationWithoutReplacingProvenance() {
        UUID operation = id(90);
        var frontend = event(20, new ExecutionIdentity(build(1, "admin-portal"), id(3), id(4)),
            EventCode.OPERATION_STARTED, operation);
        var backend = event(21, new ExecutionIdentity(build(2, "document-management"), id(5), id(6)),
            EventCode.DEPENDENCY_FAILED, operation);
        var infrastructure = event(22, new ExecutionIdentity(build(7, "runtime-observer"), id(8), id(9)),
            EventCode.PRODUCER_DELIVERY_DEGRADED, operation);

        for (var value : Arrays.asList(frontend, backend, infrastructure)) {
            assertEquals(operation, value.correlationId());
            assertEquals(Severity.DEBUG, value.severity());
            assertEquals(Instant.parse("2026-11-02T14:00:00Z"), value.timestamp());
            assertNotNull(value.trace());
        }
        assertNotEquals(frontend.execution().build().component(), backend.execution().build().component());
        assertNotEquals(backend.execution().build().buildId(), infrastructure.execution().build().buildId());
        assertNotEquals(frontend.eventId(), backend.eventId());
        assertEquals("dependency.failed", backend.eventCode().wireName());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "AdminPortal", "component with space", "a/b", "x\nsecret", ".hidden"})
    void malformedComponentDoesNotEnterBuildMetadata(String component) {
        assertThrows(RuntimeException.class, () -> new BuildIdentity(id(1), component, SHA, SOURCE));
    }

    @Test
    void exactLengthIdentifiersAreAcceptedAndOversizedIdentifiersAreRejected() {
        assertEquals(96, build(1, "a".repeat(96)).component().length());
        assertThrows(IllegalArgumentException.class, () -> build(1, "a".repeat(97)));
        for (String invalid : new String[] {"", "a".repeat(39), "a".repeat(41), "A".repeat(40), "g".repeat(40)}) {
            assertThrows(IllegalArgumentException.class, () -> new BuildIdentity(id(1), "backend", invalid, SOURCE));
        }
        for (String invalid : new String[] {"", "b".repeat(63), "b".repeat(65), "B".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> new BuildIdentity(id(1), "backend", SHA, invalid));
        }
        assertThrows(NullPointerException.class, () -> new BuildIdentity(id(1), "backend", null, SOURCE));
        assertThrows(NullPointerException.class, () -> new BuildIdentity(id(1), "backend", SHA, null));
    }

    @Test
    void nilAndMissingIdsAndRunReuseCannotBeMistakenForRealIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new BuildIdentity(NIL, "backend", SHA, SOURCE));
        assertThrows(NullPointerException.class, () -> new BuildIdentity(null, "backend", SHA, SOURCE));
        var artifact = build(1, "backend");
        assertThrows(NullPointerException.class, () -> new ExecutionIdentity(null, id(2), id(3)));
        assertThrows(NullPointerException.class, () -> new ExecutionIdentity(artifact, null, id(3)));
        assertThrows(NullPointerException.class, () -> new ExecutionIdentity(artifact, id(2), null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionIdentity(artifact, NIL, id(3)));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionIdentity(artifact, id(2), NIL));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionIdentity(artifact, artifact.buildId(), id(3)));
    }

    @Test
    void incompleteEventMetadataCannotMasqueradeAsAnOperationRecord() {
        var execution = new ExecutionIdentity(build(1, "backend"), id(2), id(3));
        var timestamp = Instant.now();
        var trace = new TraceContext(null, null);
        assertThrows(IllegalArgumentException.class, () -> new EventMetadata(NIL, execution, timestamp, Severity.ERROR, EventCode.OPERATION_FAILED, id(4), trace));
        assertThrows(NullPointerException.class, () -> new EventMetadata(null, execution, timestamp, Severity.ERROR, EventCode.OPERATION_FAILED, id(4), trace));
        assertThrows(NullPointerException.class, () -> new EventMetadata(id(5), null, timestamp, Severity.ERROR, EventCode.OPERATION_FAILED, id(4), trace));
        assertThrows(NullPointerException.class, () -> new EventMetadata(id(5), execution, null, Severity.ERROR, EventCode.OPERATION_FAILED, id(4), trace));
        assertThrows(NullPointerException.class, () -> new EventMetadata(id(5), execution, timestamp, null, EventCode.OPERATION_FAILED, id(4), trace));
        assertThrows(NullPointerException.class, () -> new EventMetadata(id(5), execution, timestamp, Severity.ERROR, null, id(4), trace));
        assertThrows(IllegalArgumentException.class, () -> new EventMetadata(id(5), execution, timestamp, Severity.ERROR, EventCode.OPERATION_FAILED, NIL, trace));
        assertThrows(NullPointerException.class, () -> new EventMetadata(id(5), execution, timestamp, Severity.ERROR, EventCode.OPERATION_FAILED, null, trace));
        assertThrows(NullPointerException.class, () -> new EventMetadata(id(5), execution, timestamp, Severity.ERROR, EventCode.OPERATION_FAILED, id(4), null));
    }

    @Test
    void sourceValueIsNotEchoedInRejectedMetadata() {
        var failure = assertThrows(IllegalArgumentException.class, () -> build(1, "synthetic/private/upload.txt"));
        assertEquals("INVALID_BUILD_METADATA", failure.getMessage());
    }

    @Test
    void reviewedVocabularyIncludesSuccessFailureCancellationAndDeliveryLoss() {
        var names = Arrays.stream(EventCode.values()).map(EventCode::wireName).toList();
        assertEquals(16, names.size());
        assertEquals(names.size(), names.stream().distinct().count());
        assertTrue(names.containsAll(Arrays.asList("operation.succeeded", "operation.failed", "operation.cancelled",
            "producer.events.lost", "producer.delivery.degraded")));
        assertTrue(names.stream().allMatch(name -> name.matches("[a-z][a-z0-9.]+")));
        assertEquals(6, Severity.values().length);
    }

    @Test
    void optionalTraceCanBeAbsentOrHaveNoLocalSpanYet() {
        assertNull(new TraceContext(null, null).traceId());
        assertNull(new TraceContext("a".repeat(32), null).spanId());
        var trace = new TraceContext("0".repeat(31) + "1", "0".repeat(15) + "1");
        assertEquals("0".repeat(31) + "1", trace.traceId());
        assertEquals("0".repeat(15) + "1", trace.spanId());
        assertThrows(IllegalArgumentException.class, () -> new TraceContext(null, "a".repeat(16)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "00000000000000000000000000000000", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "gggggggggggggggggggggggggggggggg", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void malformedOrZeroTraceCannotJoinAnUnrelatedOperation(String trace) {
        assertThrows(IllegalArgumentException.class, () -> new TraceContext(trace, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0000000000000000", "AAAAAAAAAAAAAAAA", "gggggggggggggggg", "aaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaa"})
    void malformedOrZeroSpanCannotMasrepresentAValidTrace(String span) {
        assertThrows(IllegalArgumentException.class, () -> new TraceContext("a".repeat(32), span));
    }
}
