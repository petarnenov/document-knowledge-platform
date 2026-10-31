package org.documentknowledge.microkernelfixtures.microkernel;
public class UnsafeKernel {
    public org.documentknowledge.microkernelfixtures.plugins.alpha.Plugin implementation;
    public Class<?> load(String input) throws ClassNotFoundException { return Class.forName(input); }
    public Process run(String input) throws java.io.IOException { return Runtime.getRuntime().exec(input); }
}
