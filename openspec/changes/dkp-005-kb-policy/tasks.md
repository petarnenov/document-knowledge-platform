# DKP-005 tasks — parent #36, specification #167

All tasks remain governed by issue-first ordering. Unnumbered future implementation/review items are planning placeholders, not tasks already authorized/executed or issues claimed created.

## 1. Specification #167

- [x] 1.1 Read integrated BP01/BP02, public System Design, contracts and owner restrictions; preserve acceptance verbatim.
- [x] 1.2 Draft proposal/design, frozen APIs/catalog schema, normative deltas and meaningful real-PG/current-authority/concurrency/E2E verification plan.
- [x] 1.3 Run strict OpenSpec, frozen OpenAPI/catalog/compatibility checks; record actual results and limitations.
- [x] 1.4 Independent architecture [#171](https://github.com/petarnenov/document-knowledge-platform/issues/171) and security [#172](https://github.com/petarnenov/document-knowledge-platform/issues/172) were created before review; resolve their four findings under preexisting [#174](https://github.com/petarnenov/document-knowledge-platform/issues/174) and obtain re-review, including bounded batch budgets and the separately typed authorized-subset member-list eligibility correction.
- [x] 1.5 Record development approval/fingerprint after independent171/172 PASS and root strict/contract checks; see approval.md.
- [ ] 1.6 Open reviewed specification PR and verify required CI; no automatic merge.

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
- [ ] 3.2 Retain direct verification evidence; integrate only after required review/CI and authorized merge instruction. No automatic merge currently.
- [ ] 3.3 Audit full BP04 acceptance without dropping unresolved sharing/classification decisions or claiming later readiness; archive approved change only after completion integration.
- [ ] 3.4 Close only verified issues, persist resumable checkpoint and invoke actual current-session /compact if available; otherwise record pending. Continue full-project queue.
