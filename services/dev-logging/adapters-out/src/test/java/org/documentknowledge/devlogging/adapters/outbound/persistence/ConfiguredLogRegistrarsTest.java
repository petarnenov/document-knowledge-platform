package org.documentknowledge.devlogging.adapters.outbound.persistence;

import java.time.*;
import java.util.*;
import org.documentknowledge.devlogging.application.LogProducerRegistry.*;
import org.documentknowledge.devlogging.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredLogRegistrarsTest {
    final Instant now=Instant.parse("2026-11-02T20:00:00Z");
    final Registrar registrar=new Registrar(UUID.randomUUID(),now.plusSeconds(60));
    final Delegator delegator=new Delegator(UUID.randomUUID(),now.plusSeconds(60),Set.of(UUID.randomUUID()),Set.of(),Set.of());
    final String registrationToken="a".repeat(43),delegationToken="b".repeat(43);
    byte[] hash(String value) { return HexFormat.of().parseHex(VerifiedLogManifest.sha256(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII))); }
    @Test void dedicatedRolesUseDefensiveHashCopiesAndExpiryWithoutPrintingCredentials() {
        byte[] bytes=hash(registrationToken);
        var registration=new ConfiguredLogRegistrars.RegistrarGrant(registrar,bytes); bytes[0]^=1;
        byte[] copy=registration.tokenSha256();copy[1]^=1;
        var delegation=new ConfiguredLogRegistrars.DelegatorGrant(delegator,hash(delegationToken));
        var auth=new ConfiguredLogRegistrars(List.of(registration),List.of(delegation),Clock.fixed(now,ZoneOffset.UTC));
        assertEquals(registrar,auth.registrar(registrationToken));assertEquals(delegator,auth.delegator(delegationToken));
        assertFalse(registration.toString().contains(registrationToken));assertFalse(delegation.toString().contains(delegationToken));
        assertThrows(LogFailure.class,()->auth.registrar(delegationToken));assertThrows(LogFailure.class,()->auth.delegator(registrationToken));
        for(String invalid:new String[]{null,"short","!".repeat(43),"c".repeat(513),"c".repeat(43)}) {
            assertEquals(LogFailure.Kind.UNAUTHENTICATED,assertThrows(LogFailure.class,()->auth.registrar(invalid)).kind());
            assertEquals(LogFailure.Kind.UNAUTHENTICATED,assertThrows(LogFailure.class,()->auth.delegator(invalid)).kind());
        }
        var expired=new ConfiguredLogRegistrars(List.of(registration),List.of(delegation),Clock.fixed(now.plusSeconds(60),ZoneOffset.UTC));
        assertEquals(LogFailure.Kind.EXPIRED,assertThrows(LogFailure.class,()->expired.registrar(registrationToken)).kind());
        assertEquals(LogFailure.Kind.EXPIRED,assertThrows(LogFailure.class,()->expired.delegator(delegationToken)).kind());
    }
    @Test void privateConfigurationRejectsHashOrPrincipalReuseAndUnboundedCatalogs() {
        var one=new ConfiguredLogRegistrars.RegistrarGrant(registrar,hash(registrationToken));
        var other=new ConfiguredLogRegistrars.DelegatorGrant(delegator,hash(registrationToken));
        assertThrows(IllegalArgumentException.class,()->new ConfiguredLogRegistrars(List.of(one),List.of(other),Clock.systemUTC()));
        var sameId=new Delegator(registrar.id(),delegator.expiresAt(),delegator.parents(),Set.of(),Set.of());
        assertThrows(IllegalArgumentException.class,()->new ConfiguredLogRegistrars(List.of(one),List.of(new ConfiguredLogRegistrars.DelegatorGrant(sameId,hash(delegationToken))),Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,()->new ConfiguredLogRegistrars(Collections.nCopies(4097,one),List.of(),Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,()->new ConfiguredLogRegistrars(List.of(),Collections.nCopies(4097,other),Clock.systemUTC()));
        for(byte[] invalid:new byte[][]{null,new byte[31]}) {
            assertThrows(IllegalArgumentException.class,()->new ConfiguredLogRegistrars.RegistrarGrant(registrar,invalid));
            assertThrows(IllegalArgumentException.class,()->new ConfiguredLogRegistrars.DelegatorGrant(delegator,invalid));
        }
    }
}
