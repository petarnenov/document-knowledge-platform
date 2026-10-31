# DKP-005 — Knowledge collections, audiences and policy revisions

Status: DRAFT, specification task [#167](https://github.com/petarnenov/document-knowledge-platform/issues/167), package [#36](https://github.com/petarnenov/document-knowledge-platform/issues/36). No implementation, approval, independent review or production acceptance is claimed. Latest owner instruction requires a reviewed PR and prohibits automatic merging; stale worktree automation prose does not override it.

## Why

BP-04 establishes KB-owned collections, explicit audience policies, many-to-many memberships and authenticated revision validation. BP-01/BP-02 are integrated dependencies. BP-03 runtime acceptance is independent. Future publication/search must not mistake collection membership, an old snapshot or unavailable DM authority for document access.

## What Changes

- Tenant-owned collection administration, bounded issuer-qualified ACL restriction/delegation, audience policy revisions and explicit document sharing memberships, including an honest Mantine administration journey.
- Proposed development-only action matrix and classification mapping, current catalog AND collection ACL AND independent DM authorization, explicit denies and no owner shortcut.
- Authenticated actor-bound metadata-only DM share assessment and KB audience/visibility snapshot contracts. Snapshots include complete membership-set revisions and never grant content/publication access.
- KB-only PostgreSQL migrations, immutable policy/audit/command facts, CAS and durable revision invalidation/outbox records. No shared DM transaction, foreign database writes or implemented Kafka dispatch.
- Frozen draft OpenAPI and catalog schema; published contracts, generated clients and runtime remain unchanged during this task.

## Capabilities

### New Capabilities

- `knowledge-base`: collections, administrative ACLs, explicit memberships and audience rules.
- `knowledge-policy`: complete revision snapshots and fail-closed current authority validation.
- `kb-persistence`: owned transactions, audit, replay, revisions and durable invalidation facts.

### Modified Capabilities

- `browser-bff`: fixed KB routes, exact actor/tenant parity and existing CSRF/Origin controls.
- `portal-shell`: collections/memberships/audiences with real validated server state.
- `service-foundation`: reviewed KB metadata surface and a narrow DM share-assessment boundary.

## Completion and approval boundary

Full BP-04 implementation acceptance includes the new domain behavior, additive DM authority boundary, independent builds, real owned PostgreSQL migrations/roles/restarts, all negative/concurrency cases, at least75% changed-source line/branch coverage, real OIDC/HTTPS BFF/portal journeys, compatible SDKs and independent implementation review with passing PR checks. This proposal does not implement BP-05 processing/outbox dispatch, BP-11 search or BP-13 publication. Content remains governed by its owning DM API. RustFS remains the first selected object store; this change introduces none.

Finite development actions, audience relation facts, snapshot semantics and the DM sharing action are explicit proposals requiring independent security/architecture review and recorded development approval before runtime work. CID-07 formal production classifications/role matrix and CID-15 retention remain open. No SPIFFE/SPIRE/workload trust, production deployment or compliance approval is invented.
