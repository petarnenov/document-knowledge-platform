/** Test-only negative profile guards; never a canonicalizer implementation. */
public final class ProbeEdges {
    interface Checked { void run() throws Exception; }
    static void rejected(Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Profile was not rejected");
    }
    public static void main(String[] args) throws Exception {
        rejected(() -> StrictCompositionProbe.main(new String[0]));
        rejected(() -> StrictCompositionProbe.main(new String[]{"UNKNOWN", "unused"}));
        rejected(() -> StrictCompositionProbe.canonize("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "UNKNOWN"));
        rejected(() -> PolyglotCompositionProbe.main(new String[0]));
        System.out.println("RESEARCH_EDGE_GUARDS_PASS");
    }
}
