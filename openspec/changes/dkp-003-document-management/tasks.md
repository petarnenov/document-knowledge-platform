## 1. Specification and review — issue #52, parents #34/#50

- [x] 1.1 Read System Design, current authority defaults and both away decisions; record Option B, resources DENY correction and privileged inspect-draft addendum.
- [x] 1.2 Draft proposal/design/capability deltas and frozen draft OpenAPI; preserve RustFS BP-03/#35/#53/#54 and CID-07/15 gates.
- [x] 1.3 Security review #56 (independent re-review PASS; issue closed after accepted deliverable): independent catalog/ACL/bootstrap/delegation/retention/API security review and resolve findings.
- [x] 1.4 Architecture review #57 (independent re-review PASS; issue closed after accepted deliverable): independent ownership/transaction/contract/version boundary review and resolve findings.
- [x] 1.5 Root #52: strict OpenSpec and contract validation, complete reviewed artifact digest/evidence, then recorded automatic spec approval; no runtime work before approval.

## 2. Runtime implementation — NOT STARTED; approved specification required gates satisfied

Runtime issues #58–#63 were created by root before implementation. They remain unstarted; the development specification is approved in approval.md after resolved reviews. Runtime acceptance and independent implementation review remain outstanding. Any remaining UNCREATED placeholder requires its own prioritized issue before execution. No checklist line constitutes approval to start.

- [ ] 2.1 [issues #58; unstarted; approved scope, verification outstanding] Catalog adapter/config/bootstrap: strict bounded schema, exact actor/tenant/actions/delegation ceiling, immutable read-only ignored file, no wildcard/admin/owner grants; malformed/missing/unreadable/oversize503, no action403, no stale-positive decisions, external unavailable. Add ≥75% source coverage and real file mutation/revocation tests.
- [ ] 2.2 [issues #59; unstarted; approved scope, verification outstanding] DM domain/application: draft creation, explicit creator ACL bootstrap, inspect/list/update, self-restriction/delegation with ceiling and target validation, legal hold place/inspect, unresolved retention. Tests membership-without-operation, operation-without-ACL, deny wins, read-never-draft, same-sub-different-issuer, no privilege escalation and holds/destructivefalse.
- [ ] 2.3 [issues #60; unstarted; approved scope, verification outstanding] DM PostgreSQL persistence: pinned runtime/driver, isolated migration/runtime roles, qualified migration lifecycle, migrations/constraints, prepared tenant-scoped SQL, CAS and atomic idempotency/audit writes. Real PostgreSQL tests double-CAS, concurrent same-key, conflicting body/revision409, rollback failure injection, final authorization races, DML-role DDL/public/cross-database denial, fsync and persistent restart.
- [ ] 2.4 [issues #59/#60/#61; unstarted; approved scope, verification outstanding] Immutable versions and verified-artifact port: real runtime UNAVAILABLE until BP-03 RustFS; authorized public append503; caller storage pointers rejected; explicit test-only receipt verifies same-document constraints, numbering, immutable content and unchanged active pointer.
- [ ] 2.5 [issues #61; unstarted; approved scope, verification outstanding] Frozen contract implementation: publish reviewed OpenAPI, regenerate/check Java/TS SDKs, bounds/unknown-field/error/idempotency/status parity tests, policy-safe bounded cursor/list behavior and key bootstrap.
- [ ] 2.6 [issues #62; unstarted; approved scope, verification outstanding] BFF/portal: only fixed DM routes; Origin+CSRF on every unsafe method; safe server credentials/selector; real draft create/inspect/update/ACL/hold UI; no invented upload/publication success. Accessible negative/expiry/unavailable states and ≥75% changed-source coverage; exact TanStack Query5.105.0/Zod4.6.7 pins, all server state in Query, Zod boundary validation, tenant/logout cache clearing and mutation invalidation tests.
- [ ] 2.7 [issues #63; unstarted; approved scope, verification outstanding] Local PG and synthetic catalog bootstrap: generated secret material ignored, restrictive file permissions, runtime read-only catalog, only used dependencies; no RustFS/object runtime yet; no global trust changes. Test catalog/DB missing/invalid configurations fail closed.
- [ ] 2.8 [issues #63/#62; unstarted; approved scope, verification outstanding] Integrated verification: real Keycloak+HTTPS BFF create→list/inspect→metadata CAS→ACL restrict/delegate→hold inspect; tenant isolation and direct API tests; real PG restart evidence; all changed source ≥75% coverage; no skipped acceptance or fabricated data.
- [ ] 2.9 [issues #63; unstarted; approved scope, verification outstanding] CI/supply-chain/docs: effective pinned dependency locks, scans, architecture negative fixtures, contract reproducibility, migrations/backup/rollback and gate status; document local policy is not production/compliance approval.

## 3. Delivery — NOT STARTED

- [ ] 3.1 [child issue UNCREATED; unstarted] Independent implementation/security reviews and fixes; applicable strict/contract/unit/PG/E2E/coverage/CI gates pass.
- [ ] 3.2 [child issue UNCREATED; unstarted] Open reviewed PR into dev, evidence and rollback; authorized merge only after independent review and checks. Close issues only after their actual verified criteria; save handoff and compaction pending if current-session /compact unavailable.
- [ ] 3.3 [child issue UNCREATED; unstarted] Archive accepted change after integration; update full-project queue to BP-03/#35 RustFS, keep CID-07/CID-15 and production gates open.

## 4. Amendment regression matrix — existing issues #59/#60/#61/#62/#63, review #56/#57

Supersedes initial mode+rules/PUT ACL shape and inspection-bearing mutation bodies per away run 20261031T163854Z-claude-72144. No runtime approval implied.

- [ ] 4.1 #61/#62: contract tests all five mutation families, DM+BFF, body EXACT documentId/revision; create201/update200/ACL PATCH200/hold201; append future201 schema only, actual503 UNAVAILABLE until BP-03. No PUT ACL. No inspect-only facts after inspect revocation or in replay.
- [ ] 4.2 #59/#60: RESTRICT own REMOVE_ALLOW/ADD_DENY only; forbidden principal/operation422 RESTRICT_SCOPE; self-removing acl.restrict returns receipt then replay404 on current ACL.
- [ ] 4.3 #59/#60/#61: DELEGATE four operations, target same-tenant action422 TARGET_NOT_GRANTABLE, caller ceiling403 DELEGATION_CEILING; ADD_ALLOW preserves DENY; bounded REMOVE_DENY; unrelated rules preserved.
- [ ] 4.4 #59/#60: duplicate principal/action across operations422 DUPLICATE_ACL_CHANGE before transaction; resulting unique-rule count101=>422 ACL_RULE_LIMIT rollback; existing ADD/absent REMOVE no-op still revision/audit/idempotency.
- [ ] 4.5 #61/#63: existing/nonexistent GET detail/ACL/hold lacking inspection action identical404 before lookup; list/create/mutation missing action403 before lookup; missing/malformed catalog503 precedes decision; resource ACL deny/missing404; stale409 only after authorization.
- [ ] 4.6 #59/#61/#63: replay rechecks current membership/action/ACL (create catalog only), never inspect authority; current denial returns fresh403/404, never stored body. Independent amended spec re-review #56/#57 before approval.
