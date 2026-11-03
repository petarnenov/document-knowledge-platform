package org.documentknowledge.documentmanagement.application;

import java.util.Objects;
import java.util.UUID;

/** Version1 diagnostic correlation only. Never an actor, tenant, resource or authorization capability. */
public record DiagnosticContext(UUID correlationId,String traceId,String spanId) {
    public static final String REQUEST_ATTRIBUTE="dkp.dm.diagnostics.v1";
    public DiagnosticContext {
        Objects.requireNonNull(correlationId);
        if(correlationId.getMostSignificantBits()==0 && correlationId.getLeastSignificantBits()==0)
            throw new IllegalArgumentException("INVALID_DIAGNOSTIC_CONTEXT");
        if(traceId==null) {
            if(spanId!=null)throw new IllegalArgumentException("INVALID_DIAGNOSTIC_CONTEXT");
        } else {
            hex(traceId,32);
            if(spanId!=null)hex(spanId,16);
        }
    }
    private static void hex(String value,int length) {
        if(!value.matches("[0-9a-f]{"+length+"}") || value.chars().allMatch(c->c=='0'))
            throw new IllegalArgumentException("INVALID_DIAGNOSTIC_CONTEXT");
    }
    public static DiagnosticContext fresh() {return new DiagnosticContext(UUID.randomUUID(),null,null);}
}
