package org.documentknowledge.microkernelvalid.plugin.spi;
public interface Capability {
    record Input(String artifactDigest) { }
    record Output(String artifactDigest) { }
    Output execute(Input input);
}
