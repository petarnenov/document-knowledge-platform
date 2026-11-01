## 1. Specification and independent review — issue366

- [x] 1.1 Independently inspect pinned primary source/API and reproduce same-instance public-config/identity blind spots without reading private state or sending a body.
- [x] 1.2 Draft option B read-only technical amendment, exact closed descriptor, alternatives, proof limits and prospective tests. This is not approval.
- [x] 1.3 Strict all OpenSpec16/16, descriptor28 tests (100% lines/branches), strictAjv2020 one positive/40 negatives and real public API probe3/3 pass; exact candidate frozen for independent root review.
- [x] 1.4 Independent source review375 and written-gate audit398 accepted; exact9beb1d0 development technique approval is recorded separately in approval.md under standing owner authorization. No formal architecture/security/CID/production approval, full CI or runtime acceptance is implied.
- [ ] 1.5 Publish reviewed PR; applicable exact-head CI and review remain required. Do not automatically merge.

## 2. Implementation and acceptance — NOT STARTED by this change

- [ ] 2.1 Only after amendment approval, implement exact read-only metadata validation and fresh per-handoff value reads in the outer BFF boundary; preserve hexagonal dependencies and all341 controls.
- [ ] 2.2 Prove startup/first-byte/current-channel identity/adjacency/serialized callbacks and exact same-instance mutation rejection before body-capable paths; missing/denied/null/wrong artifact/type/layout fails closed.
- [ ] 2.3 Prove no production private write, setter repair, arbitrary field/class lookup, Unsafe/JNI/agent/JVM-opens fallback or public-data disclosure; changed-source line/branch coverage each at least75%.
- [ ] 2.4 Re-run actual unprivileged pinned Linux fixed/chunked/coalesced/pipelined/backpressure/cancellation/protocol and body-bound tests with late ordered mutations and actual readiness latch.
- [ ] 2.5 Re-run fresh current provider/native305/elapsed storage/HTTPS/fullCI and aggregate222/final-hour gates as applicable. No spec/probe result substitutes for runtime acceptance or production sign-off.
