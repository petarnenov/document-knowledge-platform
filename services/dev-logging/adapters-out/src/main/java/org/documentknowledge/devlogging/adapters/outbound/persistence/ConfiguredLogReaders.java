package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.documentknowledge.devlogging.application.LogReaderAuthentication;
import org.documentknowledge.devlogging.domain.*;

/** Bootstrap supplies private read-only grants. No credential or grant is accepted from URL/body data. */
public final class ConfiguredLogReaders implements LogReaderAuthentication {
    public record Grant(LogReader reader, byte[] tokenSha256) {
        public Grant {
            Objects.requireNonNull(reader);
            if (tokenSha256 == null || tokenSha256.length != 32) throw new IllegalArgumentException("SHA-256 required");
            tokenSha256 = tokenSha256.clone();
        }
        @Override public byte[] tokenSha256() { return tokenSha256.clone(); }
    }
    private final List<Grant> grants;
    private final Clock clock;

    public ConfiguredLogReaders(List<Grant> grants, Clock clock) {
        this.grants = List.copyOf(grants);
        this.clock = Objects.requireNonNull(clock);
        if (this.grants.size() > 4096) throw new IllegalArgumentException("Reader registry bound exceeded");
        var ids = new java.util.HashSet<java.util.UUID>();
        var hashes = new java.util.HashSet<String>();
        for (var grant : this.grants) {
            if (!ids.add(grant.reader().id()) || !hashes.add(java.util.HexFormat.of().formatHex(grant.tokenSha256())))
                throw new IllegalArgumentException("Duplicate reader grant");
        }
    }

    @Override
    public LogReader authenticate(String token) {
        if (token == null || token.length() < 43 || token.length() > 512 || !token.matches("[A-Za-z0-9_-]+"))
            throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException impossible) { throw new LogFailure(LogFailure.Kind.UNAVAILABLE); }
        LogReader selected = null;
        for (var grant : grants) {
            if (MessageDigest.isEqual(hash, grant.tokenSha256())) selected = grant.reader();
        }
        if (selected == null) throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        selected.requireActive(clock.instant());
        return selected;
    }
}
