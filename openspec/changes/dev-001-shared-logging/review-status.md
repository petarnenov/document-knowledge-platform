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
- Collector topology, mapping, resource profile, immutable release pin and conformance/security verification remain an explicit full-runtime gate. Registry lookup found candidate contrib0.164.0 multiarch digest `sha256:340c9ca6a36617084c6661bdc4f0fe92b7cda5a805d61673ec8db375fddc522b`; discovery is not a selected/reviewed/deployed dependency.

## Narrow first implementation selection

The owner's instruction on2026-11-02 explicitly approves implementation after independent review. `implementation-slices.md` defines a separately reviewable first slice: immutable framework-free build/execution/event metadata only. Exact independent clearance and recorded owner-selection binding must precede its source edits. This slice grants no HTTP/security/database/Collector approval and does not satisfy the shared logger acceptance by itself. Tests, coverage, pinned Linux verification and independent implementation review remain required.
