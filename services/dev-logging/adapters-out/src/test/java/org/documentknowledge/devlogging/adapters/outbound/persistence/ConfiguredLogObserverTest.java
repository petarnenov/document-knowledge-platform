package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.documentknowledge.devlogging.application.*;
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredLogObserverTest {
    @Test void dedicatedHashOnlyObserverRejectsWrongMalformedExpiredAndPreservesCopies() {
        String token="o".repeat(43);byte[] hash=ConfiguredLogObserver.hash(token);var now=Instant.parse("2026-11-02T10:00:00Z");
        var grant=new ConfiguredLogObserver.Grant(UUID.randomUUID(),now.plusSeconds(30),hash);hash[0]^=1;
        assertNotEquals(hash[0],grant.tokenSha256()[0]);var copy=grant.tokenSha256();copy[0]^=1;assertNotEquals(copy[0],grant.tokenSha256()[0]);
        assertEquals("ObserverGrant[REDACTED]",grant.toString());
        var auth=new ConfiguredLogObserver(grant,Clock.fixed(now,ZoneOffset.UTC));auth.authenticate(token);
        for(String bad:Arrays.asList(null,"short","é".repeat(43),"x".repeat(513),"x".repeat(43)))
            assertEquals(LogFailure.Kind.UNAUTHENTICATED,assertThrows(LogFailure.class,()->auth.authenticate(bad)).kind());
        var expired=new ConfiguredLogObserver(grant,Clock.fixed(now.plusSeconds(30),ZoneOffset.UTC));
        assertEquals(LogFailure.Kind.EXPIRED,assertThrows(LogFailure.class,()->expired.authenticate(token)).kind());
        assertThrows(IllegalArgumentException.class,()->new ConfiguredLogObserver.Grant(new UUID(0,0),now,new byte[32]));
        assertThrows(IllegalArgumentException.class,()->new ConfiguredLogObserver.Grant(UUID.randomUUID(),now,new byte[31]));
    }
    @Test void forbiddenNonproducerHashCannotReachSqlAndOriginalDeadlineIsForwarded() {
        var seen=new AtomicReference<RequestDeadline>();var calls=new java.util.concurrent.atomic.AtomicInteger();byte[] forbidden=ConfiguredLogObserver.hash("r".repeat(43));
        var auth=new SeparatedLogProducerAuthentication((token,deadline)->{seen.set(deadline);calls.incrementAndGet();return null;},List.of(forbidden));
        forbidden[0]^=1;
        assertEquals(LogFailure.Kind.UNAUTHENTICATED,assertThrows(LogFailure.class,()->auth.authenticate("r".repeat(43),RequestDeadline.start())).kind());assertEquals(0,calls.get());
        var deadline=RequestDeadline.start();assertNull(auth.authenticate("p".repeat(43),deadline));assertSame(deadline,seen.get());assertEquals(1,calls.get());
        assertThrows(IllegalArgumentException.class,()->new SeparatedLogProducerAuthentication((t,d)->null,List.of(new byte[31])));
        assertThrows(IllegalArgumentException.class,()->new SeparatedLogProducerAuthentication((t,d)->null,Collections.nCopies(12290,new byte[32])));
    }
}
