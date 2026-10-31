package org.documentknowledge.identity;

/** Actor identity is namespaced by its exact trusted issuer. */
public record Actor(String issuer, String subject) {
    public Actor {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Actor requires issuer and subject");
        }
    }
}
