# Draft verification evidence — task #52

2026-10-31; specification author only. No approval, runtime tests or implementation completion claimed.

- Official installed OpenSpec CLI from completed DKP-002 worktree: `openspec validate dkp-003-document-management --strict` PASS after canonical BP-01 archive integrated into this worktree.
- Repository Redocly config: draft OpenAPI valid. Initial lint found a missing-server warning (corrected to relative server) and two expected no-2xx warnings on intentionally unavailable BP-02 version-append operations. Do not invent a success response merely to silence lint: BP-03 verified RustFS receipt authority is absent.
- JSON grant catalog schema accompanies the frozen draft. File/ownership/size bounds, duplicate actor keys, delegation cross-entry consistency and reauthorization require runtime policy tests; JSON schema alone does not prove these.
- Security review #56 and architecture review #57 pending. Reviewed artifact digest and approval record belong to root after resolved reviews; runtime child issues remain uncreated/unstarted.
- Baseline branch now contains archived BP-01 canonical specs. Only this change directory is authored by this task; no runtime source, SDK output, credentials or database artifacts created. No commit.

## Amendment verification — pending independent re-review #56/#57

Decision run 20261031T163854Z-claude-72144 replaces mode+rules/PUT ACL and all inspection-bearing command success responses. CommandReceipt is EXACT {documentId,revision}. AclChange changes[] is an explicit delta with mode retained. Future append201 is documented as receipt but runtime remains503 until BP-03/RustFS; therefore previous intentional no-2xx lint warnings are superseded by the explicit future response documentation.

Required runtime regression evidence (not run in this specification task):

| Scenario | Required outcome |
|---|---|
| All five mutation families / both DM+BFF | Only documentId/revision success keys; no lifecycle/creator/timestamps/metadata/ACL/retention/holdId |
| Mutation without inspection / replay after inspect revocation | Only original receipt; inspect GET404 |
| Self-restrict removes own operation ALLOW | Initial receipt commits; later current-ACL replay404 |
| Duplicate principal/action delta key |422 DUPLICATE_ACL_CHANGE before transaction |
| Resulting rule count101 |422 ACL_RULE_LIMIT, complete rollback |
| Existing ADD / absent REMOVE | No-op rule state but revision/audit/idempotency committed |
| RESTRICT another principal / forbidden operation |422 RESTRICT_SCOPE |
| DELEGATE target not granted / outside ceiling |422 TARGET_NOT_GRANTABLE /403 DELEGATION_CEILING |
| ADD_ALLOW over DENY / REMOVE_DENY | Deny preserved; removal only bounded delegate path |
| Unrelated ACL rules | Preserved through delta |
| GET detail/ACL/hold lacking route inspection grant | Identical404 for existing/nonexistent, before lookup |
| List/create/mutation lacking action |403 before lookup |
| Missing/malformed catalog |503 before action decision; no stale allow |
| Missing document / ACL missing ALLOW or DENY | Identical404 |
| Stale revision / conflicting replay |409 only after current authority |

Security review #56 and architecture review #57 must independently re-review the amended draft before root approval. No runtime code or commit created.

Amendment static checks executed: exact decision Python assertion prints OK; design grep hits all four required error codes; strict OpenSpec PASS after splitting lengthy requirements into focused normative requirements/scenarios; Redocly valid with no warnings once future version201 was explicitly documented as unavailable at runtime. No runtime tests performed. Re-review #56/#57 pending.

## Root approval checkpoint

Independent security re-review #56 and architecture re-review #57: PASS after all original findings resolved; review issues closed after accepted deliverables. Root repeated all-project strict OpenSpec (7/7), configured Redocly (valid, zero warnings), exact CommandReceipt/ACL PATCH/ten DM+BFF mutation-success assertions and git diff --check: PASS. Frozen pnpm install passed without lock/package changes. Earlier draft-author/pending-review statements above describe their historical checkpoints and are superseded by this root checkpoint and approval.md.

Runtime issues #58–#63 were created before execution and remain unstarted at approval. Metadata status/checklist updates after re-review do not alter the reviewed contract or normative requirements. Production, runtime coverage/PG/E2E/independent implementation review remain outstanding.

