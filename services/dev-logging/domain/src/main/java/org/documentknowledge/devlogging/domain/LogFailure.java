package org.documentknowledge.devlogging.domain;

import java.util.Objects;

/** Fixed diagnostic failure kinds; no raw token, SQL text or payload in exception messages. */
public final class LogFailure extends RuntimeException {
    public enum Kind { INVALID, UNAUTHENTICATED, FORBIDDEN, CONFLICT, EXPIRED, RATE_LIMITED, CAPACITY, UNAVAILABLE }

    private final Kind kind;

    public LogFailure(Kind kind) {
        super(Objects.requireNonNull(kind).name());
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
