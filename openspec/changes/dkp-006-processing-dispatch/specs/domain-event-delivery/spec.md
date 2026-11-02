## ADDED Requirements

### Requirement: Atomic closed domain event envelope
Acceptance/enrollment/cancel SHALL persist the frozen versioned domain envelope in the same owned transaction as business facts. Events SHALL contain required IDs/revision/time/actor/tenant/correlation/causation and bounded reference payload, no content, credentials or original URLs. Audit and telemetry SHALL remain distinct.

#### Scenario: Atomic event failure
- **WHEN** event insert or schema validation fails
- **THEN** business acceptance rolls back and no malformed event is emitted

#### Scenario: Content or secret injection
- **WHEN** an event includes raw file text, bearer, extra field or unsupported schema
- **THEN** strict validation rejects it before Kafka and no protected log payload is printed

### Requirement: Independent at-least-once outbox relay
Relay SHALL publish only committed immutable events outside SQL with scoped TLS Kafka, stable aggregate key, bounded leases/retries and fenced ack marking. Crash after broker ack before mark MAY redeliver the same eventId; no exactly-once PG/Kafka transaction or event-dependent Temporal dispatch is claimed.

#### Scenario: Crash after publish
- **WHEN** real Kafka accepts then relay dies before marking
- **THEN** restart may duplicate delivery but retains one logical event and no lost outbox row

#### Scenario: Kafka outage
- **WHEN** broker is unavailable while Temporal/DM remain safe
- **THEN** outbox accumulates within quotas, retries remain bounded and processing dispatch is independent

### Requirement: Idempotent ordered consumers
Consumers SHALL strictly validate event/workload scope and atomically deduplicate eventId+payload fingerprint with owned effects. Duplicate IDs with changed payload SHALL fail; out-of-order/gaps SHALL use bounded buffering and authoritative reconciliation, not stale grants. No consumer writes another service database.

#### Scenario: Duplicate and out-of-order delivery
- **WHEN** real Kafka redelivers, reorders or resumes offsets after restart
- **THEN** one owned effect occurs per exact event, revisions never regress, missing revisions reconcile and stale authority never grants access

### Requirement: Compatible retained event recovery
Published AsyncAPI/schema/client versions SHALL preserve reviewed compatibility, retained replay and explicit backlog/retention/RPO limits. Unknown versions SHALL not be silently discarded. CID05 and CID15 production retention/backup/key decisions remain separate gates.

#### Scenario: Schema upgrade and restore
- **WHEN** old/new consumers replay retained events and recover from a real restored checkpoint
- **THEN** compatible versions work or safely pause; no false full-platform recovery is declared beyond measured retained history

### Requirement: Explicit processing-event sequence
Every processing event SHALL use processingRunId as aggregateId and a contiguous persisted per-run event sequence as aggregateRevision. Payload SHALL carry runRevision and documentRevision separately. Consumers SHALL deduplicate and detect gaps by tenant+run+event sequence; the document Kafka key SHALL not imply document-revision ordering.

#### Scenario: Multiple states and runs
- **WHEN** several state events share one document revision and two runs interleave on its Kafka key
- **THEN** each run advances only its own event sequence; duplicate/out-of-order delivery cannot lose effects, invent cross-run gaps or confuse operational revision changes with missing events
