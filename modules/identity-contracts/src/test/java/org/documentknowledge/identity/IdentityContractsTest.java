package org.documentknowledge.identity;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class IdentityContractsTest {
    @Test void actorAndMembershipAreImmutableAndIssuerScoped() {
        assertNotEquals(new Actor("https://one", "same"),new Actor("https://two", "same"));
        for (String[] pair : new String[][]{{null,"x"},{"", "x"},{"https://one",null},{"https://one"," "}})
            assertThrows(IllegalArgumentException.class,()->new Actor(pair[0],pair[1]));
        Set<String> source=new HashSet<>(Set.of("a","b"));
        var membership=new TenantMembership(AuthorityDecision.ALLOW, source); source.clear();
        assertEquals(AuthorityDecision.ALLOW,membership.select("a"));
        assertEquals(AuthorityDecision.ALLOW,membership.select("b"));
        assertEquals(AuthorityDecision.DENY,membership.select("c"));
        assertEquals(AuthorityDecision.DENY,membership.select(null));
        assertThrows(UnsupportedOperationException.class,()->membership.tenants().clear());
        assertThrows(IllegalArgumentException.class,()->new TenantMembership(null,Set.of()));
        assertThrows(IllegalArgumentException.class,()->new TenantMembership(AuthorityDecision.DENY,Set.of("a")));
        assertEquals(AuthorityDecision.DENY,TenantMembership.denied().select("a"));
    }
    @Test void absentAuthorityNeverGrantsEvenAdminOrSpiffe() {
        var actor=new Actor("https://issuer","admin");
        assertEquals(AuthorityDecision.UNAVAILABLE,DefaultAuthorities.entitlements().memberships(actor).select("a"));
        assertEquals(AuthorityDecision.DENY,DefaultAuthorities.resources().authorize(actor,"a","admin","everything"));
        assertEquals(AuthorityDecision.UNAVAILABLE,DefaultAuthorities.workloads().verify("spiffe://forged/admin"));
        assertEquals(AuthorityDecision.UNAVAILABLE,DefaultAuthorities.workloads().verify(null));
    }
}
