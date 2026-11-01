## ADDED Requirements

### Requirement: Owned constrained processing state
DM SHALL own isolated processing coverage/run/intent/attempt/checkpoint/outbox/inbox/admission records with same-tenant/document/version constraints, immutable lineage and runtime DML-only privileges. Deferred cutover guards SHALL reject any NEW version lacking the exact atomic quad. No cross-service SQL writes are permitted.

#### Scenario: Bypass and restore
- **WHEN** runtime SQL attempts partial acceptance, cross-tenant FK, immutable update or old-writer version-only insert
- **THEN** real PG denies it; backup/restart retains sealed cutover, exact lineage and replay facts

#### Scenario: No cross-system transaction
- **WHEN** dispatcher or relay handles claimed work
- **THEN** its SQL transaction ends before Temporal/Kafka I/O and only exact fenced local state is updated afterward

### Requirement: Conserved durable claims and quotas
Claim epochs, input identity, lease and possible remote start SHALL survive restart. Lease expiry SHALL transfer reconciliation ownership only, never prove absence or release active capacity. Global50/tenant5 active and pending/backlog counters SHALL match durable rows and never reset on process restart.

#### Scenario: Late worker result
- **WHEN** a prior claim owner returns after expiry/replacement
- **THEN** CAS rejects stale mutation and unknown remote effects retain their permit/evidence

#### Scenario: Restart and contention
- **WHEN** multiple dispatchers and tenant requests race or PG restarts
- **THEN** real persistent limits, FIFO/round-robin fairness, sorted lock order and same-key one-winner invariants hold

### Requirement: Unknown commit preserves recovery
All processing UoWs SHALL use bounded actual pool/SQL deadlines and preserve UNKNOWN commit outcomes. No automatic fresh-key retry, compensating deletion of accepted history or callback after owner cancellation SHALL occur. Rollback SHALL keep the cutover fence and durable pending work.

#### Scenario: Actual lost commit ACK
- **WHEN** a real PG commit is acknowledged ambiguously
- **THEN** same-key result is recovered without duplicate facts and late callback cannot enter a second transaction
