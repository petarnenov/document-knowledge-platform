## ADDED Requirements

### Requirement: Exclusive logical KB ownership
KB SHALL use its own logical PostgreSQL database/schema, independent migration and DML-only runtime credentials and inward application ports. Domain/application SHALL NOT depend on sibling implementations or framework persistence. Runtime SHALL have no schema ownership/DDL/migration-history changes or access to DM/Search databases. Immutable policy, audit, command, snapshot and outbox evidence SHALL NOT be updated/deleted through runtime grants.

#### Scenario: Runtime attempts another domain or DDL
- **WHEN** KB runtime credentials attempt DM access, schema creation or immutable fact deletion
- **THEN** real PostgreSQL rejects the operation and owned KB data remains intact

### Requirement: Atomic command and policy facts
Mutation, CAS revisions, immutable policy/membership history, audit, idempotency outcome and durable invalidation/outbox facts SHALL commit in one KB transaction or all roll back. Actor/tenant/resource/body command identity SHALL be exact. Same-key concurrency SHALL commit once; no-op commands SHALL still advance revision/audit. Counters SHALL never reset or silently overflow.

#### Scenario: Failure after staged revision change
- **WHEN** persistence or final authority fails before commit
- **THEN** aggregate, revisions, audit, command result and invalidation/outbox changes all roll back

### Requirement: Local ordered revision coordination
KB SHALL lock tenant policy fence, then sorted collection IDs, then sorted document membership scopes, then command rows. Snapshot construction SHALL read complete scope state consistently. Every relevant audience/membership/status/ACL change SHALL advance affected scope revisions and tenant fence atomically; inactive/archived history SHALL remain. No shared DM lock or SQL transaction SHALL be claimed.

#### Scenario: Opposing concurrent policy and membership changes
- **WHEN** independent transactions change audience and add/remove/reactivate membership
- **THEN** no deadlock-driven partial state, missed affected scope or false-current snapshot succeeds

### Requirement: Durable restart and bounded admission
Successful committed policy revisions, snapshots and replay facts SHALL survive a real PostgreSQL process restart. Snapshot admission SHALL cap10000 per tenant with429 and no eviction or invented retention duration. Readiness SHALL report only minimal503 on required authority/DB failure while liveness remains200. Kafka dispatch, search readiness and production compliance SHALL NOT be inferred from stored outbox facts.

#### Scenario: Restart after policy commit before acknowledgment
- **WHEN** PostgreSQL restarts after a successful commit but before client acknowledgment
- **THEN** authorized replay returns the single original receipt and old snapshots remain invalid without duplicate audit/events

### Requirement: Finite request and transaction admission
Reviewed development budgets SHALL be KB8s, initial DM2s and final TOTAL2s (DM<=1.5s, KBtail/COMMIT<=500ms), locks<=250ms and statements capped by remaining time. HTTP SHALL finish outside KB SQL connections/locks. Actor1/global16 admission SHALL be single-instance, no queue. Expired callbacks SHALL NOT enter SQL. Runtime acceptance SHALL require actual timing, race, concurrency and E2E evidence.

#### Scenario: Slow authority returns after deadline
- **WHEN** a provider returns ALLOW after cancellation or whole-request expiry
- **THEN** the invalidated coordinator cannot admit mutation SQL and no delayed callback starts a transaction

### Requirement: Honest ambiguous commit recovery
HTTP timeout or cancellation SHALL NOT prove an already dispatched COMMIT absent. Unknown outcomes SHALL retain original actor/tenant/operation/resource/key/body identity and reconcile durable command facts under fresh authority. A known committed result SHALL not be deleted to fabricate rollback, and retry SHALL not use a new key.

#### Scenario: Lost commit response
- **WHEN** PostgreSQL commits but its response is lost or HTTP times out
- **THEN** the outcome is UNKNOWN until original-key reconciliation proves committed or absent, with no duplicate revision/audit/outbox or stale success bypass
