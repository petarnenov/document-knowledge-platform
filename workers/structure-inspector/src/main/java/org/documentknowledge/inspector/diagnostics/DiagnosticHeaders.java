package org.documentknowledge.inspector.diagnostics;

import java.util.List;
import java.util.UUID;

/** Diagnostic metadata grants no authority and cannot reject a business request. */
public final class DiagnosticHeaders {
    private DiagnosticHeaders() { }
    public static InspectorDiagnostics.Context read(List<String> correlation,List<String> traceparent,InspectorDiagnostics diagnostics) {
        UUID id=UUID.randomUUID();String trace=null,span=null;
        if(correlation!=null) {
            try {
                String value=single(correlation,36);UUID parsed=UUID.fromString(value);
                if(!parsed.toString().equals(value) || parsed.equals(new UUID(0,0)))throw new IllegalArgumentException();
                id=parsed;
            } catch(IllegalArgumentException invalid) {reject(diagnostics);}
        }
        if(traceparent!=null) {
            try {
                String value=single(traceparent,55);
                if(!value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"))throw new IllegalArgumentException();
                trace=value.substring(3,35);span=value.substring(36,52);
                if(trace.chars().allMatch(c->c=='0') || span.chars().allMatch(c->c=='0'))throw new IllegalArgumentException();
            } catch(IllegalArgumentException invalid) {trace=null;span=null;reject(diagnostics);}
        }
        return new InspectorDiagnostics.Context(id,trace,span);
    }
    private static String single(List<String> values,int length) {
        if(values.size()!=1 || values.getFirst()==null || values.getFirst().length()!=length)throw new IllegalArgumentException();
        return values.getFirst();
    }
    private static void reject(InspectorDiagnostics diagnostics) {try{diagnostics.rejected();}catch(RuntimeException ignored){}}
}
