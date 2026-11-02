package org.documentknowledge.devlogging.application;

import java.time.Instant;
import java.util.*;
import org.documentknowledge.devlogging.domain.*;
import org.documentknowledge.observability.BuildIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogProducerRegistryTest {
    final UUID parent = UUID.randomUUID();
    final Instant until = Instant.now().plusSeconds(3600);
    final LogScope.Description tenant = new LogScope.Description(LogScope.Kind.TENANT,"https://issuer.invalid","actor","tenant-a");
    final LogProducerRegistry.ScopePermission permission = new LogProducerRegistry.ScopePermission(tenant.kind(),tenant.issuer(),tenant.tenantId());

    @Test void trustedGrantCopiesItsFiniteExactPermissionsAndExpiresClosed() {
        var parents = new HashSet<>(Set.of(parent)); var bridges = new HashSet<>(Set.of("a".repeat(64)));
        var permissions = new HashSet<>(Set.of(permission));
        var grant = new LogProducerRegistry.Delegator(UUID.randomUUID(),until,parents,bridges,permissions);
        parents.clear(); bridges.clear(); permissions.clear();
        grant.requireActive(until.minusNanos(1)); grant.requireScope(parent,tenant);
        assertEquals(Set.of("a".repeat(64)),grant.bridgeManifests());
        assertThrows(UnsupportedOperationException.class,()->grant.parents().clear());
        assertThrows(LogFailure.class,()->grant.requireActive(until));
        assertThrows(LogFailure.class,()->grant.requireScope(UUID.randomUUID(),tenant));
        for(var other:List.of(new LogScope.Description(tenant.kind(),tenant.issuer(),"actor","tenant-b"),
                new LogScope.Description(tenant.kind(),"https://other.invalid","actor","tenant-a"),
                new LogScope.Description(LogScope.Kind.AUTHENTICATED_UNSELECTED,tenant.issuer(),"actor",null)))
            assertThrows(LogFailure.class,()->grant.requireScope(parent,other));
        assertTrue(permission.permits(new LogScope.Description(tenant.kind(),tenant.issuer(),"other-verified-subject","tenant-a")));
    }
    @Test void refusesUnboundedOrMalformedGrantsAndRegistrarIdentities() {
        for(UUID invalid:new UUID[]{null,new UUID(0,0)}) {
            assertThrows(LogFailure.class,()->new LogProducerRegistry.Registrar(invalid,until));
            assertThrows(LogFailure.class,()->new LogProducerRegistry.Delegator(invalid,until,Set.of(),Set.of(),Set.of()));
        }
        var registrar = new LogProducerRegistry.Registrar(parent,until);
        registrar.requireActive(until.minusSeconds(1));
        assertThrows(LogFailure.class,()->registrar.requireActive(until));
        assertThrows(LogFailure.class,()->new LogProducerRegistry.Delegator(parent,until,Set.of(new UUID(0,0)),Set.of(),Set.of()));
        assertThrows(LogFailure.class,()->new LogProducerRegistry.Delegator(parent,until,Set.of(),Set.of("bad"),Set.of()));
        var parents = new HashSet<UUID>(); var bridges = new HashSet<String>(); var permissions = new HashSet<LogProducerRegistry.ScopePermission>();
        for(int i=0;i<4097;i++) {
            parents.add(UUID.randomUUID()); bridges.add(String.format("%064x",i));
            permissions.add(new LogProducerRegistry.ScopePermission(LogScope.Kind.TENANT,"issuer","tenant-"+i));
        }
        assertThrows(LogFailure.class,()->new LogProducerRegistry.Delegator(parent,until,parents,Set.of(),Set.of()));
        assertThrows(LogFailure.class,()->new LogProducerRegistry.Delegator(parent,until,Set.of(),bridges,Set.of()));
        assertThrows(LogFailure.class,()->new LogProducerRegistry.Delegator(parent,until,Set.of(),Set.of(),permissions));
    }
    @Test void issuedCredentialCannotLeakThroughDiagnosticRendering() {
        var binding = new ProducerBinding(new BuildIdentity(UUID.randomUUID(),"browser-bff","a".repeat(40),"b".repeat(64)),UUID.randomUUID());
        var authority = new ProducerScope(parent,tenant.identified(UUID.randomUUID()),binding,until);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var issued = new LogProducerRegistry.Issued(authority,until.minusSeconds(60),token);
        assertFalse(issued.toString().contains(token)); assertEquals(authority,issued.authority());
        for(String invalid:new String[]{null,"short","!".repeat(43)})
            assertThrows(LogFailure.class,()->new LogProducerRegistry.Issued(authority,until,invalid));
    }
}
