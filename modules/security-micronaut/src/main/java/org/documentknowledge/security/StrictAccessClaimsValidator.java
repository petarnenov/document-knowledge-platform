package org.documentknowledge.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Collection;

@Singleton
public class StrictAccessClaimsValidator implements GenericJwtClaimsValidator<HttpRequest<?>> {
    private final TrustConfiguration configuration;
    private final Clock clock;
    @jakarta.inject.Inject
    public StrictAccessClaimsValidator(TrustConfiguration configuration) { this(configuration, Clock.systemUTC()); }
    StrictAccessClaimsValidator(TrustConfiguration configuration, Clock clock) { this.configuration = configuration; this.clock = clock; }
    @Override
    public boolean validate(Claims claims, HttpRequest<?> request) {
        Object expiry = claims.get("exp");
        Object before = claims.get("nbf");
        Object subject = claims.get("sub");
        Object audience = claims.get("aud");
        return configuration.usable() && configuration.getIssuer().equals(claims.get("iss"))
            && subject instanceof String s && !s.isBlank() && s.length() <= 256
            && Double.isFinite(epoch(expiry)) && epoch(expiry) > clock.instant().getEpochSecond()
            && (before == null || (Double.isFinite(epoch(before)) && epoch(before) <= clock.instant().getEpochSecond()))
            && ((audience instanceof String a && configuration.getAudience().equals(a))
                || (audience instanceof Collection<?> values && values.contains(configuration.getAudience())))
            && claims.get("azp") instanceof String party && configuration.getAuthorizedParties().contains(party)
            && "Bearer".equals(claims.get("typ"));
    }
    private static double epoch(Object value) {
        // Nimbus parses NumericDate claims into Date after validating their JSON type.
        if (value instanceof java.util.Date date) return date.toInstant().getEpochSecond();
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }
}
