package org.documentknowledge.security;

import com.nimbusds.jose.jwk.JWKSet;
import io.micronaut.security.token.jwt.signature.jwks.JwkSetFetcher;
import io.micronaut.security.token.jwt.signature.jwks.JwksClient;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/** Single configured source, bounded lease and failure without stale-positive fallback. */
@Singleton
@io.micronaut.context.annotation.Bean(typed = BoundedJwkSetFetcher.class)
public class BoundedJwkSetFetcher implements JwkSetFetcher<JWKSet> {
    private final TrustConfiguration configuration;
    private final JwksClient client;
    private final Clock clock;
    private Mono<Entry> cached;
    @jakarta.inject.Inject
    public BoundedJwkSetFetcher(TrustConfiguration configuration, JwksClient client) { this(configuration, client, Clock.systemUTC()); }
    BoundedJwkSetFetcher(TrustConfiguration configuration, JwksClient client, Clock clock) {
        this.configuration = configuration; this.client = client; this.clock = clock;
    }
    @Override
    public Publisher<JWKSet> fetch(String providerName, String url) {
        if (!configuration.usable() || !configuration.getJwksUrl().equals(url)) return Mono.empty();
        return source().map(Entry::keys).onErrorResume(error -> Mono.empty());
    }
    private synchronized Mono<Entry> source() {
        if (cached == null) {
            cached = Mono.defer(() -> Mono.from(client.load("dkp", configuration.getJwksUrl())))
                .timeout(Duration.ofMillis(configuration.getTimeoutMillis()))
                .filter(body -> body.length() <= 131072)
                .map(body -> {
                    try { return JWKSet.parse(body); }
                    catch (java.text.ParseException error) { throw new IllegalArgumentException("Invalid signing-key set"); }
                })
                .filter(keys -> !keys.isEmpty())
                .map(keys -> new Entry(keys, clock.instant().plusSeconds(configuration.getJwksLeaseSeconds())))
                .cacheInvalidateIf(entry -> !clock.instant().isBefore(entry.expiresAt()));
        }
        return cached;
    }
    @Override
    public synchronized void clearCache(String url) {
        if (configuration.getJwksUrl().equals(url)) cached = null;
    }
    private record Entry(JWKSet keys, Instant expiresAt) { }
}
