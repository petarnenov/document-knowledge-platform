## ADDED Requirements

### Requirement: Real isolated processing providers
Development SHALL reproducibly run scoped TLS Temporal, Kafka, owned PG and separately built dispatcher/relay/worker with pinned reviewed image digests/private credentials/resources/probes. No dummy provider, foreign lifecycle mutation, permissive workload binding or production HA claim is allowed.

#### Scenario: Fresh real full stack
- **WHEN** a clean isolated recorded project starts and verifies real providers
- **THEN** genuine202→intent→Temporal/checkpoint/wait and real Kafka consumer facts pass alongside all existing package acceptance

#### Scenario: Provider outage and recovery
- **WHEN** owned Temporal/Kafka/PG/worker stop/restart or corrupt/inconsistent checkpoint occurs
- **THEN** safe distinct outage states, durable pending/replay/current authorization and measured recovery are demonstrated without dropping WF/DAT/SCH gates
