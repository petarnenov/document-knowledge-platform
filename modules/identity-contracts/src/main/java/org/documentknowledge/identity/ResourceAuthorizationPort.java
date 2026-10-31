package org.documentknowledge.identity;

@FunctionalInterface
public interface ResourceAuthorizationPort {
    AuthorityDecision authorize(Actor actor, String tenant, String action, String resource);
}
