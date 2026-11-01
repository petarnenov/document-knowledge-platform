package org.documentknowledge.browserbff.application;

/** Syntax-only inward port; never resolves, fetches or normalizes an issuer. */
@FunctionalInterface
public interface KnowledgeUriSyntax {
    boolean isAbsolute(String value);
}
