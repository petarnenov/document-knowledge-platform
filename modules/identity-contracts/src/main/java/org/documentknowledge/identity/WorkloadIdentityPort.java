package org.documentknowledge.identity;

@FunctionalInterface
public interface WorkloadIdentityPort {
    AuthorityDecision verify(String untrustedIdentityHint);
}
