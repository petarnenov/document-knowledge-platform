## 1. Specification and review — issue #52, parents #34/#50

- [x] 1.1 Read System Design, current authority defaults and both away decisions; record Option B, resources DENY correction and privileged inspect-draft addendum.
- [x] 1.2 Draft proposal/design/capability deltas and frozen draft OpenAPI; preserve RustFS BP-03/#35/#53/#54 and CID-07/15 gates.
- [x] 1.3 Security review #56 (independent re-review PASS; issue closed after accepted deliverable): independent catalog/ACL/bootstrap/delegation/retention/API security review and resolve findings.
- [x] 1.4 Architecture review #57 (independent re-review PASS; issue closed after accepted deliverable): independent ownership/transaction/contract/version boundary review and resolve findings.
- [x] 1.5 Root #52: strict OpenSpec and contract validation, complete reviewed artifact digest/evidence, then recorded automatic spec approval; no runtime work before approval.

## 2. Runtime implementation — VERIFIED AND INTEGRATED

Runtime issues were created before execution. Approved specification6e53b810/PR64 preceded runtime source. Reviewed implementation PR80 merged5b35b8e after all11checks passed; integrated-dev41228406275 all9jobs passed. Implementation/fix issues are closed with verified evidence and resumable handoffs. Completion81/review82 remain in progress. No production approval is implied.

