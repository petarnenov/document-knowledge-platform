package org.documentknowledge.security;

import io.micronaut.security.token.MapClaims;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.documentknowledge.identity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClaimsAndMembershipTest {
    private final Clock clock=Clock.fixed(Instant.ofEpochSecond(2000),ZoneOffset.UTC);
    private Map<String,Object> claims() { return new HashMap<>(Map.of("iss","https://issuer.example/realms/dkp","sub","user", "exp",3000L,"aud",List.of("dkp-dm"),"azp","dkp-bff","typ","Bearer","dkp_tenants",List.of("a","b"))); }
    @Test void strictTrustRequiresEveryClaimAndExactIssuer() {
        var validator=new StrictAccessClaimsValidator(TestTrust.config(),clock);
        assertTrue(validator.validate(new MapClaims(claims()),null));
        for (String name : List.of("iss","sub","exp","aud","azp","typ")) {
            var c=claims();c.remove(name);assertFalse(validator.validate(new MapClaims(c),null),name);
        }
        for (var bad : Map.<String,Object>ofEntries(
            Map.entry("iss","http://issuer.example/realms/dkp"),Map.entry("sub"," "),Map.entry("exp",1999L),Map.entry("aud","wrong"),Map.entry("azp","other"),Map.entry("typ","ID")).entrySet()) {
            var c=claims();c.put(bad.getKey(),bad.getValue());assertFalse(validator.validate(new MapClaims(c),null),bad.getKey());
        }
        for (Object bad : List.of("3000",Double.NaN,Double.POSITIVE_INFINITY,2000L)) {var c=claims();c.put("exp",bad);assertFalse(validator.validate(new MapClaims(c),null));}
        for (Object bad : List.of("1000",Double.NaN,3000L)) {var c=claims();c.put("nbf",bad);assertFalse(validator.validate(new MapClaims(c),null));}
        var c=claims();c.put("nbf",1000L);c.put("exp",Date.from(Instant.ofEpochSecond(3000)));c.put("aud","dkp-dm");assertTrue(validator.validate(new MapClaims(c),null));
        c.put("sub","x".repeat(257));assertFalse(validator.validate(new MapClaims(c),null));
        assertFalse(new StrictAccessClaimsValidator(new TrustConfiguration(),clock).validate(new MapClaims(claims()),null));
    }
    @Test void membershipsAreIssuerBoundExplicitAndNonpermissive() {
        var resolver=new TrustedTenantResolver(TestTrust.config());
        assertEquals(AuthorityDecision.ALLOW,resolver.resolve(claims()).select("a"));
        assertEquals(AuthorityDecision.ALLOW,resolver.resolve(claims()).select("b"));
        var boundary=claims();boundary.put("dkp_tenants",List.of("x".repeat(256)));
        assertEquals(AuthorityDecision.ALLOW,resolver.resolve(boundary).select("x".repeat(256)));
        assertEquals(AuthorityDecision.DENY,resolver.resolve(claims()).select("forged"));
        for (Object value : List.of("a",List.of(),List.of("a","a"),List.of(" "),List.of(3),List.of("x".repeat(257)),List.of("a\n"),Collections.nCopies(101,"a"))) {
            var c=claims();c.put("dkp_tenants",value);assertEquals(AuthorityDecision.DENY,resolver.resolve(c).select("a"));
        }
        var c=claims();c.remove("dkp_tenants");assertEquals(AuthorityDecision.DENY,resolver.resolve(c).decision());
        c=claims();c.put("iss","https://other");assertEquals(AuthorityDecision.DENY,resolver.resolve(c).decision());
        c=claims();c.remove("sub");assertEquals(AuthorityDecision.DENY,resolver.resolve(c).decision());
        c=claims();c.put("sub"," ");assertEquals(AuthorityDecision.DENY,resolver.resolve(c).decision());
        assertEquals(AuthorityDecision.DENY,new TrustedTenantResolver(new TrustConfiguration()).resolve(claims()).decision());
        var external=TestTrust.config();external.setProfile("external");
        assertEquals(AuthorityDecision.UNAVAILABLE,new TrustedTenantResolver(external).resolve(claims()).select("a"));
        assertEquals(AuthorityDecision.DENY,new TrustedTenantResolver(external, actor->TenantMembership.denied()).resolve(claims()).select("a"));
    }
    @Test void unsafeAndIncompleteConfigurationClosesCapability() {
        var c=TestTrust.config();assertTrue(c.usable());assertEquals("resource",c.getMode());
        for(String mode:Arrays.asList(null,"off","unknown","")) {c.setMode(mode);assertFalse(c.usable());}
        c.setMode("resource");
        assertEquals(30,c.getJwksLeaseSeconds());assertEquals(2000,c.getTimeoutMillis());
        c.setJwksLeaseSeconds(31);assertFalse(c.usable());c.setJwksLeaseSeconds(0);assertFalse(c.usable());c.setJwksLeaseSeconds(30);
        c.setTimeoutMillis(0);assertFalse(c.usable());c.setTimeoutMillis(5001);assertFalse(c.usable());c.setTimeoutMillis(2000);
        c.setProfile("other");assertFalse(c.usable());c.setProfile("external");assertTrue(c.usable());c.setProfile("standalone");
        c.setStandaloneIssuer("https://other");assertFalse(c.usable());c.setStandaloneIssuer(c.getIssuer());
        c.setAudience("");assertFalse(c.usable());c.setAudience(null);assertFalse(c.usable());c.setAudience("dkp-dm");
        c.setAuthorizedParties(List.of());assertFalse(c.usable());c.setAuthorizedParties(List.of(" "));assertFalse(c.usable());c.setAuthorizedParties(List.of("bff"));
        for (String bad : Arrays.asList(null,"http://issuer.example","https://user:pass@issuer.example","https://issuer.example/#fragment","https://issuer.example/?query","https:///missing-host", "[bad")) {
            c.setJwksUrl(bad);assertFalse(c.usable(),String.valueOf(bad));
        }
    }
}
