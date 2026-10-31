package org.documentknowledge.browserbff.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.documentknowledge.browserbff.domain.BrowserIdentity;

/** Policy at the framework's already-verified ID-token mapper boundary; never parses JWT strings. */
public final class VerifiedIdentityFactory {
    private final String issuer;
    private final String clientId;
    private final boolean standalone;
    private final Clock clock;
    public VerifiedIdentityFactory(String issuer, String clientId, boolean standalone, Clock clock) {
        this.issuer = issuer; this.clientId = clientId; this.standalone = standalone; this.clock = clock;
    }
    public BrowserIdentity create(Map<String, Object> verifiedClaims, String accessToken, Integer expiresIn) {
        Instant now = clock.instant();
        if (!issuer.equals(verifiedClaims.get("iss")) || !audienceContains(verifiedClaims.get("aud"), clientId)) fail();
        String subject = text(verifiedClaims.get("sub"), 256);
        Object expiration = verifiedClaims.get("exp");
        if (!(expiration instanceof Number)) fail();
        Instant idExpiry;
        try { idExpiry = Instant.ofEpochSecond(((Number) expiration).longValue()); }
        catch (RuntimeException invalid) { throw new BffFailure(401, "INVALID_IDENTITY"); }
        if (!idExpiry.isAfter(now) || expiresIn == null || expiresIn <= 0 || accessToken == null || accessToken.isBlank() || accessToken.length() > 32768 || !accessToken.matches("[A-Za-z0-9._~+/=-]+")) fail();
        Instant deadline = now.plusSeconds(Math.min(expiresIn, 300));
        if (idExpiry.isBefore(deadline)) deadline = idExpiry;
        List<String> tenants = standalone ? memberships(verifiedClaims.get("dkp_tenants")) : List.of();
        Object name = verifiedClaims.get("name");
        String displayName = name instanceof String value && !value.isBlank() && value.length() <= 256 ? value : subject;
        return new BrowserIdentity(issuer, subject, displayName, tenants, standalone, accessToken, deadline);
    }
    private static boolean audienceContains(Object audience, String expected) {
        return expected.equals(audience) || audience instanceof List<?> list && list.stream().allMatch(String.class::isInstance) && list.contains(expected);
    }
    private static List<String> memberships(Object claim) {
        if (!(claim instanceof List<?> values) || values.isEmpty() || values.size() > 100) throw new BffFailure(401, "INVALID_MEMBERSHIP");
        List<String> result = new ArrayList<>();
        for (Object value : (List<?>) claim) result.add(text(value, 256));
        if (new HashSet<>(result).size() != result.size()) throw new BffFailure(401, "INVALID_MEMBERSHIP");
        return List.copyOf(result);
    }
    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max || !text.equals(text.strip()) || text.chars().anyMatch(Character::isISOControl)) fail();
        return (String) value;
    }
    private static void fail() { throw new BffFailure(401, "INVALID_IDENTITY"); }
}
