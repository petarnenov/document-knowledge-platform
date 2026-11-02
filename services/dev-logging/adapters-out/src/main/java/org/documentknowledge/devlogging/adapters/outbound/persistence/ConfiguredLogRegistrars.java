package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.documentknowledge.devlogging.application.LogRegistrationAuthentication;
import org.documentknowledge.devlogging.application.LogProducerRegistry.*;
import org.documentknowledge.devlogging.domain.*;

/** Private bootstrap-derived finite catalogs; request fields cannot select or extend grants. */
public final class ConfiguredLogRegistrars implements LogRegistrationAuthentication {
    public record RegistrarGrant(Registrar authority, byte[] tokenSha256) {
        public RegistrarGrant { Objects.requireNonNull(authority); tokenSha256=checked(tokenSha256); }
        @Override public byte[] tokenSha256() { return tokenSha256.clone(); }
        @Override public String toString() { return "RegistrarGrant[REDACTED]"; }
    }
    public record DelegatorGrant(Delegator authority, byte[] tokenSha256) {
        public DelegatorGrant { Objects.requireNonNull(authority); tokenSha256=checked(tokenSha256); }
        @Override public byte[] tokenSha256() { return tokenSha256.clone(); }
        @Override public String toString() { return "DelegatorGrant[REDACTED]"; }
    }
    private final List<RegistrarGrant> registrars;
    private final List<DelegatorGrant> delegators;
    private final Clock clock;
    public ConfiguredLogRegistrars(List<RegistrarGrant> registrars, List<DelegatorGrant> delegators, Clock clock) {
        this.registrars=List.copyOf(registrars); this.delegators=List.copyOf(delegators); this.clock=Objects.requireNonNull(clock);
        if(this.registrars.size()>4096 || this.delegators.size()>4096) throw new IllegalArgumentException("Grant bound exceeded");
        var ids=new HashSet<UUID>(); var hashes=new HashSet<String>();
        for(var grant:this.registrars) unique(ids,hashes,grant.authority().id(),grant.tokenSha256());
        for(var grant:this.delegators) unique(ids,hashes,grant.authority().id(),grant.tokenSha256());
    }
    @Override public Registrar registrar(String token) {
        byte[] hash=hash(token); Registrar selected=null;
        for(var grant:registrars) if(MessageDigest.isEqual(hash,grant.tokenSha256())) selected=grant.authority();
        if(selected==null) throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        selected.requireActive(clock.instant()); return selected;
    }
    @Override public Delegator delegator(String token) {
        byte[] hash=hash(token); Delegator selected=null;
        for(var grant:delegators) if(MessageDigest.isEqual(hash,grant.tokenSha256())) selected=grant.authority();
        if(selected==null) throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        selected.requireActive(clock.instant()); return selected;
    }
    private static byte[] checked(byte[] hash) {
        if(hash==null || hash.length!=32) throw new IllegalArgumentException("SHA-256 required");
        return hash.clone();
    }
    private static void unique(Set<UUID> ids, Set<String> hashes, UUID id, byte[] hash) {
        if(!ids.add(id) || !hashes.add(HexFormat.of().formatHex(hash))) throw new IllegalArgumentException("Duplicate grant");
    }
    private static byte[] hash(String token) {
        if(token==null || token.length()<43 || token.length()>512 || !token.matches("[A-Za-z0-9_-]+"))
            throw new LogFailure(LogFailure.Kind.UNAUTHENTICATED);
        return HexFormat.of().parseHex(VerifiedLogManifest.sha256(token.getBytes(StandardCharsets.US_ASCII)));
    }
}
