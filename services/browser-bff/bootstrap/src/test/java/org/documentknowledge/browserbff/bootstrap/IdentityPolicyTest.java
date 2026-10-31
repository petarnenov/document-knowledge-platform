package org.documentknowledge.browserbff.bootstrap;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.documentknowledge.browserbff.application.BffFailure;
import org.documentknowledge.browserbff.application.SessionPolicy;
import org.documentknowledge.browserbff.application.VerifiedIdentityFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final VerifiedIdentityFactory local = new VerifiedIdentityFactory("https://issuer.example", "dkp-bff", true, clock);
    private Map<String, Object> valid() { return new HashMap<>(Map.of("iss", "https://issuer.example", "sub", "staff-1", "aud", List.of("dkp-bff"), "exp", NOW.plusSeconds(600).getEpochSecond(), "dkp_tenants", List.of("tenant-a", "tenant-b"), "name", "Synthetic Staff")); }
    @Test void snapshotsAreImmutableAndExpireAtEarliestDeadlineWithoutExposingCredentials() {
        var claims = valid(); var tenants = new ArrayList<>(List.of("tenant-a", "tenant-b")); claims.put("dkp_tenants", tenants);
        var identity = local.create(claims, "synthetic-access", 900); tenants.clear();
        assertEquals(List.of("tenant-a", "tenant-b"), identity.tenants()); assertEquals(NOW.plusSeconds(300), identity.expiresAt());
        assertFalse(identity.toString().contains("synthetic-access")); assertFalse(identity.toString().contains("staff-1"));
        claims.put("exp", NOW.plusSeconds(20).getEpochSecond()); claims.put("aud", "dkp-bff"); claims.remove("name"); claims.put("dkp_tenants", List.of("tenant-a"));
        var shorter = local.create(claims, "synthetic-access", 60); assertEquals(NOW.plusSeconds(20), shorter.expiresAt()); assertEquals("staff-1", shorter.displayName());
        assertThrows(UnsupportedOperationException.class, () -> identity.tenants().add("tenant-c"));
    }
    @Test void identityTrustRejectsMissingMalformedAndCrossAudienceClaims() {
        for (String name : List.of("iss", "sub", "aud", "exp")) { var claims = valid(); claims.remove(name); assertDenied(() -> local.create(claims, "access", 60), 401); }
        for (Map.Entry<String,Object> change : Map.<String,Object>of("iss","http://issuer.example", "aud", List.of("other-client"), "sub", "  ", "exp", "tomorrow").entrySet()) {
            var claims = valid(); claims.put(change.getKey(),change.getValue()); assertDenied(() -> local.create(claims,"access",60),401);
        }
        for (Object audience : List.of(List.of("dkp-bff", 42), 4, List.of())) { var claims=valid(); claims.put("aud",audience); assertDenied(() -> local.create(claims,"access",60),401); }
        for (Object sub : List.of("a".repeat(257), " leading", "trailing ", "control\n")) { var claims=valid(); claims.put("sub",sub); assertDenied(() -> local.create(claims,"access",60),401); }
        for (long expiry : List.of(NOW.getEpochSecond(), NOW.minusSeconds(1).getEpochSecond(), Long.MAX_VALUE)) { var claims=valid(); claims.put("exp",expiry); assertDenied(() -> local.create(claims,"access",60),401); }
        for (String token : new String[]{null,"", " ", "a".repeat(32769)}) assertDenied(() -> local.create(valid(),token,60),401);
        for (Integer ttl : new Integer[]{null,0,-1}) assertDenied(() -> local.create(valid(),"access",ttl),401);
    }
    @Test void malformedOrAmbiguousMembershipNeverGrantsAccess() {
        for (Object membership : List.of("tenant-a", List.of(), List.of("tenant-a","tenant-a"), List.of(5), List.of(" "), java.util.Collections.nCopies(1001,"a"))) {
            var claims=valid(); claims.put("dkp_tenants",membership); assertDenied(() -> local.create(claims,"access",60),401);
        }
        var absent=valid(); absent.remove("dkp_tenants"); assertDenied(() -> local.create(absent,"access",60),401);
    }
    @Test void externalProfileIgnoresLocalClaimAndAuthorityRemainsUnavailable() {
        var claims=valid(); claims.put("dkp_tenants",List.of("administrator","tenant-root"));
        var external=new VerifiedIdentityFactory("https://issuer.example","dkp-bff",false,clock).create(claims,"access",60);
        assertTrue(external.tenants().isEmpty()); assertFalse(external.membershipAvailable());
        assertDenied(() -> new SessionPolicy(clock).requireActive(external),503);
    }
    @Test void sessionPolicyChecksExpiryAndTenantOnEveryUse() {
        var identity=local.create(valid(),"access",60); var policy=new SessionPolicy(clock);
        policy.requireTenant(identity,"tenant-a"); policy.requireTenant(identity,"tenant-b");
        assertDenied(() -> policy.requireTenant(identity,"tenant-c"),403); assertDenied(() -> policy.requireTenant(identity,null),403);
        assertDenied(() -> policy.requireActive(null),401);
        assertDenied(() -> new SessionPolicy(Clock.fixed(NOW.plusSeconds(61),ZoneOffset.UTC)).requireActive(identity),401);
        var view=policy.view(identity,"tenant-a","csrf"); assertEquals("staff-1",view.subject()); assertEquals("csrf",view.csrfToken());
        assertEquals("tenant-a",view.selectedTenant()); assertEquals(identity.issuer(),view.issuer()); assertEquals(identity.displayName(),view.displayName()); assertEquals(identity.tenants(),view.tenants());
        var f=new BffFailure(403,"tenant_denied"); assertEquals("TENANT_DENIED",f.code()); assertEquals(403,f.status());
    }
    private static void assertDenied(org.junit.jupiter.api.function.Executable operation,int status) { assertEquals(status, assertThrows(BffFailure.class,operation).status()); }
}
