package org.documentknowledge.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class BoundedKeyLeaseTest {
    static class MutableClock extends Clock {
        Instant instant=Instant.ofEpochSecond(2000);
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return instant;}
        void advance(){instant=instant.plusSeconds(30);}
    }
    @Test void knownKeyLeaseExpiresAndRotationNeverFallsBackToStaleKeys() throws Exception {
        var first=new RSAKeyGenerator(2048).keyID("one").generate().toPublicJWK();
        var second=new RSAKeyGenerator(2048).keyID("two").generate().toPublicJWK();
        var c=TestTrust.config();var clock=new MutableClock();var calls=new AtomicInteger();
        var body=new AtomicReference<>(new JWKSet(first).toString());
        var fetcher=new BoundedJwkSetFetcher(c,(provider,url)->{calls.incrementAndGet();return body.get()==null?Mono.error(new IllegalStateException("unavailable")):Mono.just(body.get());},clock);
        assertEquals(first,Mono.from(fetcher.fetch("dkp",c.getJwksUrl())).block().getKeyByKeyId("one"));
        body.set(new JWKSet(second).toString());
        assertNull(Mono.from(fetcher.fetch("dkp",c.getJwksUrl())).block().getKeyByKeyId("two"));assertEquals(1,calls.get());
        clock.advance();assertEquals(second,Mono.from(fetcher.fetch("dkp",c.getJwksUrl())).block().getKeyByKeyId("two"));
        assertNull(Mono.from(fetcher.fetch("dkp",c.getJwksUrl())).block().getKeyByKeyId("one"));
        body.set(null);clock.advance();assertNull(Mono.from(fetcher.fetch("dkp",c.getJwksUrl())).block());
        body.set(new JWKSet(first).toString());assertNotNull(Mono.from(fetcher.fetch("dkp",c.getJwksUrl())).block());
        fetcher.clearCache("https://attacker");assertEquals(4,calls.get());
        fetcher.clearCache(c.getJwksUrl());assertNotNull(Mono.from(fetcher.fetch("dkp",c.getJwksUrl())).block());assertEquals(5,calls.get());
        assertNull(Mono.from(fetcher.fetch("dkp","https://attacker/keys")).block());assertEquals(5,calls.get());
    }
    @Test void firstFailureTimeoutAndMalformedSetsCannotEstablishTrust() {
        var c=TestTrust.config();c.setTimeoutMillis(20);
        assertNull(Mono.from(new BoundedJwkSetFetcher(c,(p,u)->Mono.never()).fetch("dkp",c.getJwksUrl())).block(Duration.ofSeconds(2)));
        for (String body : new String[]{"not-json","{}","{\"keys\":[]}","x".repeat(131073)}) {
            assertNull(Mono.from(new BoundedJwkSetFetcher(c,(p,u)->Mono.just(body)).fetch("dkp",c.getJwksUrl())).block());
        }
        var bad=new TrustConfiguration();assertNull(Mono.from(new BoundedJwkSetFetcher(bad,(p,u)->{fail("must not fetch");return Mono.empty();}).fetch("dkp","https://attacker")).block());
    }
}