- [x] 2.1 [issues #58; verified, issue closed after PR80 and integrated-dev CI] Catalog adapter/config/bootstrap: strict bounded schema, exact actor/tenant/actions/delegation ceiling, immutable read-only ignored file, no wildcard/admin/owner grants; malformed/missing/unreadable/oversize503, no action403, no stale-positive decisions, external unavailable. Add ≥75% source coverage and real file mutation/revocation tests.
- [x] 2.2 [issues #59; verified, issue closed after PR80 and integrated-dev CI] DM domain/application: draft creation, explicit creator ACL bootstrap, inspect/list/update, self-restriction/delegation with ceiling and target validation, legal hold place/inspect, unresolved retention. Tests membership-without-operation, operation-without-ACL, deny wins, read-never-draft, same-sub-different-issuer, no privilege escalation and holds/destructivefalse.
- [x] 2.3 [issues #60; verified, issue closed after PR80 and integrated-dev CI] DM PostgreSQL persistence: pinned runtime/driver, isolated migration/runtime roles, qualified migration lifecycle, migrations/constraints, prepared tenant-scoped SQL, CAS and atomic idempotency/audit writes. Real PostgreSQL tests double-CAS, concurrent same-key, conflicting body/revision409, rollback failure injection, final authorization races, DML-role DDL/public/cross-database denial, fsync and persistent restart.
- [x] 2.4 [issues #59/#60/#61; verified, issue closed after PR80 and integrated-dev CI] Immutable versions and verified-artifact port: real runtime UNAVAILABLE until BP-03 RustFS; authorized public append503; caller storage pointers rejected; explicit test-only receipt verifies same-document constraints, numbering, immutable content and unchanged active pointer.
- [x] 2.5 [issues #61/#65/#66/#67/#72; verified, issue closed after PR80 and integrated-dev CI] Frozen contract implementation: publish reviewed OpenAPI, regenerate/check Java/TS SDKs, bounds/unknown-field/error/idempotency/status parity tests, policy-safe bounded cursor/list behavior and key bootstrap.
- [x] 2.6 [issues #62; verified, issue closed after PR80 and integrated-dev CI] BFF/portal: only fixed DM routes; Origin+CSRF on every unsafe method; safe server credentials/selector; real draft create/inspect/update/ACL/hold UI; no invented upload/publication success. Accessible negative/expiry/unavailable states and ≥75% changed-source coverage; exact TanStack Query5.105.0/Zod4.6.7 pins, all server state in Query, Zod boundary validation, tenant/logout cache clearing and mutation invalidation tests.
- [x] 2.7 [issues #63; verified, issue closed after PR80 and integrated-dev CI] Local PG and synthetic catalog bootstrap: generated secret material ignored, restrictive file permissions, runtime read-only catalog, only used dependencies; no RustFS/object runtime yet; no global trust changes. Test catalog/DB missing/invalid configurations fail closed.
- [x] 2.8 [issues #63/#62; verified, issue closed after PR80 and integrated-dev CI] Integrated verification: real Keycloak+HTTPS BFF create→list/inspect→metadata CAS→ACL restrict/delegate→hold inspect; tenant isolation and direct API tests; real PG restart evidence; all changed source ≥75% coverage; no skipped acceptance or fabricated data.
- [x] 2.9 [issues #63; verified, issue closed after PR80 and integrated-dev CI] CI/supply-chain/docs: effective pinned dependency locks, scans, architecture negative fixtures, contract reproducibility, migrations/backup/rollback and gate status; document local policy is not production/compliance approval.

## 3. Delivery — IMPLEMENTATION INTEGRATED; ARCHIVE IN PROGRESS

- [x] 3.1 [issues #68/#71, fixes #69/#70/#72; verified/closed] Independent implementation/security reviews and fixes; applicable strict/contract/unit/PG/E2E/coverage/CI gates pass.
- [x] 3.2 [issue #75; verified/closed] Open reviewed PR into dev, evidence and rollback; merge requires review and the current owner instruction; automatic merging is disabled by the latest supplied AGENTS.md. Close issues only after their actual verified criteria; save handoff and compaction pending if current-session /compact unavailable.
- [x] 3.3 [issue #81; verified/closed] Archive accepted change after integration; update full-project queue to BP-03/#35 RustFS, keep CID-07/CID-15 and production gates open.

## 4. Amendment regression matrix — existing issues #59/#60/#61/#62/#63, review #56/#57

Supersedes initial mode+rules/PUT ACL shape and inspection-bearing mutation bodies per away run 20261031T163854Z-claude-72144. No runtime approval implied.

- [x] 4.1 #61/#62: contract tests all five mutation families, DM+BFF, body EXACT documentId/revision; create201/update200/ACL PATCH200/hold201; append future201 schema only, actual503 UNAVAILABLE until BP-03. No PUT ACL. No inspect-only facts after inspect revocation or in replay.
- [x] 4.2 #59/#60: RESTRICT own REMOVE_ALLOW/ADD_DENY only; forbidden principal/operation422 RESTRICT_SCOPE; self-removing acl.restrict returns receipt then replay404 on current ACL.
- [x] 4.3 #59/#60/#61: DELEGATE four operations, target same-tenant action422 TARGET_NOT_GRANTABLE, caller ceiling403 DELEGATION_CEILING; ADD_ALLOW preserves DENY; bounded REMOVE_DENY; unrelated rules preserved.
- [x] 4.4 #59/#60: duplicate principal/action across operations422 DUPLICATE_ACL_CHANGE before transaction; resulting unique-rule count101=>422 ACL_RULE_LIMIT rollback; existing ADD/absent REMOVE no-op still revision/audit/idempotency.
- [x] 4.5 #61/#63: existing/nonexistent GET detail/ACL/hold lacking inspection action identical404 before lookup; list/create/mutation missing action403 before lookup; missing/malformed catalog503 precedes decision; resource ACL deny/missing404; stale409 only after authorization.
- [x] 4.6 #59/#61/#63: replay rechecks current membership/action/ACL (create catalog only), never inspect authority; current denial returns fresh403/404, never stored body. Independent amended spec re-review #56/#57 before approval.

## Execution checkpoint — 2026-10-31 17:18 UTC

Spec tasks #50/#52 are closed after reviewed PR64 and integrated-dev41221800467 all9PASS. Runtime #58–#63, SDKfix#65, HTTPtests#66 and wiring#67 remain open. Review#68 requests catalog-path#69 and final-authority#70 fixes; review#71 requires cursor-key-path#72. Module, PG, browser and SDK checks have passing evidence; full HTTP, real stack/E2E, CI, re-review and integration are outstanding. No object-store runtime; RustFS BP03/#35 remains first.

- [x] #69/#72: implement/review strict shared path-integrity helper per away16968; macOS host fails closed by design, real-platform authority integration runs in Linux CI/Compose. No subprocess ACL probe; test-only platform injection never becomes runtime override. Verify Linux default plus unsupported-platform failure and >=75% helper coverage.

- [x] #73/#60: protected catalog bridge test fixture tempdir after strict ancestor policy; real LinuxSYSTEMcatalog11PASS0skip, PG11PASS, bridge rerun and independent#68 review pending.

- [x] #68: independent stable catalog/domain/PG/SDK/build/HMAC source+evidence review PASS after69/70/73 corrections and actualLinux evidence; pure review deliverableclosed. FullHTTP/OOM#74, root#71 andrealstack gates remainopen.

- [x] #74/#67/#71: bounded frameworkInputStream request path per away38028; focusedLinux4216negative andmaximumvalidmetadataPASS/noOOM, oversize413mappingpending; all16HTTP+legacy/coverage andindependentreviewrequired. Noheapincrease/coverageoff/skip.

- [x] #76: external/unconfiguredfactorybindingsnegative tests35bootstrapPASS/no skips; Factory100%L87.5%B, root71source+evidence review pending final acceptance.
- [x] #77: strengthen actualbrowserACLjourney fromnoops to persistedreadruleaddition/removal; source+format/typecheckready, realrerunpending.
- [x] #78: actualComposePGoutage revealedreadinesseventloop/longpoolwait; DBrestoredviafinallyafterboundeddiagnostics, correctedreadiness/offload+pooltimeoutsandrealPGnegative test pending. No outagePASSor weakenedgate.

- [x] #79: deterministic database-side SHA-256 over all seven owned business tables; runtime19+bootstrap5 tests PASS, checker90.24% lines/92.86% branches; real outage/restart acceptance pending.

## Execution checkpoint — 2026-10-31 18:07 UTC

#78 focused real paused-PostgreSQL Linux test PASS20s: default acquisition1000ms/validation500ms, disabled secondary datasource unchanged, one event-loop worker, concurrent liveness, readiness503 within3s, same-endpoint recovery. Full Linux suite and actual Compose/E2E/outage checks are in progress. No issue closure or integration claimed.

## Verified integration checkpoint — 2026-10-31 18:19 UTC

All historical provisional/pending checkpoints above are superseded by PR80 head699411a5b9f7d3bcf1a2cb4ae48c2046b4b23980 all11checksPASS, merge5b35b8eb4fb3930678e5601cfb8b0f05fa8966fe and integrated-dev Foundation41228406275 all9jobsPASS. Runtime/fix issues58–63,65–67,69/70,72–74,76–79 and review71/delivery75 closed after verified integration; review68 already closed. Source36bootstrap18HTTP0skip96.43%lines93.99%branches; realHTTPS actualACLjourney4.1s and seven-table actualDBoutage/recovery PASS. Full requirement audit in verification.md. Completion81 and independent completion review82 remain open; parent34 closes after final archive PR/CI integration. Goal continues to BP03/#35 RustFS and the complete queue.

## Final publication checkpoint — 2026-10-31 18:32 UTC

CompletionPR85 headb4bf61b3935da5099b72ccc6746caa24d8936413 all11checksPASS merged3de7840728c126eb3f9efdb90f568013437af8e2. Final integrated-dev Foundation41229510527 all9jobsPASS. Independentcompletion82PASS; corrections83/84, completion81 and parent34 closed after verified acceptance. Finaltask3.3 nowcomplete;30/31archive-commandoutput above honestly described an in-progress publication checkpoint. FullgoalcontinuesBP03RustFS with86/87/88/89/90; no production/CID07/CID15approval.
