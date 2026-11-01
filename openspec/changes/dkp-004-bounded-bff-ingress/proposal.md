## Why

Issue [#341](https://github.com/petarnenov/document-knowledge-platform/issues/341), created before drafting, specifies the resource correction tracked by #338. Causal #331 uses the production BFF and its actual HTTP boundary with the unchanged public32,400,444-byte PDF: a16KiB framework pending-body queue overflows before transfer completes and produces503. The original0436 runtime exception was not retained; that particular attribution remains qualified.

## What Changes

Propose a globally configured128KiB BFF framework body queue, coupled to a supported connection customizer fixing receive allocation at8192bytes and one receive message per read cycle. Freeze the pinned HTTP/1.1 plaintext internal listener assumptions, startup/channel enforcement, a receive-credit derivation, and separately retained bridge pieces. This is a shared framework resource window, not a binary-only configuration or permission for metadata larger than16384bytes.

Preserve semantic metadata16384bytes, binary1..100000000bytes,300s total/30s idle, session/Origin/CSRF/current tenant checks and existing admission/receipt/fence/cleanup rules. Never aggregate an artifact in memory. Keep public TLS at the reviewed Nginx edge and make unsupported direct TLS/HTTP2/HTTP3/multiplex profiles unavailable until separately reviewed.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `browser-bff`: bounded shared framework ingress and explicit supported protocol envelope.

## Impact

Specification only. Future #338 implementation belongs to BFF bootstrap/transport configuration and meaningful tests; no domain, database, wire schema, SDK or DM resource-profile change. Base is integrated dev62a73f0 with approved BP03; source diagnosis73c3832 is an explicit evidence dependency, not imported runtime code. Verification-temp/generic-temp followups and aggregate #222 remain independent open gates.

## Review State

UNAPPROVED candidate. Independent specification/security review and applicable exact-source green checks are required before standing owner development-specification approval can apply. No architecture, security-control, CID or production approval is inferred. Open a draft PR targeting dev; do not automatically merge.
