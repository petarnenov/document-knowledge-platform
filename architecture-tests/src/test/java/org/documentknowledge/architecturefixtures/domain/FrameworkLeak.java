package org.documentknowledge.architecturefixtures.domain;
public final class FrameworkLeak {
    public Class<?> forbiddenFramework() { return io.micronaut.runtime.Micronaut.class; }
}
