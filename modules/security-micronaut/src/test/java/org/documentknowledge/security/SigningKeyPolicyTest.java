package org.documentknowledge.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SigningKeyPolicyTest {
    private SignedJWT token(RSAKey key,String kid,JWSAlgorithm algorithm) throws Exception {
        var header=new JWSHeader.Builder(algorithm);if(kid!=null)header.keyID(kid);
        var jwt=new SignedJWT(header.build(),new JWTClaimsSet.Builder().subject("user").build());
        jwt.sign(new RSASSASigner(key));return jwt;
    }
    @Test void frameworkVerifierRotatesOnlyAfterBoundedSuccessfulRefresh() throws Exception {
        var first=new RSAKeyGenerator(2048).keyID("one").generate();
        var second=new RSAKeyGenerator(2048).keyID("two").generate();
        var trust=TestTrust.config();var clock=new BoundedKeyLeaseTest.MutableClock();
        var published=new java.util.concurrent.atomic.AtomicReference<>(new JWKSet(first.toPublicJWK()).toString());
        var fetcher=new BoundedJwkSetFetcher(trust,(provider,url)->{
            assertEquals(trust.getJwksUrl(),url);
            return published.get()==null?reactor.core.publisher.Mono.error(new IllegalStateException("offline")):reactor.core.publisher.Mono.just(published.get());
        },clock);
        var config=new io.micronaut.security.token.jwt.signature.jwks.JwksSignatureConfigurationProperties("dkp");config.setUrl(trust.getJwksUrl());
        var verifier=new StrictReactiveJwksSignature(config,new StrictJwkValidator(),fetcher);
        var oldToken=token(first,"one",JWSAlgorithm.RS256);var newToken=token(second,"two",JWSAlgorithm.RS256);
        assertEquals(true,reactor.core.publisher.Mono.from(verifier.verify(oldToken)).block());
        published.set(new JWKSet(java.util.List.of(first.toPublicJWK(),second.toPublicJWK())).toString());
        assertEquals(false,reactor.core.publisher.Mono.from(verifier.verify(newToken)).block());
        clock.advance();assertEquals(true,reactor.core.publisher.Mono.from(verifier.verify(newToken)).block());
        assertEquals(true,reactor.core.publisher.Mono.from(verifier.verify(oldToken)).block());
        published.set(new JWKSet(second.toPublicJWK()).toString());clock.advance();
        assertEquals(false,reactor.core.publisher.Mono.from(verifier.verify(oldToken)).block());
        published.set(null);clock.advance();assertNull(reactor.core.publisher.Mono.from(verifier.verify(newToken)).block());
        var externalHeader=new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("two").jwkURL(java.net.URI.create("https://attacker.example/keys")).build();
        var malicious=new SignedJWT(externalHeader,new JWTClaimsSet.Builder().subject("user").build());malicious.sign(new RSASSASigner(second));
        assertNull(reactor.core.publisher.Mono.from(verifier.verify(malicious)).block());
    }
    @Test void onlyMatchingPublicRsaSigningKeysAndRs256AreAccepted() throws Exception {
        var key=new RSAKeyGenerator(2048).keyID("one").generate();var verifier=new StrictJwkValidator();
        var jwt=token(key,"one",JWSAlgorithm.RS256);
        assertTrue(verifier.validate(jwt,key.toPublicJWK()));
        assertFalse(verifier.validate(jwt,key));
        assertFalse(verifier.validate(token(key,null,JWSAlgorithm.RS256),key.toPublicJWK()));
        assertFalse(verifier.validate(token(key," ",JWSAlgorithm.RS256),key.toPublicJWK()));
        assertFalse(verifier.validate(token(key,"other",JWSAlgorithm.RS256),key.toPublicJWK()));
        assertFalse(verifier.validate(token(key,"one",JWSAlgorithm.RS512),key.toPublicJWK()));
        var other=new RSAKeyGenerator(2048).keyID("one").generate();assertFalse(verifier.validate(jwt,other.toPublicJWK()));
        assertFalse(verifier.validate(jwt,new RSAKey.Builder(key.toPublicJWK()).keyUse(KeyUse.ENCRYPTION).build()));
        assertFalse(verifier.validate(jwt,new RSAKey.Builder(key.toPublicJWK()).keyOperations(Set.of(KeyOperation.ENCRYPT)).build()));
        assertFalse(verifier.validate(jwt,new RSAKey.Builder(key.toPublicJWK()).algorithm(JWSAlgorithm.RS512).build()));
        assertTrue(verifier.validate(jwt,new RSAKey.Builder(key.toPublicJWK()).keyUse(KeyUse.SIGNATURE).keyOperations(Set.of(KeyOperation.VERIFY)).algorithm(JWSAlgorithm.RS256).build()));
        var small=new RSAKeyGenerator(1024,true).keyID("one").generate();assertFalse(verifier.validate(jwt,small.toPublicJWK()));
        assertFalse(verifier.validate(jwt,new OctetSequenceKey.Builder(new byte[32]).keyID("one").build()));
    }
}
