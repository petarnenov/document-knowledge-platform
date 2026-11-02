package org.documentknowledge.observability;

import java.util.regex.Pattern;

/** Null trace and span represent absence; a trace may exist before a local span. */
public record TraceContext(String traceId, String spanId) {
    private static final Pattern TRACE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN = Pattern.compile("[0-9a-f]{16}");

    public TraceContext {
        if (traceId == null) {
            if (spanId != null) throw new IllegalArgumentException("SPAN_REQUIRES_TRACE");
        } else {
            requireNonzeroHex(traceId, TRACE);
            if (spanId != null) requireNonzeroHex(spanId, SPAN);
        }
    }

    private static void requireNonzeroHex(String value, Pattern pattern) {
        if (!pattern.matcher(value).matches() || value.chars().allMatch(c -> c == '0')) {
            throw new IllegalArgumentException("INVALID_TRACE_CONTEXT");
        }
    }
}
