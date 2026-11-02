package org.documentknowledge.observability;

import java.util.Objects;
import java.util.UUID;

final class IdentityChecks {
    private static final UUID NIL = new UUID(0, 0);

    private IdentityChecks() { }

    static UUID requireId(UUID value) {
        Objects.requireNonNull(value);
        if (NIL.equals(value)) throw new IllegalArgumentException("INVALID_DIAGNOSTIC_ID");
        return value;
    }
}
