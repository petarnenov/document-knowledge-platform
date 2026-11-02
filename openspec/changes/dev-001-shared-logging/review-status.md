# Independent review and selection record — #457

Initial independent review under #224 supports the common-store/buildId/Beacon direction, but **does not approve the full runtime implementation**. Private source-hashed review receipts are retained outside the public repository; no private runtime evidence is copied here.

## Open full-runtime findings

- L1: server-verifiable epoch and stale-tab/tenant-transition attribution. Design revised to server-owned session epochs and a context endpoint; exact revised review and actual tests remain required.
- L2: finite registration/replay/dedup horizon and tombstone resource ownership. Design revised to expiring immutable principals and capacity-accounted retained receipts; exact review and tests remain required.
- L3: concrete disk admission/maintenance failure behavior. Proposed thresholds and fail-closed observation are documented; review and real PostgreSQL/storage tests remain required.
- L4: honest vendor image provenance. Design distinguishes a DKP observer's actual compilation from an observed immutable vendor image; no vendor compilation identity is invented.
- C1/C2: trusted server delegation and secured browser receipt protocol. Draft v1 now contains registration, delegation, browser context and browser receipt routes; no route is implemented or approved merely by this edit.
- C3: safe vocabulary/manifest ownership and lifecycle need a complete reviewed definition before transport/runtime ingestion.
- C4–C7: commit-only ingestion receipts, canonical duplicate rules, valid trace/span context, no-store responses, typed dependency context and total Beacon envelope sizing are revised in the draft; exact revised contract and positive/negative fixtures remain required.
- Collector topology, resource profile, immutable release pin and conformance/security verification remain explicitly OPEN under #47/BP15 before its actual implementation and full-project closure. Independent scope review checked actual #457/#47 and System Design §12.5: the common dev API/store is an incremental implementation of the selected architecture; deploying the Collector now was extra draft scope, not an owner #457 criterion. Current adapter ownership and future field mapping remain defined; no Collector/completed-observability claim or security waiver. Candidate release discovery is not selection or deployment.

## Narrow first implementation selection

The owner's instruction on2026-11-02 explicitly approves implementation after independent review. Slice1 exact selection was independently cleared, root recorded its owner-selection binding before code, then implemented and independently verified it. Local commit `defcb040bc2ee41b6d19a59010e11f8e88d76c8b` contains immutable metadata, build purity gates and tests: pinned Temurin25.0.5_8 Linux60 tests, zero failures/errors/skips; new module62/62 lines and18/18 branches covered; actual source-bound final review and owned cleanup verified. Strict OpenSpec19 items passed. The branch is now `feat/dkp-457-dev-logging`. No push, CI, dev integration or full logger delivery is claimed. Transport/security/database/Collector are outside that selection.

Additional runtime review findings R1–R4 cover parent/scope capacity churn, secret response schema, authenticated-unselected attribution and bounded scoped receipts. `producer-protocol.md` and the revised v1 draft address these for review; they are not marked resolved until exact independent review and verification. All full-runtime selection gates remain open.
