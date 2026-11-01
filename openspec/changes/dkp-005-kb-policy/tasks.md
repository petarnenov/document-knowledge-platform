# DKP-005 tasks — parent #36, specification #167

All tasks remain governed by issue-first ordering. Unnumbered future implementation/review items are planning placeholders, not tasks already authorized/executed or issues claimed created.

## 1. Specification #167

- [x] 1.1 Read integrated BP01/BP02, public System Design, contracts and owner restrictions; preserve acceptance verbatim.
- [x] 1.2 Draft proposal/design, frozen APIs/catalog schema, normative deltas and meaningful real-PG/current-authority/concurrency/E2E verification plan.
- [x] 1.3 Run strict OpenSpec, frozen OpenAPI/catalog/compatibility checks; record actual results and limitations.
- [x] 1.4 Independent architecture [#171](https://github.com/petarnenov/document-knowledge-platform/issues/171) and security [#172](https://github.com/petarnenov/document-knowledge-platform/issues/172) were created before review; resolve their four findings under preexisting [#174](https://github.com/petarnenov/document-knowledge-platform/issues/174) and obtain re-review, including bounded batch budgets and the separately typed authorized-subset member-list eligibility correction.
- [x] 1.5 Record development approval/fingerprint after independent171/172 PASS and root strict/contract checks; see approval.md.
- [x] 1.6 Reviewed specification PR184 integrated as6262d10 after all9 Foundation jobs and hygiene; post-dev41252101000 all9PASS.167/174 specification issues closed, runtime remains open.

## 2. Future implementation issues, created before execution

- [ ] 2.1 [#187](https://github.com/petarnenov/document-knowledge-platform/issues/187) KB framework-free collection/audience/membership/revision domain and application ports/services with meaningful negative/race tests.
- [ ] 2.2 [#188](https://github.com/petarnenov/document-knowledge-platform/issues/188) KB-owned PostgreSQL migrations/roles/atomic policy/audit/idempotency/snapshot/invalidation persistence and real restart/concurrency tests.
- [ ] 2.3 [#189](https://github.com/petarnenov/document-knowledge-platform/issues/189) Explicit DM sharing action/catalog/ACL compatibility and fixed metadata-only authority endpoint/adapter, with direct API negatives and final-boundary revocation tests.
- [ ] 2.4 [#190](https://github.com/petarnenov/document-knowledge-platform/issues/190) Frozen KB inbound routes, current catalog/relation adapters, finite errors/limits/probes and actual auth integration.
- [ ] 2.5 [#191](https://github.com/petarnenov/document-knowledge-platform/issues/191) BFF fixed routes and accessible Mantine portal/SDK/Zod/TanStack tests, preserving existing identity/DM security.
- [ ] 2.6 [#192](https://github.com/petarnenov/document-knowledge-platform/issues/192) Reproducible real DM+KB+PG+Keycloak HTTPS E2E/runtime wiring, independent builds and all current authority/revision fault cases.
- [ ] 2.7 [#193](https://github.com/petarnenov/document-knowledge-platform/issues/193) Publish additive versioned contracts, deterministic SDKs, compatible locks/pins/SBOM and required CI/architecture/dependency/secret/75%line+branch gates.

## 3. Review and completion

- [ ] 3.1 Create independent implementation review issues, resolve findings, rerun affected checks and open reviewed implementation PRs.
- [ ] 3.2 Retain direct verification evidence; integrate only after required review/CI and authorized merge instruction. Owner-authorized automatic dev merging requires green tests/CI, independent review and all applicable gates.
- [ ] 3.3 Audit full BP04 acceptance without dropping unresolved sharing/classification decisions or claiming later readiness; archive approved change only after completion integration.
- [ ] 3.4 Close only verified issues, persist resumable checkpoint and invoke actual current-session /compact if available; otherwise record pending. Continue full-project queue.

- [ ] [#185](https://github.com/petarnenov/document-knowledge-platform/issues/185) P1: latest owner standing automatic dev merge permission after green tests/CI; retain independent review and acceptance gates.

## Independently tracked runtime corrections and adapter boundaries

- [x] [#196](https://github.com/petarnenov/document-knowledge-platform/issues/196) P1: independent pure-model/application review, six original findings and later delegation/pagination corrections re-reviewed PASS;56 tests0skip, no HTTP/PG/integration certification.
- [ ] [#197](https://github.com/petarnenov/document-knowledge-platform/issues/197) P1: six original current-authority/admission corrections; source PASS, integration pending.
- [x] [#200](https://github.com/petarnenov/document-knowledge-platform/issues/200) P1: independent persistence review PASS after203 commit-time seal;33 tests0skip including20 real-PG cases. Integration remains open.
- [ ] [#201](https://github.com/petarnenov/document-knowledge-platform/issues/201) P2: remove unapproved extra caller target-action ACL predicate while preserving explicit delegation ceiling/target grant/current operation; independent source PASS, integration pending.
- [ ] [#202](https://github.com/petarnenov/document-knowledge-platform/issues/202) P1: requested page limits1..50/default20 with full eligibility before limit and continuation after last returned visible row; source PASS, integration pending.
- [ ] [#203](https://github.com/petarnenov/document-knowledge-platform/issues/203) P1: commit-time policy rule-set seal and actual runtime-role valid-late-INSERT rejection; verification/re-review pending.
- [ ] [#204](https://github.com/petarnenov/document-knowledge-platform/issues/204) P1: current catalog/relation/ceiling, strict DM assessment versus eligibility and private encrypted cursor adapters; implementation/tests in progress.

## Additional tracked verification and corrections

- [x] [#205](https://github.com/petarnenov/document-knowledge-platform/issues/205) Independent outbound authority/cursor review PASS, including14 actual Linux TLS tests.
- [ ] [#206](https://github.com/petarnenov/document-knowledge-platform/issues/206) Separate fixed one-shot KB migration process; source tests/review pass, configured runtime integration pending.
- [ ] [#207](https://github.com/petarnenov/document-knowledge-platform/issues/207) Truthful bounded operational readiness; source verified, cross-service acceptance pending.
- [x] [#208](https://github.com/petarnenov/document-knowledge-platform/issues/208) Independent migration review PASS after211, seven actual PG tests and14 privilege-expansion cases; integration pending.
- [x] [#209](https://github.com/petarnenov/document-knowledge-platform/issues/209) Independent readiness review PASS with19 actual Linux tests and all three coverage floors.
- [x] [#210](https://github.com/petarnenov/document-knowledge-platform/issues/210) Independent source/configuration review PASS; actual19 tests/fresh private material/Compose/Nginx syntax; full192 acceptance remains open.
- [ ] [#211](https://github.com/petarnenov/document-knowledge-platform/issues/211) Recheck current privileges on repeated migration startup; source independently accepted, runtime integration pending.
- [ ] [#212](https://github.com/petarnenov/document-knowledge-platform/issues/212) Fixed schema-scoped migration URL compatible with strict process guard.
- [ ] [#213](https://github.com/petarnenov/document-knowledge-platform/issues/213) Reject runtime JDBC URL credential/options overrides.
- [ ] [#214](https://github.com/petarnenov/document-knowledge-platform/issues/214) Independent KB HTTP/bootstrap review;216/217 findings require correction and re-review.
- [ ] [#215](https://github.com/petarnenov/document-knowledge-platform/issues/215) Independent DM sharing review;218 real incomplete-body evidence pending.
- [ ] [#216](https://github.com/petarnenov/document-knowledge-platform/issues/216) P1: actual abort/whole8s deadline must prevent late SQL/facts.
- [ ] [#217](https://github.com/petarnenov/document-knowledge-platform/issues/217) P2: final500ms phase includes validation/response encoding with overrun negative.
- [ ] [#218](https://github.com/petarnenov/document-knowledge-platform/issues/218) P2: real DM half-body timeout and late-byte-release no-SQL proof.

- [ ] [#219](https://github.com/petarnenov/document-knowledge-platform/issues/219) P1 mixed-contract DM error ownership correction, separately approved after220;193 integration pending.
- [x] [#220](https://github.com/petarnenov/document-knowledge-platform/issues/220) Independent narrow219 specification review PASS; all previous KB wire/limits preserved.

## 2026-11-01 01:10 UTC tracked review corrections and consolidation

- [ ] [#227](https://github.com/petarnenov/document-knowledge-platform/issues/227) owned KB runtime lifecycle; source review230 passes after234. Genuine runtime192/integration remain pending.
- [ ] [#228](https://github.com/petarnenov/document-knowledge-platform/issues/228) explicit strict KB browser profile; independent229 source review passes, actual HTTPS execution pending.
- [x] [#230](https://github.com/petarnenov/document-knowledge-platform/issues/230) independent227/234 source review: exact0c86aa1d three-file manifest; independent36tests0skip, runner93.71% source/88.18% branch coverage. This is not runtime acceptance.
- [ ] [#234](https://github.com/petarnenov/document-knowledge-platform/issues/234) committed tracked source only, independently checked clean index and working tree before Docker. Actual tiny-Git untracked/private exclusion and dirty-source regressions pass; integration pending.
- [ ] [#235](https://github.com/petarnenov/document-knowledge-platform/issues/235) independent214 blockers: final response500ms ownership and cumulative2s identity/catalog setup. Author correction/testing active, independent re-review pending.
- [ ] [#236](https://github.com/petarnenov/document-knowledge-platform/issues/236) independent233 blockers: portal stale-cursor recovery, exact406 negotiation and actual raw-socket cancellation. RawRST regression genuinely failed before correction; author fixes/testing active.

193 partial consolidation now uses isolated feat/dkp-005-runtime-integration from integrated dev62a73f0; accepted model/persistence/outbound/migration/infra/DM/contracts/shared-runner packets are copied by verified manifests. KB235/BFF236 HTTP/portal packets remain excluded until independent acceptance. Root build merging retains existing BP03 inspector dependency rejection fixtures alongside KB ownership tests. No provider or full BP04 acceptance is inferred.

- [ ] [#240](https://github.com/petarnenov/document-knowledge-platform/issues/240) independent consolidated build/lock/coverage review; bounded source review has no findings, final source/checks pending.
- [ ] [#243](https://github.com/petarnenov/document-knowledge-platform/issues/243) genuine100-member/current-authority browser acceptance source; independent245 review and actual configured execution remain pending.
- [ ] [#244](https://github.com/petarnenov/document-knowledge-platform/issues/244) actual combined architecture violation corrected with immutable inward URI syntax port and bootstrap exact JDK parser. Independent233 delta PASS,84BFFtests/8architecturetests and all4floors PASS; consolidated Linux verification/integration pending.
- [ ] [#245](https://github.com/petarnenov/document-knowledge-platform/issues/245) independent maximum-size acceptance-source review, in progress.

Current193 integration snapshot includes only independently accepted235/236/244 production and existing two-theme browser source. Unreviewed243 additions remain excluded. Current-source168KBtests pass with zero skips. A macOS combined DM attempt failed15 legacy file-authority cases and skipped18 platform-dependent cases; it is not accepted as Linux proof and no authority checks are weakened. Genuine Linux gates remain required before192 execution and integration.

243 maximum-size browser source is independently accepted245 at5e91d5c8 and now included in the integration snapshot. Its actual configured execution remains required under192;149unit tests, helper8tests100%line/branch and discovery3cases are source evidence only.

## 2026-11-01 integration CI corrections

- [ ] [#251](https://github.com/petarnenov/document-knowledge-platform/issues/251) P1: run both shared browser-runner suites under unchanged75% coverage floors and give only the two complete instrumented DOM workflow cases a20s test budget. Actual baseline reproduced both5s timeouts; corrected149portal tests pass with full-source96.27%lines/93.80%branches/92.91%functions/95.04%statements. Combined runner coverage90.88%lines/99.28%branches/94.44%functions. Final CI/integration remain required.
- [ ] [#252](https://github.com/petarnenov/document-knowledge-platform/issues/252) independent two-file source review PASS at69f7455eae1160722b861cb56d6c69e7da45acc43ef8a5ba792bb9c0592eeb4c. All assertions, coverage floors and actual API deadlines remain unchanged; closure evidence pending.

The exact4519f2bf611c committed integration source passes the combined unprivileged Linux architecture/KB/DM/BFF bootstrap build with all configured coverage gates (133tasks,2m27s). Native-provider cases skipped by this core test profile are not runtime acceptance. The isolated KB runtime, genuine browser scenarios and full cross-domain acceptance remain required under192.

- [ ] [#254](https://github.com/petarnenov/document-knowledge-platform/issues/254) P1: isolate Python/Node security fixtures from the baseline authority directory. Independent257 review accepts test-only16c5439a packet; root independently reran11Python/19Node tests with unchanged75% floors. Actual baseline authority is never removed or adopted. Final CI/integration pending.
- [ ] [#255](https://github.com/petarnenov/document-knowledge-platform/issues/255) P1: canonical relative-filename byte ordering for both Git and snapshot digests. Actual tiny-Git regression fails before correction and37KBtests pass afterward,94.06%lines/89.09%branches. Independent256 accepts8849783a packet. Original failed runtime has zero provider resources and remains preserved; fresh runtime/final CI pending.
- [ ] [#256](https://github.com/petarnenov/document-knowledge-platform/issues/256) independent255 source review PASS; closure evidence pending.
- [ ] [#257](https://github.com/petarnenov/document-knowledge-platform/issues/257) independent254 source review PASS, all hashes and focused tests independently verified; closure evidence pending.

- [ ] [#260](https://github.com/petarnenov/document-knowledge-platform/issues/260) P1: actual Docker default address pools were exhausted after a successful exact-source image build. Reviewed deterministic private/28 allocation preserves all nine logical networks and checks Docker/specific host routes again immediately before startup. Inventory, verify and stop attest exact subnet/gateway/options; no foreign resources or daemon settings change. Independent265 accepts7b6e0831 and the separate unchanged75%-floor workflow tuple wiring. Integrated188Python tests pass; fresh runtime and finalCI remain required.
- [ ] [#263](https://github.com/petarnenov/document-knowledge-platform/issues/263) P1: same40ms blocked-reader test timer starts on the admitted reader; controlled delayed admission reproduces the original source-entry failure. Independent264 accepts testb2ee739b; full BFF build passes. Production300s/30s deadlines and every prior assertion remain unchanged. FinalCI pending.
- [ ] [#264](https://github.com/petarnenov/document-knowledge-platform/issues/264) independent263 bounded source review PASS; closure evidence pending.
- [ ] [#265](https://github.com/petarnenov/document-knowledge-platform/issues/265) independent260 source and strict coverage tuple review PASS; closure evidence pending.
