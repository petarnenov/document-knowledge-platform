package org.documentknowledge.security;

import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.security.token.jwt.nimbus.ReactiveJwksSignature;
import io.micronaut.security.token.jwt.signature.jwks.JwksSignatureConfiguration;

/** Bind framework verification to our bounded source without replacing cryptography. */
@EachBean(JwksSignatureConfiguration.class)
@Replaces(ReactiveJwksSignature.class)
public class StrictReactiveJwksSignature extends ReactiveJwksSignature {
    public StrictReactiveJwksSignature(JwksSignatureConfiguration configuration, StrictJwkValidator validator,
                                      BoundedJwkSetFetcher fetcher) {
        super(configuration, validator, fetcher);
    }
}
