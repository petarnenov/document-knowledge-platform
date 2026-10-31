# DKP-004 — RustFS quarantine, verified upload and safe content

Draft specification task [#88](https://github.com/petarnenov/document-knowledge-platform/issues/88), parent BP-03 [#35](https://github.com/petarnenov/document-knowledge-platform/issues/35). Research #86, design adjudication #87, independent security review #89 and architecture review #90 precede implementation. No approval or runtime completion is claimed.

## Why

BP-02 persists immutable versions but deliberately cannot verify real artifacts. BP-03 must accept untrusted files into isolated quarantine, establish real integrity and malware/structure evidence, and serve exact authorized versions through a revocation-aware gateway.

## What Changes

- Introduce the owner-selected first object store, RustFS, behind an S3-compatible application port with private quarantine and immutable verified originals.
- Enforce PDF/DOCX/PPTX/XLSX signature, structure, SHA-256, standard ClamAV malware checks and bounded isolated inspection.
- Persist tenant/document/actor-scoped upload admission, state, verification evidence and opaque immutable receipts; bind receipt consumption atomically to a same-document version without changing its active pointer.
- Extend fixed BFF routes and the real portal journey with bounded binary transport, guarded mutations, actor-owned consumed-receipt version discovery and exact-version authorized downloads; no browser-selected S3 keys or permanent links.
- Freeze upload/OpenAPI, relational ownership, receipt and content contracts before runtime work. Apply actual #87 away95531 staging and content.review proposal; independent #89/#90 acceptance remains required before approval.

## Capabilities

### New Capabilities

- `upload-security`: Quarantine, admission, isolated file verification and scoped verified receipts.
- `content-gateway`: Exact-version revocation-aware private content delivery.

### Modified Capabilities

- `document-management`: Replace unavailable artifact verification only through real trusted evidence, preserving immutable versions and publication separation.
- `document-authority`: Explicit upload/content actions and current conjunctive policy, subject to #87 decision.
- `dm-persistence`: Owned upload/receipt records, admission and atomic idempotency/audit transactions.
- `browser-bff`: Fixed binary upload and content delegation with existing trust/session/Origin/CSRF controls.
- `portal-shell`: Functional verified-upload journey and honest processing/publication limits.
- `local-runtime`: Used RustFS/scanner/inspection dependencies, scoped secrets, readiness and failure verification.

## Impact

Affected layers: DM pure domain/application ports, outbound PostgreSQL/S3/scanner/inspection adapters, inbound API, authenticated BFF, portal, versioned contracts/SDKs and hardened local CI. No shared database ownership or direct browser storage trust is introduced.

BP-03 completion requires all four formats, real RustFS plus standard signature scanning, negative tenant/malware/integrity tests, immutable receipt/version evidence, exact-version/range authorization and a real HTTPS authenticated happy path. Every changed source area needs at least75% line and branch coverage.

BP-05 durable processing, BP-06 extraction, BP-12 rendered preview, BP-13 publication and BP-15 destructive retention remain separate packages. Their boundaries require an explicit #87 amendment to §8.1.3 rather than fabricated processing readiness. CID-07/CID-15 and production security/release acceptance remain open.
