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

## Subsequent exact development selection and actual local evidence

The full development definition was subsequently independently reviewed and selected under the owner's explicit after-review implementation approval, before new service code. Local specification commit `de413f7225388ee8ee00db48c253ae022b603814` records that revision; the initial findings above remain historical context and implementation-proof obligations, rather than a claim that the original incomplete draft was approved. Private exact source/validation/review/owner-selection bindings are retained outside this repository. This is development selection only, with no architecture, security-control or production sign-off inferred.

Local implementation commit `3fe6670` was independently checked against the actual pinned Linux source,86 passing tests, coverage reports and owned PostgreSQL/test-container/network/image cleanup. This verifies the domain/application, migration and JDBC commit/receipt slice; it does not satisfy all of2.1 or #457. Earlier RED test-fixture results remain retained with their corrections. No PR, CI, dev integration or full logger acceptance is claimed.

J2 was independently resolved at the definition level before query implementation: global SQL sequence values are excluded from StoredEvent responses, and reader/filter/expiry-bound cursors require authenticated encryption. Actual cursor/interleaved-tenant proof remains pending. J3's misleading test name and J4's duplicate receipt IDs were corrected and tested. J1 remains OPEN before transport wiring: driver/server per-step timeouts cannot prove the five-second whole-request deadline. The new J1 executor/deadline/actual-worker-ownership definition in `producer-protocol.md` is proposed for exact independent review before its implementation; real stalled/trickled/late-commit acceptance remains mandatory.
