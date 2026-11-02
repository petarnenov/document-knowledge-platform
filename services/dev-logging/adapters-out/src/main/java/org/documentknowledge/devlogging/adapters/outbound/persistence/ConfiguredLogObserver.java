package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.documentknowledge.devlogging.application.LogObserverAuthentication;
import org.documentknowledge.devlogging.domain.*;

/** Hash-only dedicated observer grant; never consults the database or emits recursive diagnostics. */
public final class ConfiguredLogObserver implements LogObserverAuthentication {
    public record Grant(UUID id, Instant expiresAt, byte[] tokenSha256) {
        public Grant {
            if(id==null || id.equals(new UUID(0,0)) || expiresAt==null || tokenSha256==null || tokenSha256.length!=32)
                throw new IllegalArgumentException("Invalid observer grant");
            tokenSha256=tokenSha256.clone();
        }
        @Override public byte[] tokenSha256(){return tokenSha256.clone();}
        @Override public String toString(){return "ObserverGrant[REDACTED]";}
    }
    private final Grant grant;
    private final Clock clock;
    public ConfiguredLogObserver(Grant grant,Clock clock){this.grant=Objects.requireNonNull(grant);this.clock=Objects.requireNonNull(clock);}
    @Override public void authenticate(String token) {
        if(!MessageDigest.isEqual(hash(token),grant.tokenSha256()))throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        if(!clock.instant().isBefore(grant.expiresAt()))throw new LogFailure(LogFailure.Kind.EXPIRED);
    }
    static byte[] hash(String token) {
        if(token==null || token.length()<43 || token.length()>512 || !token.matches("[A-Za-z0-9_-]+"))
            throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        return HexFormat.of().parseHex(VerifiedLogManifest.sha256(token.getBytes(StandardCharsets.US_ASCII)));
    }
}
