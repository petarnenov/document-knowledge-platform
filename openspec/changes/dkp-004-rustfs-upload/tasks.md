## 1. Specification — issue #88, parent #35

- [x] 1.1 Read canonical BP01/BP02, System Design §§7.4/7.5/8.1/14 and existing runtime-unavailable receipt port; review public research #86.
- [x] 1.2 Apply actual away95531 OptionA as a proposal: verified upload completion, explicit append201 and separate BP05 atomic202 obligation; do not infer approval.
- [x] 1.3 Draft closed OpenAPI, DB slots/evidence/receipt/consumption, limits/privilege/content contract, capability deltas and full failure/E2E matrix.
- [x] 1.4 Independent security review [#89](https://github.com/petarnenov/document-knowledge-platform/issues/89): all candidate action/bootstrap/delegation, transport, quarantine/scanner/inspection, hold/expiry and current authority boundaries; resolve findings.
- [x] 1.5 Independent architecture review [#90](https://github.com/petarnenov/document-knowledge-platform/issues/90): staging clarification, owned persistence/UoW/receipt consumption, exact immutable RustFS identity, quotas/restart and compatibility; resolve findings.
- [x] 1.6 Root #88: strict OpenSpec, Redocly, closed-contract checks; include root-owned proposed System Design/queue clarification; freeze reviewed artifact digest and record development approval only after all gates pass.

## 2. Implementation — IN PROGRESS; specification integrated through PR101

Runtime issues91–99 were created before execution. Development specification approval is recorded in approval.md; PR101 and integrated-dev run41232208950 passed before runtime execution. Evidence and open acceptance gates are tracked in docs/implementation/dkp-004-status.md. Delivery3.x placeholders still require separate prioritized issues before execution. Root owns final ownership mapping; source tasks stay unchecked until verified integration.

- [ ] 2.1 [issue #91; created before execution, in progress] Pure upload state/admission/receipt/content ports and models; scoped authority, actor ownership, final rechecks, replay and strict-profile tests >=75% lines/branches.
- [ ] 2.2 [issue #92; created before execution, in progress] DM-owned PostgreSQL migrations/UoW/accounting/leases/cleanup/immutable evidence/consumption; real PG races, rollback, restart, roles and exact snapshot coverage.
- [ ] 2.3 [issue #93; created before execution, in progress] Exact pinned RustFS S3 adapter/native IAM bootstrap/private buckets/TLS/scoped app keys; real release compatibility, version immutability, readback/integrity, outage and negative credentials tests.
- [ ] 2.4 [issue #94; created before execution, in progress] Standard ClamAV definitions/updater/freshness/protocol adapter plus isolated PDF/OOXML inspector; all four formats, EICAR/bombs/macros/encryption/limits/egress/malicious XML and unavailable cases.
- [ ] 2.5 [issue #95; created before execution, in progress] Inbound JSON/binary routes and receipt-aware atomic existing append; publish reviewed versioned contracts and regenerate Java/TS clients; compatibility and exact errors/size/status tests.
- [ ] 2.6 [issue #96; created before execution, in progress] Fixed streaming BFF/portal upload/status/complete/append and exact-version download; Origin/CSRF before forwarding, separate binary budgets, Query/Zod scoped state, accessibility/cache/expiry/denial tests.
- [ ] 2.7 [issue #97; created before execution, in progress] Hardened RustFS/scanner/inspector Compose, generated ignored keys/CA, non-root/resource/network isolation, true readiness, durable cleanup/quota reconciliation and local rollback guidance.
- [ ] 2.8 [issue #98; created before execution, in progress] Real HTTPS Keycloak/BFF all-four-format happy paths plus malicious/cross-tenant/revocation/range/expiry/outage/restart journeys, standard definitions and immutable byte parity. No skipped acceptance/fake scanner.
- [ ] 2.9 [issue #99; created before execution, in progress] Effective dependency locks/SBOM/image provenance/security scans, architecture/contract/SDK reproducibility and >=75% per-changed-source line/branch CI gates.

## 3. Review and delivery — INDEPENDENT REVIEW IN PROGRESS

- [ ] 3.1 [issues #110/#111; created before review, in progress] Independent implementation security/architecture review; resolve issues before PR merge dev, require applicable checks green and actual evidence.
- [ ] 3.2 [issue #141; created before delivery, in progress] Reviewed PR into dev, integration checks and verified issue closures, resumable handoff plus actual current-session /compact or honest pending marker.
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
- [x] 5.3 Independent89/90 accept precise settlement proof and indefinitehold503 cost; root strict/contract/schema/compatibility gates before recorded approval. Runtime91–99 began only after specification integration; source tasks remain unchecked pending verified acceptance.

## 6. Runtime review corrections — acceptance remains open

- [ ] #112 exact receipt/version artifact binding; real PostgreSQL21 tests pass, independent delta review passes; integration pending.
- [ ] #113 unknown-settlement503 and lease freshness;58 application tests pass, independent delta review passes; integration pending.
- [ ] #114 attested FreshClam execution profile and exclusive pre-recorded staging; hardening and independent review pending.
- [ ] #115/#116/#119/#121 nested media, physical ZIP grammar, lazy PDF errors, pre-inflation aggregate/XML budgets;40 worker tests pass, zero skips,97.18% lines/77.28% branches; independent re-review and runtime pending.
- [ ] #118 physically one-shot storage deletion: original JDK transport retries on EOF; Content-Length0 candidate fails all5 actual Linux TLS factory tests with zero dispatch. Candidate rejected; actual Codex round2 decision in progress.
- [ ] #120 canonical bounded upload errors; targeted actual HTTP tests pass, full Linux/runtime and review pending.
- [ ] #117 approved Mantine common foundation and light/dark theme; specification PR108 integrated with all9 dev checks passing; frontend implementation/tests in progress.

## Tracked browser boundary follow-ups under #96

- [ ] #123: header-only framework CSRF resolution preserves required header token/Origin checks and avoids eager untrusted JSON/binary parsing; actual HTTP null/array/trailing JSON and guarded streaming evidence, independent review and integration required.
- [ ] #128: highest-precedence new-upload framework500 normalization to503UNAVAILABLE, actual HTTP regression preserving legacy routes; independent review and integration required.

- [ ] #122 success audit/real HTTP committed facts: fixed, independent review and full Linux283 aggregate PASS; integration pending.
- [ ] #125 real isolated inspector acceptance: production adapter four formats and malformed/authority/profile/TLS negatives PASS; integration pending.
- [ ] #126 isolated full HTTPS runtime: all healthchecked services healthy after #131/#132, real PING scanner probe; trusted runner uses configured ports and Playwright URL strings; 11 merged-Compose/runner tests PASS. Actual both-mode all-format E2E in progress.
- [ ] #127/#131 private RustFS authority: actual pinned private-network ownership/policy/versioning/native IAM and unsigned existing-object matrix PASS,19 unit tests93.31% lines/89.17% branches; independent re-review supersedes unsupported ACL assumption. Integration pending.
- [ ] #129 exact upload edge budgets/finite error wire: independent append-route correction PASS; actual HTTPS oversized JSON/append/binary requests return413 PAYLOAD_TOO_LARGE with no-store/nosniff. Integration pending.
- [ ] #130 independent BFF/portal/runtime wiring review: source PASS bounded to reviewed files, full HTTPS/integration pending.
- [ ] #132 isolated BFF OIDC callback: explicit matching configured callback only; trust validator unchanged; actual healthy BFF and strict HTTPS login, merged configuration regression PASS. Integration pending.
- [ ] #133 stale real E2E append assertion: approved unknown-receipt hidden404/NOT_FOUND replaces BP02 disabled-provider503 expectation; unchanged revision and full upload paths retained. Runtime verification/review/integration pending.

- [ ] #134 finite canonical upload edge headers: source review and actual anonymous/oversize HTTPS checks PASS; authenticated all-format download headers/bytes PASS. Integration pending.
- [ ] #135 finite four-MIME download negotiation: deterministic actual403 reproduced, both DM/BFF route/Accept correction reviewed, current Linux284 and actual both-theme8downloads PASS. Integration pending.
- [ ] #136 actual provider CI/profile: authored and independently reviewed; applicable GitHub CI still required.
- [ ] #137 fresh reproducible bootstrap:76Python tests,95.38% source/87.90% branch and independent review PASS; first genuine fresh run fails closed at inspector startup wait before apps. Current health and native87-operation IAM matrix pass; original failure not accepted. Diagnosis/regression/new fresh proof required.
- [ ] #138 native Linux host-gateway/socket-group harness: reviewed bounded group inspection; forced real69tests/three coverage floors zero-skips PASS. Integration pending.
- [ ] #139 preserve forbidden encoded-body evidence: Netty decompression disabled in both apps, repeated valid/invalidgzip HTTP rejection/noforward and real HTTPS unchanged consumed-slot proof PASS; reviewed. Integration pending.
- [ ] #140 real browser create-response timing: immediate body capture retains exact201/schema; both-theme all-format path PASS17.8s, expanded negative suite PASS23.4s; strict TypeScript final finite-MIME narrowing independently PASS. Fresh current-source rerun/integration pending.
- [ ] #141 reviewed dev PR/integration/checks/closures/handoff: public source staged; secret scan0leaks, pinned OSV source-lock scan0issues; no runtime PR merged or issues prematurely closed.
- [ ] #142 effective dependency SBOM/provenance: implementation in progress; schema/parity/reproducibility/review and CI publication required, no whole-image inventory claim from source locks.

- [ ] [#143](https://github.com/petarnenov/document-knowledge-platform/issues/143) P1: deployed BP03 full-upload-table durability and owned provider outage/recovery acceptance; created before implementation, source in progress and actual runtime/integration pending.

- [ ] [#144](https://github.com/petarnenov/document-knowledge-platform/issues/144) P1: combined real PostgreSQL/RustFS COPY-01–03 and FENCE-01–05 barriers/restart acceptance; created before implementation.
- [ ] [#145](https://github.com/petarnenov/document-knowledge-platform/issues/145) P1: real HTTPS quota, unchanged sixty-minute expiry and current authority/session/Origin/CSRF journeys; created before implementation.

- [ ] [#147](https://github.com/petarnenov/document-knowledge-platform/issues/147) P1: generate public synthetic documents from readable source without tracking forbidden binary files; actual PR hygiene failure, fix in progress.
- [ ] [#148](https://github.com/petarnenov/document-knowledge-platform/issues/148) P1: exact canonical Docker capability attestation; 24 tests and independent review PASS, genuine fresh start and integration pending.

- [ ] [#149](https://github.com/petarnenov/document-knowledge-platform/issues/149) P1: Linux public-lock staging permissions: native cause reproduced, source/tests and independent review PASS; final CI pending.

- [ ] [#150](https://github.com/petarnenov/document-knowledge-platform/issues/150) P1: Linux 200% zoom button wrapping: exact overflow reproduced, seven browser tests and independent review PASS; final CI pending.

- [ ] [#151](https://github.com/petarnenov/document-knowledge-platform/issues/151) P1: Owned scoped read-only acceptance principal and genuine combined-provider runner: implementation in progress.

- [ ] [#152](https://github.com/petarnenov/document-knowledge-platform/issues/152) P1: Deterministic real completion/append receipt observation: reviewed source, all eight format/theme downloads passed; whole suite needs fresh quota state.

- [ ] [#153](https://github.com/petarnenov/document-knowledge-platform/issues/153) P1: Actual resource-balance SQL regression: eight real PG read-only cases and independent review PASS, integration pending.

- [ ] [#154](https://github.com/petarnenov/document-knowledge-platform/issues/154) P1: Deployed durability failure diagnosis: actual first PG fault failed; source diagnosis in progress.

- [ ] [#155](https://github.com/petarnenov/document-knowledge-platform/issues/155) P1: Complete physical restart, cancellation and prior-hold replay acceptance gaps from independent #144 review.

- [ ] [#156](https://github.com/petarnenov/document-knowledge-platform/issues/156) P1: Separate genuine HTTPS quiescent upload fixture and finite runner flag; source reviews PASS, actual execution pending.

- [ ] [#157](https://github.com/petarnenov/document-knowledge-platform/issues/157) P1: Bind actual immutable acceptance image Java/Gradle bytes to recorded source hashes; source fix in progress.

- [ ] [#158](https://github.com/petarnenov/document-knowledge-platform/issues/158) P1: Private acceptance authority volume minimal-capability initialization order; source fix in progress.

- [ ] [#159](https://github.com/petarnenov/document-knowledge-platform/issues/159) P1: genuine OIDC reauthentication across unchanged five-minute sessions in the sixty-minute expiry acceptance; test source frozen, actual run pending.

- [ ] [#160](https://github.com/petarnenov/document-knowledge-platform/issues/160) P1: consistent Python3.14 coverage core and transparent per-helper metrics; all121 tests and six unchanged floors pass locally, CI pending.
- [ ] [#161](https://github.com/petarnenov/document-knowledge-platform/issues/161) P1: actual curl HEAD config parser correction; real native403/nonmutating preparation passes, combined cases pending.
- [x] [#162](https://github.com/petarnenov/document-knowledge-platform/issues/162) P1: independent review of HEAD, coverage and isolated spool fixture fixes.
- [ ] [#163](https://github.com/petarnenov/document-knowledge-platform/issues/163) P1: configure both real combined-test S3 adapters with the service isolated spool directory; production direct-parent guard preserved, actual rerun pending.

- [ ] [#164](https://github.com/petarnenov/document-knowledge-platform/issues/164) P1: persist latest owner-provided prohibition on automatic merging across governing delivery docs; PR146 stays unmerged.

- [ ] [#165](https://github.com/petarnenov/document-knowledge-platform/issues/165) P1: actual physical PostgreSQL restart recovery diagnosis; first combined case reaches restart but finite verification fails, mapping hypothesis under investigation.

- [ ] [#166](https://github.com/petarnenov/document-knowledge-platform/issues/166) P1: complete genuine authenticated outage/retry and verified-unconsumed/consumed receipt restart acceptance; source work in progress, no concurrent provider faults while combined acceptance owns the runtime.

- [ ] [#168](https://github.com/petarnenov/document-knowledge-platform/issues/168) P1: real quota unsafe-mutation assertion must match existing BFF guard contract; first full quota run failed after six-concurrent/five-admitted proof, fresh rerun required.

- [ ] [#169](https://github.com/petarnenov/document-knowledge-platform/issues/169) P1: diagnose actual quota visible-capacity draft discovery/pagination synchronization; no full quota PASS claimed.

- [ ] [#170](https://github.com/petarnenov/document-knowledge-platform/issues/170) P1: independent authenticated fault acceptance review; three blockers recorded before real execution.
- [ ] [#173](https://github.com/petarnenov/document-knowledge-platform/issues/173) P1: correct route/service-specific503 contracts and reject extra wrong-outcome audit rows from review170.

## Side-conversation owner request — fast expiry execution (#178)

- [ ] [#178](https://github.com/petarnenov/document-knowledge-platform/issues/178): explicit `node tools/runtime/upload-expiry.mjs fast` application regression versus `acceptance` real sixty-minute profile. Scoped selector/tests and [execution guide](../../../docs/development/upload-expiry-testing.md) added; review and PR integration remain pending. No runtime TTL or final acceptance gate is changed; the final real-time result remains mandatory.

- [ ] [#175](https://github.com/petarnenov/document-knowledge-platform/issues/175) P1: integrate real authenticated faults, seven helper coverage floors and a separate fresh genuine quota runtime into CI; independent177 review passes, actual CI pending.
- [ ] [#176](https://github.com/petarnenov/document-knowledge-platform/issues/176) P1: observe genuine metadata create through exact authorized idempotent replay and UI/discovery binding;23 helper tests100% coverage and independent177 review pass, actual final-source two-theme/quota CI pending.
- [ ] [#177](https://github.com/petarnenov/document-knowledge-platform/issues/177) P1: independently review176 and175 integration; corrected cleanup attempts both owned runtimes and preserves nonzero failure. Review PASS, handoff pending.
- [ ] [#179](https://github.com/petarnenov/document-knowledge-platform/issues/179) P2: independent review of concurrent owner-side178 fast versus real-time expiry selection; no genuine sixty-minute success claimed early.

- [ ] [#180](https://github.com/petarnenov/document-knowledge-platform/issues/180) P2: close179 review gaps using injected-clock application tests for the original exact expiry boundary, delayed retry preserving its deadline and expired held accounting; no production TTL/runtime change.

178/179/180 checkpoint23:01UTC: selector5 tests100% coverage; actualfast64 application tests pass,97.85% lines/80.87% branches; independent source re-review PASS. Required real60-minute acceptance remains RUNNING, final CI/integration pending.