## Runtime acceptance — 2026-10-31 18:08 UTC, issues #58–#79

Approved specification commit `6e53b8108d3ec7d9a473074feece3e596a1bc42e` preceded runtime implementation; specification PR64 merged as `fb08ff4f6c676b7105a5a20eccd4b5f2f8f536ac`, integrated-dev Foundation41221800467 passed all nine jobs. This checkpoint records local implementation acceptance. Implementation PR, final-head CI and dev integration remain pending under #75; implementation issues remain open.

### Executed verification

| Gate | Observed result |
|---|---|
| Linux DM bootstrap and all-layer coverage | PASS29s, 36 bootstrap tests, zero failures/errors/skips: 18 HTTP, 7 cursor, 6 security, 2 probes, 2 factory bindings, 1 readiness dependency. Service source 405/420 lines (96.43%), 344/366 branches (93.99%). |
| Catalog and complete authority-path policy | Linux 11 tests PASS with no skips; 92.47% lines/88.68% branches. Real default Linux provider covered. Unsupported macOS ACL inspection fails closed; the unit-test platform seam is not runtime configuration. |
| Domain/application | Domain4 tests PASS, 96.30% lines/100% branches; application19 PASS, 95.71%/91.96%; DocumentService99.29%/95.10%. |
| Real PostgreSQL persistence | 11 PostgreSQL tests plus catalog bridge PASS, no skips; 93.33% lines/88.46% branches. Runtime/migration privileges, concurrent keys/CAS, rollback, relational ownership, replay/revocation and actual server restart tested. |
| BFF | 27 tests PASS; source333/351 lines (94.87%), 493/586 branches (84.13%); changed classes individually meet75%. |
| Portal | 58 tests PASS; source98.58% lines/96.62% branches; strict TypeScript, lint, formatting, build and four built-portal Playwright tests PASS. |
| Real HTTPS provider/browser journey | PASS4.1s with actual Keycloak, trusted CA/hostname verification, BFF and PostgreSQL. Draft create/list/inspect/update; ACL READ initially absent, DELEGATE adds actual rule and fresh GET/UI confirms revision2, RESTRICT removes it and fresh GET/UI confirms revision3; hold revision4; CSRF/Origin negatives; append503 without state change; logout/cache cleared. No mocked backend. |
| Actual Compose outage/recovery | PASS after real authenticated data exists. Seven-table database-side SHA-256 snapshot before/after PostgreSQL stop/start matches; readiness503 during outage, liveness200, readiness200 after recovery; scoped privileges and durability rechecked. All seven containers healthy after recovery. |
| Paused PostgreSQL regression | Focused PASS20s, then included in full36-test PASS. One Netty worker; readiness waits off-thread, concurrent liveness returns within750ms, readiness503 within original3s, same endpoint recovers after unpause. Default pool acquisition1000ms/validation500ms; disabled secondary configuration retains its bounds without creating a pool. Missing-database and external-binding tests still pass. |
| Runtime/bootstrap Python | Runtime19 + bootstrap5 PASS; checker90.24% source lines/92.86% branches. Complete snapshot query, invalid/raw digest rejection, changed-state failure and guaranteed recovery paths covered. |
| Architecture/shared security | Root architecture (including all seven owner negative fixtures), identity/security modules and BFF/KB/Search builds PASS21s. |
| Contracts/SDK | Four published contracts checked;13 contract tests PASS; frozen DM contract equality, all ten mutation receipts, deterministic generation/drift and Java/TypeScript SDK compilation PASS. Null-only helper100% line/branch/function coverage. |
| Specifications/dependencies | Strict OpenSpec7/7 PASS; configured Redocly zero warnings; dependency compatibility PASS; pinned OSV scanner27 manifests, no issues. |

### Requirement/scenario audit

