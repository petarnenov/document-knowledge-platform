## ADDED Requirements

### Requirement: Singleton durable verification workspace lifecycle
The upload runtime SHALL wire exactly one application-owned workspace port shared by COMPLETE source/readback and the S3 verification-copy adapter. Missing binding SHALL fail closed without a Path-only fallback. The content gateway and other spool families SHALL retain separate ownership. No SDK, filesystem adapter or cross-database dependency SHALL enter DM domain policy.

#### Scenario: Inspect production wiring
- **WHEN** upload verification is enabled
- **THEN** source, copy scratch and readback SHALL share one durable workspace owner
- **AND** no request-scoped manager or fallback SHALL reset capacity

### Requirement: Truthful local recovery readiness
Startup SHALL validate the private namespace and process lock without adopting unknown files. Valid active work SHALL remain healthy; invalid metadata or inactive unreconciled local bytes SHALL make upload readinessDOWN, without changing liveness. Recovery SHALL be bounded and tracked until actual settlement; it SHALL not mint an actor, authority decision or receipt.

#### Scenario: Restart after failed unlink
- **WHEN** the old process exits with owned bytes retained
- **THEN** readiness SHALL remainDOWN until safe owned reconciliation restores capacity
- **AND** remote quota zero or lease expiry SHALL NOT reset local admission

### Requirement: Shutdown retains executable local ownership
Shutdown SHALL stop new workspace admission and revoke progress. It SHALL retain exclusive ownership while local I/O can still execute; no timeout-only handoff or stale object mutation SHALL free a later owner's files. Normal shutdown SHALL settle owned streams/workers and release resources without deleting foreign spool material.

#### Scenario: Blocked local work at shutdown
- **WHEN** shutdown begins while a local writer or unlink is unsettled
- **THEN** another live process SHALL NOT acquire the workspace or overwrite its payload
- **AND** a real process restart SHALL perform the closed recovery protocol before reuse
