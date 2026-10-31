package org.documentknowledge.microkernelvalid.microkernel;
import org.documentknowledge.microkernelvalid.plugin.spi.Capability;
public final class Registry {
    private final Capability capability;
    public Registry(Capability capability) { this.capability = capability; }
    public Capability.Output execute(Capability.Input input) { return capability.execute(input); }
}
