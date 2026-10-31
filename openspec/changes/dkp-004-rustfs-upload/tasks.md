## 1. Specification — issue #88, parent #35

- [x] 1.1 Read canonical BP01/BP02, System Design §§7.4/7.5/8.1/14 and existing runtime-unavailable receipt port; review public research #86.
- [x] 1.2 Apply actual away95531 OptionA as a proposal: verified upload completion, explicit append201 and separate BP05 atomic202 obligation; do not infer approval.
- [x] 1.3 Draft closed OpenAPI, DB slots/evidence/receipt/consumption, limits/privilege/content contract, capability deltas and full failure/E2E matrix.
- [x] 1.4 Independent security review [#89](https://github.com/petarnenov/document-knowledge-platform/issues/89): all candidate action/bootstrap/delegation, transport, quarantine/scanner/inspection, hold/expiry and current authority boundaries; resolve findings.
- [x] 1.5 Independent architecture review [#90](https://github.com/petarnenov/document-knowledge-platform/issues/90): staging clarification, owned persistence/UoW/receipt consumption, exact immutable RustFS identity, quotas/restart and compatibility; resolve findings.
- [x] 1.6 Root #88: strict OpenSpec, Redocly, closed-contract checks; include root-owned proposed System Design/queue clarification; freeze reviewed artifact digest and record development approval only after all gates pass.

## 2. Implementation — NOT STARTED; specification approved for development

Runtime issues91–99 were created before execution and remain unstarted/dependent on approved specification. Development specification approval is recorded in approval.md; runtime execution waits for specification integration. Delivery3.x placeholders still require separate prioritized issues before execution. Root owns final ownership mapping; source tasks stay unchecked until verified integration.

- [ ] 2.1 [issue #91; created before execution, unstarted] Pure upload state/admission/receipt/content ports and models; scoped authority, actor ownership, final rechecks, replay and strict-profile tests >=75% lines/branches.
- [ ] 2.2 [issue #92; created before execution, unstarted] DM-owned PostgreSQL migrations/UoW/accounting/leases/cleanup/immutable evidence/consumption; real PG races, rollback, restart, roles and exact snapshot coverage.
- [ ] 2.3 [issue #93; created before execution, unstarted] Exact pinned RustFS S3 adapter/native IAM bootstrap/private buckets/TLS/scoped app keys; real release compatibility, version immutability, readback/integrity, outage and negative credentials tests.
- [ ] 2.4 [issue #94; created before execution, unstarted] Standard ClamAV definitions/updater/freshness/protocol adapter plus isolated PDF/OOXML inspector; all four formats, EICAR/bombs/macros/encryption/limits/egress/malicious XML and unavailable cases.
- [ ] 2.5 [issue #95; created before execution, unstarted] Inbound JSON/binary routes and receipt-aware atomic existing append; publish reviewed versioned contracts and regenerate Java/TS clients; compatibility and exact errors/size/status tests.
- [ ] 2.6 [issue #96; created before execution, unstarted] Fixed streaming BFF/portal upload/status/complete/append and exact-version download; Origin/CSRF before forwarding, separate binary budgets, Query/Zod scoped state, accessibility/cache/expiry/denial tests.
- [ ] 2.7 [issue #97; created before execution, unstarted] Hardened RustFS/scanner/inspector Compose, generated ignored keys/CA, non-root/resource/network isolation, true readiness, durable cleanup/quota reconciliation and local rollback guidance.
- [ ] 2.8 [issue #98; created before execution, unstarted] Real HTTPS Keycloak/BFF all-four-format happy paths plus malicious/cross-tenant/revocation/range/expiry/outage/restart journeys, standard definitions and immutable byte parity. No skipped acceptance/fake scanner.
- [ ] 2.9 [issue #99; created before execution, unstarted] Effective dependency locks/SBOM/image provenance/security scans, architecture/contract/SDK reproducibility and >=75% per-changed-source line/branch CI gates.

## 3. Review and delivery — NOT STARTED

- [ ] 3.1 [issue UNCREATED; unstarted] Independent implementation security/architecture review; resolve issues before PR merge dev, require applicable checks green and actual evidence.
- [ ] 3.2 [issue UNCREATED; unstarted] Reviewed PR into dev, integration checks and verified issue closures, resumable handoff plus actual current-session /compact or honest pending marker.
- [ ] 3.3 [issue UNCREATED; unstarted] Requirement audit and reviewed archive after integration; retain BP05 legacy-version/append compatibility obligation, next BP04 queue and open CID07/CID15/production gates.

## 4. Consolidated specification review corrections — #88

- [x] 4.1 Address #89 discovery blocker: frozen exact actor-owned consumed-receipt GET DM+BFF, content.review+ACL, bounded minimal IDs, resource-hidden/current/final policy and portal/E2E matrix; original commands/status unchanged.
- [x] 4.2 Address #90 P1copy accounting: one possible original only, durable before-write marker, settled-writer/exact cleanup proof before another copy, hold/outage/restart charges, global lock order and real PG/RustFS crash/retry tests.
- [x] 4.3 Address #90 action-wire blocker: additive tenth Action in existing ACL request/response plus catalog schema, preserving other BP02 schemas; exact enum SDK/Zod/DM/catalog/old9 compatibility matrix.
- [x] 4.4 Independent #89/#90 re-review of amended full source and root-owned staging clarification; no PASS/approval claimed by author.
- [x] 4.5 Root strict OpenSpec/Redocly/Ajv/schema and parity checks; freeze reviewed revision and record approval only after resolved reviews.

## 5. Additional re-review corrections — #88 / decision #100

- [x] 5.1 Restore existing append SDK operationIds and AppendVersion DTO; document explicit typed upload-failure/429 additions without claiming whole-operation identity.
- [x] 5.2 Apply actual away18639 proposal: durable exact-version deletion fence shared atomically with placeHold, current prior201 replay preserved, newhold503/no mutation on unresolved fence; known settlement/recovery in scope, ambiguity persists with full charges.
- [x] 5.3 Independent89/90 accept precise settlement proof and indefinitehold503 cost; root strict/contract/schema/compatibility gates before recorded approval. Runtime91–99 remain unstarted.
