package org.documentknowledge.browserbff.bootstrap;

/** Explicit counterfactual launcher: the exact same large-HTTP assertion must fail on the old profile. */
public final class IngressBaselineLargeAcceptance {
    public static void main(String[] args)throws Exception {
        if(args.length!=0||!System.getProperty("os.name").equals("Linux"))throw new IllegalArgumentException("EXPLICIT_LINUX_PROFILE_REQUIRED");
        new BoundedIngressLargeTransferTest().actualLargeFixedAndChunkedBrowserBodiesTraverseProductionBffBoundaryWithExactBytes();
    }
}
