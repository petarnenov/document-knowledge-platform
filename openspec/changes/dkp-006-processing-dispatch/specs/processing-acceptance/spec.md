## ADDED Requirements

### Requirement: Atomic new processing acceptance
DM SHALL atomically commit NEW immutable version, initial run, intent and versioned outbox with receipt consumption, CAS, command and audit before additive202 ACCEPTED_PENDING_DISPATCH. Current actor/tenant/catalog AND ACL and exact verified original evidence SHALL govern. No remote I/O SHALL execute under SQL locks.

#### Scenario: Durable real acceptance
- **WHEN** an authorized actor accepts a verified four-format original
- **THEN** exactly one version/run/intent/event commits and202 conveys only durable pending dispatch, with activeVersionId and approval unchanged

#### Scenario: Failure between writes
- **WHEN** any write, policy check, constraint or commit fails
- **THEN** the entire UoW rolls back or UNKNOWN returns503 reconciled by original key; no partial quad or consumed-only receipt exists

### Requirement: Legacy append compatibility
Legacy dmAppendVersion/bffAppendVersion paths, DTOs, operationIds,201 receipts and authorized original-key replay SHALL remain compatible. After fenced cutover, NEW legacy commands SHALL use the same atomic quad transaction and project minimal201. Pre-cutover replay SHALL NOT schedule a run.

#### Scenario: Old and new clients
- **WHEN** old SDK appends or replays a pre-cutover command while new SDK accepts processing
- **THEN** old201 behavior remains exact and new202 proves the four-record transaction; status is inspected separately

#### Scenario: Competing routes
- **WHEN** legacy and additive commands compete for the same verified receipt
- **THEN** one version/run/intent/event wins; the other cannot consume again or bypass current authority

### Requirement: Complete existing-version enrollment
Every pre-cutover version SHALL be durably inventoried NOT_SCHEDULED or enrolled. Explicit current-authorized enrollment SHALL atomically create its unique initial run/intent/outbox/command/audit without rewriting immutable content. It SHALL return a distinct202 linked to the existing version, never claim a NEW-version quad.

#### Scenario: Old version recovery
- **WHEN** an authorized actor enrolls a real inventoried version
- **THEN** current append AND processing.request authority and verified evidence are checked; one linked run is created; immutable fields remain byte-equal

#### Scenario: Missing or unknown original trust
- **WHEN** old evidence is missing, incompatible or unavailable
- **THEN** no fabricated READY or silent inventory omission occurs; known invalid422/unknown503 and original facts remain

### Requirement: Idempotency and current result authority
Every mutation SHALL bind issuer/actor/tenant/operation/resource/key and canonical body, reauthorize before replay and final result, and preserve minimal stored outcome. Conflict409, hidden404 and unknown503 SHALL not leak unauthorized existence. Replays SHALL not advance facts or restart work.

#### Scenario: Lost commit acknowledgement
- **WHEN** DM commits then loses acknowledgement or the caller disconnects
- **THEN** same exact key/body reconciles the original outcome after fresh authority; no second version or run is created

#### Scenario: Revocation during replay
- **WHEN** current catalog or document ACL denies an old successful command
- **THEN** replay is withheld using frozen safe ordering without undoing committed history

### Requirement: Bounded admission and immutable deadline
Acceptance SHALL enforce persistent pending/backlog quotas before receipt mutation and request no-queue actor1/global16. Whole8s, cumulative setup2s, SQL500ms and final500ms SHALL cap actual remaining time. Original run deadline acceptedAt+30min SHALL never extend on retry/restart/wait.

#### Scenario: Capacity or slow authority
- **WHEN** admission is full or body/auth/policy/SQL/response stalls
- **THEN** 429 or finite503 occurs within its owner; late callbacks execute no new SQL and no partial mutation

#### Scenario: Provider outage at acceptance
- **WHEN** Temporal or Kafka is down while DM and current trust are safe
- **THEN** atomic acceptance remains pending and independent backlogs retain work; no processing-started claim appears
