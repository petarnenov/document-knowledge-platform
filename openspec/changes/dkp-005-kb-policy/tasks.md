# DKP-005 tasks — parent #36, specification #167

All tasks remain governed by issue-first ordering. Unnumbered future implementation/review items are planning placeholders, not tasks already authorized/executed or issues claimed created.

## 1. Specification #167

- [x] 1.1 Read integrated BP01/BP02, public System Design, contracts and owner restrictions; preserve acceptance verbatim.
- [x] 1.2 Draft proposal/design, frozen APIs/catalog schema, normative deltas and meaningful real-PG/current-authority/concurrency/E2E verification plan.
- [x] 1.3 Run strict OpenSpec, frozen OpenAPI/catalog/compatibility checks; record actual results and limitations.
- [x] 1.4 Independent architecture [#171](https://github.com/petarnenov/document-knowledge-platform/issues/171) and security [#172](https://github.com/petarnenov/document-knowledge-platform/issues/172) were created before review; resolve their four findings under preexisting [#174](https://github.com/petarnenov/document-knowledge-platform/issues/174) and obtain re-review, including bounded batch budgets and the separately typed authorized-subset member-list eligibility correction.
- [x] 1.5 Record development approval/fingerprint after independent171/172 PASS and root strict/contract checks; see approval.md.
- [ ] 1.6 Reviewed specification PR184 is open; verify final-head CI then integrate under renewed185 owner authorization. Independent review and all applicable gates remain mandatory.

## 2. Future implementation issues, created before execution

- [ ] 2.1 KB framework-free collection/audience/membership/revision domain and application ports/services with meaningful negative/race tests.
- [ ] 2.2 KB-owned PostgreSQL migrations/roles/atomic policy/audit/idempotency/snapshot/invalidation persistence and real restart/concurrency tests.
- [ ] 2.3 Explicit DM sharing action/catalog/ACL compatibility and fixed metadata-only authority endpoint/adapter, with direct API negatives and final-boundary revocation tests.
- [ ] 2.4 Frozen KB inbound routes, current catalog/relation adapters, finite errors/limits/probes and actual auth integration.
- [ ] 2.5 BFF fixed routes and accessible Mantine portal/SDK/Zod/TanStack tests, preserving existing identity/DM security.
- [ ] 2.6 Reproducible real DM+KB+PG+Keycloak HTTPS E2E/runtime wiring, independent builds and all current authority/revision fault cases.
- [ ] 2.7 Publish additive versioned contracts, deterministic SDKs, compatible locks/pins/SBOM and required CI/architecture/dependency/secret/75%line+branch gates.

## 3. Review and completion

- [ ] 3.1 Create independent implementation review issues, resolve findings, rerun affected checks and open reviewed implementation PRs.
- [ ] 3.2 Retain direct verification evidence; integrate only after required review/CI and authorized merge instruction. Owner-authorized automatic dev merging requires green tests/CI, independent review and all applicable gates.
- [ ] 3.3 Audit full BP04 acceptance without dropping unresolved sharing/classification decisions or claiming later readiness; archive approved change only after completion integration.
- [ ] 3.4 Close only verified issues, persist resumable checkpoint and invoke actual current-session /compact if available; otherwise record pending. Continue full-project queue.

- [ ] [#185](https://github.com/petarnenov/document-knowledge-platform/issues/185) P1: latest owner standing automatic dev merge permission after green tests/CI; retain independent review and acceptance gates.

## Current acceptance corrections — 2026-11-01 03:21 UTC

- [ ] [#285](https://github.com/petarnenov/document-knowledge-platform/issues/285) strict paired RustFS readiness probes backported2216a8d; full196Python tests/helper100%lines+branches and independent290 PASS. Historical native503 exact cause remains unknown; fullCI/integration required.
- [ ] [#288](https://github.com/petarnenov/document-knowledge-platform/issues/288) closed legacy DM fixture operations use existing16KiB/1MiB/10s/noncache semantics; KB mirror assertions remain strict and canonical-path guarded.
- [ ] [#289](https://github.com/petarnenov/document-knowledge-platform/issues/289) finite safe KB failure diagnostics,32-event ring/four actual body reads/200ms observation window; no business assertion/network changes. Actual fresh both-theme/max100-member journeys required.
- [x] [#292](https://github.com/petarnenov/document-knowledge-platform/issues/292) independent corrected288/289 source and root CI coverage wiring PASS;11 helper tests100%lines/branches/functions, pending-read and path traversal findings resolved.
- [ ] [#295](https://github.com/petarnenov/document-knowledge-platform/issues/295) actual2216a8d CI BFF expected413/actual400; controlled typed framework size exception defect corrected, exact original CI scheduling not claimed reproduced. Native/Linux86tests0skip/all floors PASS; currentCI/HTTPS/integration required.
- [x] [#298](https://github.com/petarnenov/document-knowledge-platform/issues/298) independent295 source review PASS, root reran four realHTTP/controller tests; declared/chunked oversize413, exact16KiB201 and auth/CSRF-before-read remain enforced.
- [ ] [#300](https://github.com/petarnenov/document-knowledge-platform/issues/300) independent current bookkeeping and no-automatic-merge protocol review.
