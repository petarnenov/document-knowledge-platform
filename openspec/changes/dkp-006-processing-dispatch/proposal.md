# DKP-006: durable processing acceptance and independent dispatch

## Why
BP03 verifies immutable originals and consumes receipts into versions, but has no processing runs, durable intents or domain-event relay. A successful upload or legacy append201 cannot imply processing has started. BP05 must close that deliberate sequencing gap without breaking already generated clients or losing pre-processing versions.

Issue [#246](https://github.com/petarnenov/document-knowledge-platform/issues/246), parent [#37](https://github.com/petarnenov/document-knowledge-platform/issues/37); readiness inventory [#183](https://github.com/petarnenov/document-knowledge-platform/issues/183). This packet is DRAFT, not development-approved; independent architecture/security reviews must be created before review. No runtime, provider, published contract or SDK is changed.

## What Changes
- Atomic DM-owned acceptance of NEW immutable version, initial ProcessingRun, unique ProcessingIntent, domain outbox, command and audit, with additive202 ACCEPTED_PENDING_DISPATCH.
- Preserve legacy append201 paths/DTOs/operationIds/status/replay while using the same atomic new-write transaction after cutover. Explicitly enumerate and schedule existing versions without rewriting immutable originals.
- Direct durable dispatcher and deterministic Temporal orchestration skeleton, reconciliation of ambiguous starts, fenced activities and cancellation; independent Kafka transactional-outbox relay with idempotent durable consumption.
- Add versioned OpenAPI/AsyncAPI/workflow/event contracts, technical workload authentication/authorization, bounded quotas/deadlines/fairness and separate releases/builds for dispatcher, relay and processing worker.
- Current authority, tenant isolation, artifact lineage, original trust, unresolved retention/legal-hold protection, rollback/UNKNOWN outcomes and genuine fault/restart acceptance remain mandatory.

## Capabilities
### New Capabilities
- `processing-acceptance`: atomic acceptance, legacy201 projection and explicit old-version enrollment.
- `processing-persistence`: owned SQL, cutover guards, durable claims/quotas and UNKNOWN outcomes.
- `processing-dispatch`: claims, workflow identity, reconciliation, deterministic orchestration and cancellation.
- `domain-event-delivery`: transactional outbox, Kafka relay, versioned envelopes and idempotent consumers.
- `processing-security`: actor/workload authority, artifact access and stage isolation.
### Modified Capabilities
- `upload-security`: replace only the historical BP03 no202 staging restriction after reviewed BP05 cutover; retain verification and single consumption.
- `browser-bff`: fixed processing mirrors and accessible honest run status/reconciliation controls.
- `local-runtime`: isolated self-hosted Temporal/Kafka plus independently runnable workers and failure gates.

## Impact and dependency gates
Affected future code: DM inward models/use cases, owned SQL/migrations, dispatch/relay adapters; separate processing worker bootstrap; BFF/Mantine portal; versioned SDKs and actual runtime/CI. No domain imports Temporal/Kafka/HTTP/SQL libraries and no KB/Search table writes.

BP03 PR146 integrated ondev62a73f0 with genuine expiry evidence, but correction242 remains OPEN. BP04 specification approved/merged; runtime, maximum-size/current-authority243 and other integration gates remain OPEN. Preparation may proceed; BP05 runtime/publication starts only after both dependency packages and corrections are verified integrated. No later parser, index, publication, workload federation or production/CID gate is deemed complete. Numeric technical proposals and authority additions in this packet require independent review; owner standing automatic specification approval does not self-approve this draft.
