## ADDED Requirements

### Requirement: One persistent development logging destination
The platform SHALL provide one common authenticated logging API backed by a dedicated persistent log-only database for all development compilations and current and future components.

#### Scenario: Two builds share the destination
- **WHEN** frontend, backend and infrastructure events from two distinct compilations are ingested
- **THEN** both builds' records are queryable from the same store after producer and logging-service restarts
- **AND** task fixture cleanup does not delete the shared store

### Requirement: Filterable build and execution identity
Every event SHALL carry a compilation-specific `buildId`, a separate execution-specific `runId`, component, source revision, timestamp, severity, event name and operation correlation.

#### Scenario: Independent compilations of the same source
- **WHEN** the same Git revision is compiled twice and each build executes twice
- **THEN** queries distinguish the two builds and four runs and join related component events by correlation or trace context
- **AND** missing or malformed required identities are rejected visibly

### Requirement: Nonblocking frontend Beacon delivery
The frontend SHALL deliver sanitized bounded event batches with `navigator.sendBeacon()` without awaiting a logging response or gating user actions.

#### Scenario: Logging service is delayed or unavailable
- **WHEN** a user performs an operation while ingestion is delayed or unavailable
- **THEN** UI and business requests progress independently of logging delivery
- **AND** browser queue acceptance is reported separately from confirmed persistence
- **AND** offline, queue rejection, retry and unknown delivery remain observable

### Requirement: Useful development diagnostics across components
All current and future components SHALL emit useful sanitized development DEBUG/TRACE successful and failed lifecycle boundaries with technical cause and correlation context.

#### Scenario: Reconstruct a failed transfer
- **WHEN** a transfer fails after an admitted upload
- **THEN** common-store queries reveal the frontend request, backend phase, relevant dependency and cleanup outcomes, safe exception class/cause/stack and source/runtime identity
- **AND** logging observer failure preserves the primary business failure and its original deadline

### Requirement: Authenticated and scoped logging
The logging boundary SHALL validate caller authority and enforce trusted producer attribution and tenant-scoped access independently of untrusted event fields.

#### Scenario: Forged attribution and cross-tenant access
- **WHEN** a producer for one component or tenant claims another identity or queries outside its grant
- **THEN** ingestion or query is rejected without leaking another tenant's event, count or cursor
- **AND** browser Beacon validation retains session, exact origin and CSRF protection

### Requirement: Sensitive data exclusion
Producers and ingestion SHALL sanitize untrusted diagnostics before durable storage and exclude credentials, session/CSRF tokens, document bodies and unrestricted HTTP/SQL payloads.

#### Scenario: Secret or document data in an error
- **WHEN** arbitrary console arguments or exception messages contain uploaded text, tokens, URLs or filesystem paths
- **THEN** only approved bounded technical context is retained
- **AND** safe error classes, source-relative frames, causes, status and diagnostic codes remain useful

### Requirement: Bounded and honest delivery lifecycle
Logging SHALL bound producer work, memory, spool, request, query and retention resources and expose rejection, loss, truncation, outage and persistence outcomes.

#### Scenario: Outage and duplicate recovery
- **WHEN** ingestion fails and a bounded spool retries a previously committed event after recovery
- **THEN** the original event is not duplicated, undelivered records remain distinguishable from committed records and capacity exhaustion emits an explicit loss/degraded signal
- **AND** logging failures cannot recursively generate unbounded logging work

### Requirement: Real CI and implementation evidence
The logging BLOCKER SHALL remain open until real shared-store delivery and applicable review, coverage, CI and dev-integration gates are verified.

#### Scenario: Hosted CI lacks connectivity
- **WHEN** a hosted runner cannot reach the shared development endpoint
- **THEN** it preserves a bounded sanitized spool and records delivery pending until an authorized importer commits it to the common store
- **AND** a fixture database, spool artifact or successful Beacon boolean is not reported as shared-store persistence

#### Scenario: Required completion evidence
- **WHEN** completion is proposed
- **THEN** real FE/BE/infra multi-build E2E, restart/outage/security/redaction/filter tests and at least75 percent changed-source line and branch coverage pass with source-bound independent review, exact-head applicable CI and verified dev integration