- `document-management`: domain/application and real HTTP/PG tests cover metadata-only lifecycle, tenant/issuer isolation, forged input, unresolved retention and immutable holds, unavailable public append, verified test-only version append, immutable version numbering/same-document pointer constraints, unchanged active pointer, SQL ACL filtering before pagination, cursor scope and current-authority replay. Real browser journey covers the end-to-end lifecycle. Mutation and replay DTOs contain only documentId/revision, including absent inspection authority.
- `document-authority`: catalog tests cover exact issuer/subject/tenant/action/ceiling, every malformed/bounded/unsafe file case and no stale-positive decision. Application/HTTP/PG tests cover privileged inspection versus read, explicit creator seed, deny precedence, all four ACL operations, restriction scope, duplicate/limit rollback, no-op revision/audit, target/ceiling checks and final transaction-time reauthorization. Fixes69/70/72 close ancestor replacement and final seed/target revocation gaps.
- `dm-persistence`: actual PostgreSQL tests and isolated Compose checker cover separate migration/runtime roles, public/cross-database/DDL denial, qualified migrations, CAS/idempotency/audit atomicity, failure rollback and relational constraints. Seven business tables (documents, retention, acl_rules, legal_holds, versions, command_outcomes, audit_facts) share one ordered MVCC snapshot; only its SHA-256 digest leaves PostgreSQL. Persistent restart preserves all captured state.
- `browser-bff` and frozen API contract: closed strict request/response shapes, exact receipt statuses, PATCH ACL, uppercase safe errors, route-specific403/404 before lookup, unauthorized stale suppression,503 unavailable authority,415 media,413 body limit,400 type/field/bounds,422 ACL semantics and current-authority replay covered in application/HTTP/BFF/contract tests. Bounded stream regression tests include multi-chunk invalid4097-character description, maximum valid metadata and chunked oversized input.
- `portal-shell`: Query/Zod tests cover validated boundary DTOs, retry/idempotency, mutation invalidation, accessible failures, tenant/session cache separation and logout. Real browser independently confirms persisted ACL addition/removal rather than permitted no-ops.
- `local-runtime`: private synthetic bootstrap, seven nonroot/read-only/capability-restricted containers, trusted HTTPS, no host DB port, scoped roles, real journey and outage/restart pass. Readiness is bounded and offloaded without blocking liveness.
- `service-foundation`: architecture and direct HTTP/security smoke tests retain the protected non-probe management surface and deny unsupported knowledge/search/upload/content/publication/purge operations. Forged tenant headers and anonymous requests cannot establish business authority.

### Review findings and corrections

Independent review68 accepted the stable catalog/domain/persistence/SDK/build/HMAC slice. Root review71 covers other-agent BFF/portal/infra/HTTP source. All fixes are tracked before execution:69 authority ancestors,70 final authority,72 cursor ancestors,73 protected fixture,74 request-body deferred-flow heap exhaustion,76 factory negative coverage,77 actual browser ACL delta,78 readiness and79 complete persistence snapshot. Source is frozen and local acceptance passes. Final-head CI/integration remains mandatory.

The original byte-array body path exhausted a512MiB test heap on multi-chunk invalid metadata in Linux and macOS. Bounded framework InputStream consumption plus early size handling, typed oversized-body mapping and guaranteed close passes the full HTTP suite without heap increases or skipped coverage. Heap metadata showed deferred-flow chain accumulation; the exact upstream caller was not established. Applied away decision:20261031T173701Z-claude-38028. The earlier timed-out consultation produced no decision and was not applied.

### Explicit limits and rollback

This accepts local development behavior only. Local PG transport is isolated plaintext, not production TLS approval. Distributed workload mTLS/durable multi-instance sessions, production authorization administration, retention classification/expiry, CID-07/CID-15 and production readiness remain gated. Unsupported native filesystem trust introspection denies access. Public version append remains503 until verified-artifact authority is implemented in BP03; first object store SHALL be RustFS (#35), behind the S3-compatible port, with separately reviewed pinned version/digest and security verification.

For development rollback, stop the affected application containers and revert the implementation merge through a reviewed PR. Preserve the PostgreSQL volume and ignored credentials; do not remove volumes or claim migration reversal. The initial DM schema requires an explicit backup/forward-fix or separately reviewed data migration if existing state must be retained. No production deployment occurred.

The owner-supplied AGENTS.md in the current conversation requires PR review without automatic merging. This supersedes the earlier standing automatic-merge text for this delivery; #75 remains open for review/integration.
