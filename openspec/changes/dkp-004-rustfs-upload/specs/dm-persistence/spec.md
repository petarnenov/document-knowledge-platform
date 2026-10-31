## ADDED Requirements

### Requirement: Owned durable upload and consumption records
DM SHALL own closed upload slot, receipt, evidence, consumption, command, resource-account, cleanup and upload-audit records with tenant/document foreign keys. Immutable evidence/receipt/consumption/audit/outcome facts SHALL be insert-only; runtime SHALL lack DDL/sibling/history privileges. Real PostgreSQL SHALL enforce single receipt consumption and atomic quota/state/CAS/idempotency boundaries.

#### Scenario: Crash after verified receipt or append rollback
- **WHEN** process/database transaction fails across an upload transition
- **THEN** restart preserves charges/evidence; no partial version/consumption/success exists

#### Scenario: Runtime mutation of evidence
- **WHEN** runtime attempts UPDATE/DELETE of verified evidence or another database
- **THEN** database denies the operation

### Requirement: Durable cleanup and legal hold ordering

DM SHALL atomically serialize cleanup dispatch and new placeHold using durable tenant/document deletion fence, accounts-first row order and no SQL locks over network. Current holds/eligibility/consumption SHALL precede committed exact-version dispatch authorization. New hold with unresolved fence SHALL return503/no mutation after original authorization/replay; authorized prior201 replay SHALL remain. Holdfirst SHALL block deletion dispatch.

#### Scenario: Hold commits before cleanup
- **WHEN** an authorized new legal hold commits before cleanup fence acquisition
- **THEN** no deletion authorization or remote dispatch occurs for held objects

#### Scenario: Deletion precedes new hold
- **WHEN** a deletion fence exists, including timeout/crash/unknown outcome
- **THEN** new hold returns503 without hold/revision/success outcome, while current authorized previous successful same-key replay returns its original201 receipt

### Requirement: Exact deletion settlement and ambiguity persistence

Delete SHALL use one bound attempt/executor/exact version after committed fence, with automatic retries OFF. Fence/accounting release SHALL require validated terminal bound response, proof no dispatch/retry remains executable and authoritative absence. Timeout/lost response/crash/lease expiry/HEAD absence SHALL NOT settle or transfer authority. Unknown fence/full charges SHALL persist, potentially indefinitely; known-outcome cleanup/restart recovery SHALL remain implemented.

#### Scenario: Delayed deletion after timeout
- **WHEN** remote deletion can execute after local timeout, lost response, restart or canceled future
- **THEN** fence/full charges remain and no new hold can successfully commit until independently provable settlement; stale workers cannot gain dispatch authority

#### Scenario: Proven normal cleanup and recovery
- **WHEN** exact one-shot delete finishes with validated bound response/no-further-execution proof/absence
- **THEN** durable terminal evidence, fence release and accounting commit atomically; completed-attempt restart does not redispatch and new hold may succeed safely
