package org.documentknowledge.observability;

/** Initial reviewed vocabulary; arbitrary console or exception text is not an event code. */
public enum EventCode {
    PROCESS_STARTED("process.started"),
    PROCESS_STOPPED("process.stopped"),
    OPERATION_STARTED("operation.started"),
    OPERATION_SUCCEEDED("operation.succeeded"),
    OPERATION_FAILED("operation.failed"),
    OPERATION_CANCELLED("operation.cancelled"),
    DEPENDENCY_STARTED("dependency.started"),
    DEPENDENCY_SUCCEEDED("dependency.succeeded"),
    DEPENDENCY_FAILED("dependency.failed"),
    BUILD_STARTED("build.started"),
    BUILD_SUCCEEDED("build.succeeded"),
    BUILD_FAILED("build.failed"),
    UI_ROUTE_CHANGED("ui.route.changed"),
    UI_IDENTITY_CHANGED("ui.identity.changed"),
    PRODUCER_DELIVERY_DEGRADED("producer.delivery.degraded"),
    PRODUCER_EVENTS_LOST("producer.events.lost");

    private final String wireName;

    EventCode(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }
}
