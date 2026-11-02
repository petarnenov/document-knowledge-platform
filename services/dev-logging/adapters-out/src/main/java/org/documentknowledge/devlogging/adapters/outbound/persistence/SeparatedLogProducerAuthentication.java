package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.security.MessageDigest;
import java.util.*;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.*;

/** Configured nonproducer credentials cannot become producer authority through a stored hash collision. */
public final class SeparatedLogProducerAuthentication implements LogProducerAuthentication {
    private final LogProducerAuthentication delegate;
    private final List<byte[]> forbidden;
    public SeparatedLogProducerAuthentication(LogProducerAuthentication delegate,List<byte[]> forbidden) {
        this.delegate=Objects.requireNonNull(delegate);
        if(forbidden.size()>12289)throw new IllegalArgumentException("Grant bound exceeded");
        this.forbidden=forbidden.stream().map(hash->{
            if(hash==null || hash.length!=32)throw new IllegalArgumentException("SHA-256 required");return hash.clone();
        }).toList();
    }
    @Override public ProducerScope authenticate(String token,RequestDeadline deadline) {
        deadline.requireTime();byte[] hash=ConfiguredLogObserver.hash(token);boolean denied=false;
        for(byte[] other:forbidden)denied |= MessageDigest.isEqual(hash,other);
        if(denied)throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        deadline.requireTime();return delegate.authenticate(token,deadline);
    }
}
