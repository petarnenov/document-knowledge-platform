package org.documentknowledge.browserbff.bootstrap;

/** Explicit Linux component profile; genuine provider/full HTTPS acceptance remains separate. */
public final class IngressLinuxAcceptance {
    @FunctionalInterface interface Case { void run() throws Exception; }
    private static int passed;
    static void run(String name, Case body)throws Exception {
        body.run();passed++;System.out.println("{\"case\":\""+name+"\",\"result\":\"PASS\"}");
    }
    static String json(java.util.Map<String,Long> values) {
        return values.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .map(e -> "\""+e.getKey()+"\":"+e.getValue()).collect(java.util.stream.Collectors.joining(",","{","}"));
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=0||!System.getProperty("os.name").equals("Linux"))throw new IllegalArgumentException("EXPLICIT_LINUX_PROFILE_REQUIRED");
        var config=new BoundedIngressPolicyTest();var channel=new IngressChannelPolicyTest();var backpressure=new IngressBackpressureTest();
        run("profile-tcp-and-tls-config",config::exactProfileAndPlainTcpListenersAreAccepted);
        run("profile-negative-config",config::everyMemoryProtocolAndDecompressionMismatchFailsClosed);
        run("unsupported-startup-rejected",config::actualServerCannotStartWithUnselectedWindowOrProtocol);
        run("cached-old-handle-rejected",config::actualNettyReceiveHandleCachedBeforePolicyCannotInheritNewAllocatorProof);
        run("unknown-request-channel-rejected",config::unknownHttpRequestImplementationCannotInheritChannelProof);
        run("accepted-channel-before-body",channel::acceptedRealConnectionHasSelectedAllocatorAndAutoReadBeforeAnyBody);
        run("late-channel-policy-rejected",channel::laterAllocatorReadOrGlobalPolicyMutationClosesBeforeProtectedDispatchAndLatchesReadiness);
        run("h2-offer-remains-http11",channel::optionalH2cOfferRemainsPlainHttp11AndNeverSwitchesProtocol);
        run("unsupported-wire-protocol-rejected",channel::priorKnowledgeAndHttp10NeverDispatchProtectedOperations);
        run("real-credit-and-bridge-bounds",backpressure::actualPendingQueueStopsAtInitialCreditAndSingleReceiveBurstWithIndependentBridgePieces);
        run("legal-coalesced-pipeline-two-requests",backpressure::legalCoalescedPipelineRetainsTwoIndependentRequestBodiesWithoutGlobalCapOrDeadlock);
        run("exact-large-pdf-fixed-and-chunked",new BoundedIngressLargeTransferTest()::actualLargeFixedAndChunkedBrowserBodiesTraverseProductionBffBoundaryWithExactBytes);
        run("existing-auth-encoding-metadata-ranges",new UploadHttpTest()::realBrowserBoundaryGuardsChunkedTransferDiscoveryAndRangeContent);
        run("actual-fixed-chunked-binary-boundaries",new IngressBinaryBoundsTest()::fixedAndChunkedBodiesPreserveOneSixteenKiBAndHundredMillionExactByteBounds);
        run("semantic-json-fixed-chunked-boundaries",new IngressMetadataLimitTest()::semanticMetadataLimitSurvivesLargerFrameworkWindowForFixedAndChunkedBodies);
        run("rejected-body-late-bytes",new IngressLateBytesTest()::rejectedPartialBodiesCannotTurnLateBytesIntoASecondProtectedOperation);
        System.out.println("{\"measurement\":\"credit-and-bridge\",\"values\":"+json(IngressBackpressureTest.lastCredit)+"}");
        System.out.println("{\"measurement\":\"legal-pipeline\",\"values\":"+json(IngressBackpressureTest.lastPipeline)+"}");
        run("same-handler-hidden-policy-mutation",channel::sameActualMicronautHandlerPolicyMutationClosesAndLatchesBeforeAnyProtectedHandoff);
        var live = new IngressLiveHandoffTest();
        run("actual-channel-readiness-latch",live::actualAcceptedChannelIsRequiredBeforeReadinessAndLaterSuccessCannotResetFailure);
        run("ordered-read-complete-error-mutations",live::mutationAfterRawCheckIsDetectedAtEveryActualDownstreamCallback);
        run("actual-handler-adjacency",live::insertingAnythingBetweenFinalGuardAndActualHandlerClosesBeforeInsertedCode);
        run("post-prior-request-fresh-readback",live::mutationAfterPriorSuccessfulRequestStillUsesFreshSameInstanceReadback);
        run("off-loop-hop-rejected",live::competingExecutorHopIsRejectedBeforeAnyOffLoopCodeReceivesBody);
        var readback = new PinnedIngressReadbackTest();
        run("actual-loaded-artifact-and-live-fields",readback::exactLoadedJarAndSelectedFieldsReadActualValuesAfresh);
        run("field-owner-type-modifier-access-denial",readback::exactDeclaredTypeOwnerAndModifiersAndActualJvmAccessAreMandatory);
        run("unknown-loader-construction-denied",readback::unknownApplicationLoaderRejectsConstructionWithFiniteUnavailableFailure);
        run("wrong-artifact-null-source-directory-denied",() -> {
            readback.temp = java.nio.file.Files.createTempDirectory("dkp338-artifact-negative-");
            try { readback.unselectedDirectoryNullSourceAndDifferentJarDigestAreNotAccepted(); }
            finally {
                java.nio.file.Files.deleteIfExists(readback.temp.resolve("other.jar"));
                java.nio.file.Files.delete(readback.temp);
            }
        });
        if(passed!=26)throw new IllegalStateException("INCOMPLETE_PROFILE");
        System.out.println("{\"profile\":\"dkp338-linux-ingress-component\",\"passed\":26,\"failed\":0,\"skipped\":0}");
    }
}
