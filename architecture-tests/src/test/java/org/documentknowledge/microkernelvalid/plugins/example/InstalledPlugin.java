package org.documentknowledge.microkernelvalid.plugins.example;
import org.documentknowledge.microkernelvalid.plugin.spi.Capability;
public final class InstalledPlugin implements Capability {
    public Output execute(Input input) { return new Output(input.artifactDigest()); }
}
