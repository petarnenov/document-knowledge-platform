## 1. Specification and review — issue #52, parents #34/#50

- [x] 1.1 Read System Design, current authority defaults and both away decisions; record Option B, resources DENY correction and privileged inspect-draft addendum.
- [x] 1.2 Draft proposal/design/capability deltas and frozen draft OpenAPI; preserve RustFS BP-03/#35/#53/#54 and CID-07/15 gates.
- [x] 1.3 Security review #56 (independent re-review PASS; issue closed after accepted deliverable): independent catalog/ACL/bootstrap/delegation/retention/API security review and resolve findings.
- [x] 1.4 Architecture review #57 (independent re-review PASS; issue closed after accepted deliverable): independent ownership/transaction/contract/version boundary review and resolve findings.
- [x] 1.5 Root #52: strict OpenSpec and contract validation, complete reviewed artifact digest/evidence, then recorded automatic spec approval; no runtime work before approval.

## 2. Runtime implementation — IN PROGRESS; approval preceded source changes

Runtime issues #58–#63 were created by root before implementation. Implementation is underway after approved spec commit6e53b810 and integrated PR64/fb08ff4. No implementation item is marked complete until all acceptance, review and integration gates pass. Runtime acceptance and independent implementation review remain outstanding. Any remaining UNCREATED placeholder requires its own prioritized issue before execution. No checklist line constitutes approval to start.

