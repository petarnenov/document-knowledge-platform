package org.documentknowledge.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.security.token.jwt.signature.jwks.DefaultJwkValidator;
import io.micronaut.security.token.jwt.signature.jwks.JwkValidator;
import jakarta.inject.Singleton;

@Singleton
@Replaces(DefaultJwkValidator.class)
public class StrictJwkValidator implements JwkValidator {
    private final DefaultJwkValidator delegate = new DefaultJwkValidator();
    @Override
    public boolean validate(SignedJWT jwt, JWK key) {
        String kid = jwt.getHeader().getKeyID();
        return JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) && kid != null && !kid.isBlank()
            && kid.equals(key.getKeyID()) && key instanceof RSAKey rsa && !rsa.isPrivate() && rsa.size() >= 2048
            && (key.getKeyUse() == null || KeyUse.SIGNATURE.equals(key.getKeyUse()))
            && (key.getKeyOperations() == null || key.getKeyOperations().contains(KeyOperation.VERIFY))
            && (key.getAlgorithm() == null || JWSAlgorithm.RS256.equals(key.getAlgorithm()))
            && delegate.validate(jwt, key);
    }
}
