package org.documentknowledge.loggingclient;

import java.io.IOException;
import java.util.*;
import org.documentknowledge.observability.ExecutionIdentity;

/** Replaceable owned pending-store port. Only bootstrap and the dispatcher do I/O. */
public interface LogSpool extends AutoCloseable {
    record Binding(ExecutionIdentity execution,String credentialFingerprint) {
        public Binding {
            Objects.requireNonNull(execution);
            if(credentialFingerprint==null || !credentialFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("INVALID_LOG_SPOOL_BINDING");
        }
    }
    record Stored(UUID id,byte[] bytes) {
        public Stored {
            if(id==null || id.equals(new UUID(0,0)) || bytes==null || bytes.length==0 || bytes.length>8192)
                throw new IllegalArgumentException("INVALID_LOG_SPOOL_EVENT");
            bytes=bytes.clone();
        }
        @Override public byte[] bytes() {return bytes.clone();}
    }
    Binding binding();
    List<Stored> load() throws IOException;
    void replace(List<Stored> events) throws IOException;
    @Override void close();
}