- [ ] 2.1 [issues #58; in progress; review/integration verification outstanding] Catalog adapter/config/bootstrap: strict bounded schema, exact actor/tenant/actions/delegation ceiling, immutable read-only ignored file, no wildcard/admin/owner grants; malformed/missing/unreadable/oversize503, no action403, no stale-positive decisions, external unavailable. Add ≥75% source coverage and real file mutation/revocation tests.
- [ ] 2.2 [issues #59; in progress; review/integration verification outstanding] DM domain/application: draft creation, explicit creator ACL bootstrap, inspect/list/update, self-restriction/delegation with ceiling and target validation, legal hold place/inspect, unresolved retention. Tests membership-without-operation, operation-without-ACL, deny wins, read-never-draft, same-sub-different-issuer, no privilege escalation and holds/destructivefalse.
- [ ] 2.3 [issues #60; in progress; review/integration verification outstanding] DM PostgreSQL persistence: pinned runtime/driver, isolated migration/runtime roles, qualified migration lifecycle, migrations/constraints, prepared tenant-scoped SQL, CAS and atomic idempotency/audit writes. Real PostgreSQL tests double-CAS, concurrent same-key, conflicting body/revision409, rollback failure injection, final authorization races, DML-role DDL/public/cross-database denial, fsync and persistent restart.
- [ ] 2.4 [issues #59/#60/#61; in progress; review/integration verification outstanding] Immutable versions and verified-artifact port: real runtime UNAVAILABLE until BP-03 RustFS; authorized public append503; caller storage pointers rejected; explicit test-only receipt verifies same-document constraints, numbering, immutable content and unchanged active pointer.
- [ ] 2.5 [issues #61/#65/#66/#67/#72; in progress; review/integration verification outstanding] Frozen contract implementation: publish reviewed OpenAPI, regenerate/check Java/TS SDKs, bounds/unknown-field/error/idempotency/status parity tests, policy-safe bounded cursor/list behavior and key bootstrap.
- [ ] 2.6 [issues #62; in progress; review/integration verification outstanding] BFF/portal: only fixed DM routes; Origin+CSRF on every unsafe method; safe server credentials/selector; real draft create/inspect/update/ACL/hold UI; no invented upload/publication success. Accessible negative/expiry/unavailable states and ≥75% changed-source coverage; exact TanStack Query5.105.0/Zod4.6.7 pins, all server state in Query, Zod boundary validation, tenant/logout cache clearing and mutation invalidation tests.
- [ ] 2.7 [issues #63; in progress; review/integration verification outstanding] Local PG and synthetic catalog bootstrap: generated secret material ignored, restrictive file permissions, runtime read-only catalog, only used dependencies; no RustFS/object runtime yet; no global trust changes. Test catalog/DB missing/invalid configurations fail closed.
- [ ] 2.8 [issues #63/#62; in progress; review/integration verification outstanding] Integrated verification: real Keycloak+HTTPS BFF create→list/inspect→metadata CAS→ACL restrict/delegate→hold inspect; tenant isolation and direct API tests; real PG restart evidence; all changed source ≥75% coverage; no skipped acceptance or fabricated data.
- [ ] 2.9 [issues #63; in progress; review/integration verification outstanding] CI/supply-chain/docs: effective pinned dependency locks, scans, architecture negative fixtures, contract reproducibility, migrations/backup/rollback and gate status; document local policy is not production/compliance approval.

## 3. Delivery — IN PROGRESS

- [ ] 3.1 [issues #68/#71, fixes #69/#70/#72; in progress] Independent implementation/security reviews and fixes; applicable strict/contract/unit/PG/E2E/coverage/CI gates pass.
- [ ] 3.2 [issue #75; in progress] Open reviewed PR into dev, evidence and rollback; merge requires review and the current owner instruction; automatic merging is disabled by the latest supplied AGENTS.md. Close issues only after their actual verified criteria; save handoff and compaction pending if current-session /compact unavailable.
- [ ] 3.3 [child issue UNCREATED; unstarted] Archive accepted change after integration; update full-project queue to BP-03/#35 RustFS, keep CID-07/CID-15 and production gates open.

## 4. Amendment regression matrix — existing issues #59/#60/#61/#62/#63, review #56/#57

Supersedes initial mode+rules/PUT ACL shape and inspection-bearing mutation bodies per away run 20261031T163854Z-claude-72144. No runtime approval implied.

- [ ] 4.1 #61/#62: contract tests all five mutation families, DM+BFF, body EXACT documentId/revision; create201/update200/ACL PATCH200/hold201; append future201 schema only, actual503 UNAVAILABLE until BP-03. No PUT ACL. No inspect-only facts after inspect revocation or in replay.
- [ ] 4.2 #59/#60: RESTRICT own REMOVE_ALLOW/ADD_DENY only; forbidden principal/operation422 RESTRICT_SCOPE; self-removing acl.restrict returns receipt then replay404 on current ACL.
- [ ] 4.3 #59/#60/#61: DELEGATE four operations, target same-tenant action422 TARGET_NOT_GRANTABLE, caller ceiling403 DELEGATION_CEILING; ADD_ALLOW preserves DENY; bounded REMOVE_DENY; unrelated rules preserved.
- [ ] 4.4 #59/#60: duplicate principal/action across operations422 DUPLICATE_ACL_CHANGE before transaction; resulting unique-rule count101=>422 ACL_RULE_LIMIT rollback; existing ADD/absent REMOVE no-op still revision/audit/idempotency.
- [ ] 4.5 #61/#63: existing/nonexistent GET detail/ACL/hold lacking inspection action identical404 before lookup; list/create/mutation missing action403 before lookup; missing/malformed catalog503 precedes decision; resource ACL deny/missing404; stale409 only after authorization.
- [ ] 4.6 #59/#61/#63: replay rechecks current membership/action/ACL (create catalog only), never inspect authority; current denial returns fresh403/404, never stored body. Independent amended spec re-review #56/#57 before approval.

## Execution checkpoint — 2026-10-31 17:18 UTC

Spec tasks #50/#52 are closed after reviewed PR64 and integrated-dev41221800467 all9PASS. Runtime #58–#63, SDKfix#65, HTTPtests#66 and wiring#67 remain open. Review#68 requests catalog-path#69 and final-authority#70 fixes; review#71 requires cursor-key-path#72. Module, PG, browser and SDK checks have passing evidence; full HTTP, real stack/E2E, CI, re-review and integration are outstanding. No object-store runtime; RustFS BP03/#35 remains first.

- [ ] #69/#72: implement/review strict shared path-integrity helper per away16968; macOS host fails closed by design, real-platform authority integration runs in Linux CI/Compose. No subprocess ACL probe; test-only platform injection never becomes runtime override. Verify Linux default plus unsupported-platform failure and >=75% helper coverage.

- [ ] #73/#60: protected catalog bridge test fixture tempdir after strict ancestor policy; real LinuxSYSTEMcatalog11PASS0skip, PG11PASS, bridge rerun and independent#68 review pending.

- [x] #68: independent stable catalog/domain/PG/SDK/build/HMAC source+evidence review PASS after69/70/73 corrections and actualLinux evidence; pure review deliverableclosed. FullHTTP/OOM#74, root#71 andrealstack gates remainopen.

- [ ] #74/#67/#71: bounded frameworkInputStream request path per away38028; focusedLinux4216negative andmaximumvalidmetadataPASS/noOOM, oversize413mappingpending; all16HTTP+legacy/coverage andindependentreviewrequired. Noheapincrease/coverageoff/skip.

- [ ] #76: external/unconfiguredfactorybindingsnegative tests35bootstrapPASS/no skips; Factory100%L87.5%B, root71source+evidence review pending final acceptance.
- [ ] #77: strengthen actualbrowserACLjourney fromnoops to persistedreadruleaddition/removal; source+format/typecheckready, realrerunpending.
- [ ] #78: actualComposePGoutage revealedreadinesseventloop/longpoolwait; DBrestoredviafinallyafterboundeddiagnostics, correctedreadiness/offload+pooltimeoutsandrealPGnegative test pending. No outagePASSor weakenedgate.

- [ ] #79: deterministic database-side SHA-256 over all seven owned business tables; runtime19+bootstrap5 tests PASS, checker90.24% lines/92.86% branches; real outage/restart acceptance pending.

## Execution checkpoint — 2026-10-31 18:07 UTC

#78 focused real paused-PostgreSQL Linux test PASS20s: default acquisition1000ms/validation500ms, disabled secondary datasource unchanged, one event-loop worker, concurrent liveness, readiness503 within3s, same-endpoint recovery. Full Linux suite and actual Compose/E2E/outage checks are in progress. No issue closure or integration claimed.
