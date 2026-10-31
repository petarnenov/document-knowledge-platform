# DKP-003 specification approval record

Approved for development implementation on 2026-10-31 under the owner's standing automatic specification authorization, after independent security and architecture re-reviews and passing strict checks. This approves the reviewed BP-02 development specification; it grants no production security/control, CID-07/CID-15, compliance or release sign-off.

## Reviews and findings resolved

- Security review #56: initial blockers were inspection-bearing mutation/replay responses, ambiguous ACL transition/removal semantics, and inconsistent missing-inspection denial statuses. Re-review PASS after exact minimal receipts, explicit bounded PATCH ACL delta and fixed route-boundary authorization ordering. Reviewer independently checked idempotency headers, BFF Origin/CSRF, DM/BFF parity and receipt keys. Review deliverable accepted and issue closed.
- Architecture/persistence review #57: initial mutation/ACL blockers resolved; re-review PASS across design, normative deltas, frozen contract, tasks and verification. Ownership, real PostgreSQL least privilege/migration separation/CAS/version invariants and later RustFS/production gates align with BP-02. Review deliverable accepted and issue closed.
- Root: strict all-project OpenSpec7/7 PASS; frozen OpenAPI configured Redocly valid with zero warnings; exact receipt/delta/PATCH plus all ten DM/BFF mutation success schema assertions PASS; git diff --check PASS. Frozen install changed no lock/package files. These are specification checks, not runtime test or coverage evidence.
- Source specification author and independent reviewers are different agents. Only administrative approval/status/checklist metadata changed after review; reviewed wire schemas and normative requirements did not change.

## Scope and prerequisites

Parent #34; preparation/adjudication #50; specification #52. Implementation issues #58 catalog, #59 domain/application, #60 PostgreSQL, #61 HTTP/contracts/SDK, #62 BFF/portal and #63 runtime/CI were created before implementation/delegation and remain unstarted at this checkpoint. Runtime must prove every approved scenario, >=75% changed-source automated coverage, real PostgreSQL transaction/role/restart tests and genuine authenticated HTTPS BFF/portal E2E, then independent implementation review and required CI before merge.

User-directed RustFS is the first object store, with real integration deferred to BP-03/#35; verified-artifact authority remains unavailable until then. Publication, purge/hold release, final classification/action matrix, legal durations, workload mTLS and production release retain their named later gates. The full project goal remains active beyond this package.

## Adjudicated development decisions

- `20261031T161752Z-claude-46789`: Option B explicit local authoritative catalog, no added JWT action claims or generic administrator grant.
- `20261031T163021Z-claude-62291`: distinct candidate document.inspect-draft; exact creator-authority ACL bootstrap and corrected resources()->DENY fact.
- `20261031T163854Z-claude-72144`: CommandReceipt/documentId+revision only, ACL PATCH four-operation delta, bounded restriction/delegation/revocation/replay and consistent403/404 ordering.

Exact action candidates are development choices pending CID-07; no legal retention duration or production authorization policy is invented.

## Approved artifact fingerprint

SHA-256 of the following sorted SHA-256 manifest: `15303e8fcc0480e5c206b12a1e2cdeefa929f6cb513327406fcf56b8dbc47dd0`.

```text
c73b6edcad47c7b7f920c8bbdd0a0ebfd5cd316b7b0ce4046b46497354b5471c  contracts/document-management.openapi.json
152638f1fc189f77999f1c133aa3271f39eb2268f826f82970d0a7d5fa96b868  contracts/grant-catalog.schema.json
4a4e3d830062e03869c4dbd82c9d292b4ee422998edfba650e828f1d65391e14  design.md
dbf4e820b5cb6d1cc907b9c381628e8b3dd83c0ab9a4375b76c9e262968c341d  proposal.md
4f25067ac6f7a884d8dfd2f6e8ce5ea5232d068906b57b189c46051e0930a528  specs/browser-bff/spec.md
b5370c8d522c847bb80d292cd59bf601b072a96c6331f2fe06ecbcba3babe3dd  specs/dm-persistence/spec.md
69131fc6d5c1d493f83ce4c3f6d5357ce2c7576b803ad4e6e90cbaf0f4f564cd  specs/document-authority/spec.md
3e3b8d4a801db8a9f9ea08a2deac922aa2278d04d5a99758425c08aa6fffdd43  specs/document-management/spec.md
05f856ea21777c916359cd0ee607c989636e4c6d295dd9b3c574b5637c7e799a  specs/local-runtime/spec.md
0bf028e42bdad16d039669e148363d4933939bdbaff8b2fd145b981a5f78f7fa  specs/portal-shell/spec.md
550c0d81c94288e8bd36cf9fc51cfb721b990e6f7cd10250b22a6a1f4a924d63  specs/service-foundation/spec.md
9384f15cbff727a6bf102208c926e3f01b3c52a66c5a3b59a0581e16d96139ea  tasks.md
f747b83904af395cdddf47d5ed01fcd7f8c658034b89d07c31f024c836a08c73  verification.md
```

The task checklist and execution evidence evolve during delivery. Security, contract or architectural behavior changes require proportionate independent re-review before implementation; an unchecked runtime task is not completion evidence.
