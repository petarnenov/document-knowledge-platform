## ADDED Requirements

### Requirement: Distinct explicit local payload profiles

DM SHALL use one generic local manager per instance with ten TRANSFER and two PREAPPEND durable lanes. Each payload SHALL be bounded before writes by trusted declaredSize<=100000000bytes. The proposed local payload maxima SHALL be1000000000 TRANSFER and200000000 PREAPPEND bytes, distinct from SQL/S3, verification and content quotas. These NEW local selections SHALL require recorded development approval before implementation.

#### Scenario: Remote counters are not local reservation
- **WHEN** an expired/reset/consumed upload releases SQL/S3 capacity while a generic payload remains
- **THEN** its local lane SHALL remain occupied until actual settlement and durable absence
- **AND** a new slot, replay or restart SHALL NOT discard that reservation

### Requirement: Shared technical owner and closed capabilities

TRANSFER and receipt/pre-append preflight SHALL share one application-owned generic manager. Its port and capabilities SHALL bind role, lane, epoch, operation, fresh reservation and exact trusted actor/tenant/document/upload identity; PREAPPEND SHALL also bind receipt. Production SHALL NOT allocate these payloads through a Path-only fallback, per-request manager, client selector or another namespace's quota.

#### Scenario: One receipt creates concurrent preflights
- **WHEN** two PREAPPEND operations are actively admitted and another current-authorized request arrives
- **THEN** it SHALL receive429 without a third payload or queued worker
- **AND** the existing upload-slot count SHALL NOT stand in for local admission

### Requirement: Durable private record before bytes

The implementation SHALL follow contracts/local-generic-temp-profile.md and its closed schema. It SHALL validate trusted root ancestry, private service ownership, exclusive process lock, markers, nonsymlink/nonhardlinked files and exact generation names. RESERVED and MAY_EXIST publication SHALL be durable before payload creation/writes. Unsafe, unknown or unsupported state SHALL fail503 without adoption or arbitrary deletion.

#### Scenario: Partial source and failed unlink
- **WHEN** actual input failure leaves a partial file whose owned unlink fails
- **THEN** the exact record/lane SHALL survive an INITIALIZED reset and later expiry
- **AND** controlled recovery SHALL prove absence before reuse

#### Scenario: Crash during publication
- **WHEN** a crash occurs before/after file force, atomic rename, directory force or payload creation
- **THEN** recovery SHALL validate committed state and exact generation before mutation
- **AND** state.next alone SHALL NOT authorize payload adoption

### Requirement: Transfer success and replay cleanup boundary

New TRANSFER success SHALL settle/remove its local payload and durably release the record before persisting QUARANTINED/command success. Same-command replay SHALL retain current body-fingerprint verification and require its own local cleanup before returning original success. Failure SHALL preserve existing remote writer/fence/charge semantics and SHALL NOT erase an already committed command or invent a new success.

#### Scenario: Already stored transfer replay fails cleanup
- **WHEN** an authorized same-key replay verifies the body but its new local unlink fails
- **THEN** it SHALL fail503 and retain its local reservation
- **AND** the original command fact SHALL remain unchanged without another untracked allocation

### Requirement: Receipt preflight preserves append atomicity

Authorized original append replay SHALL precede new preflight. A new preflight SHALL bind current exact receipt original/hash/size/actor/tenant and finish local settlement/absence/record release before returning immutable artifact facts. The subsequent append UoW SHALL retain final current authorization, expiry, immutable version, receipt consumption, command and audit atomicity. No network/filesystem work SHALL run under SQL locks.

#### Scenario: Preflight cannot clean its file
- **WHEN** a new append's owned preflight cleanup fails or expires
- **THEN** no new version, receipt consumption or append-success fact SHALL follow
- **AND** the file/lane SHALL remain recorded independently of remote receipt expiry

### Requirement: Existing deadlines and actual local settlement

TRANSFER SHALL retain300s whole-operation/30s incoming idle; PREAPPEND SHALL retain an explicit60s phase including lookup/admission/open/read/hash/cleanup/release. Checks SHALL occur before dispatch and after return, with equality expired. Cancellation SHALL revoke progress without implying local/remote settlement or SQL rollback; each actual worker SHALL retain ownership until finally, with no queued replacement or implicit unbudgeted cleanup.

#### Scenario: Cleanup outlives caller deadline
- **WHEN** a caller returns503 while owned unlink remains executable
- **THEN** its lane/process ownership SHALL remain held
- **AND** no later success SQL or transfer of ownership SHALL follow merely from future cancellation

### Requirement: Owned bounded recovery and stale-owner isolation

Recovery SHALL use the exclusive lock, fresh epoch and exact committed identities; it SHALL touch only inactive owned buffers. Each recovery attempt SHALL have one60s total wait and no overlapping workers/queue; request-triggered work SHALL also use the role's remaining budget. Stale role/lane/epoch/reservation capabilities SHALL fail before filesystem dispatch. No payload generation SHALL be reused.

#### Scenario: Old deletion after replacement
- **WHEN** an old closed capability or previously dispatched deletion meets a new operation
- **THEN** it SHALL NOT name/write/delete/release the new generation
- **AND** true restart SHALL reconcile recorded bytes before new admission

### Requirement: Scope, legacy and evidence honesty

Generic ownership SHALL NOT adopt verification/content buffers or settle remote objects/fences. Unknown legacy or extra generations SHALL fail503 without a general sweep or permissive migration flag. Aggregate BP03 resource acceptance SHALL remain OPEN until actual filesystem/process/provider/HTTPS/coverage/CI/integration gates pass; source or synthetic-clock tests SHALL NOT be reported as physical restart or elapsed-time proof.

#### Scenario: Compare distinct namespace evidence
- **WHEN** generic TRANSFER/PREAPPEND tests pass
- **THEN** evidence SHALL name only the proven role/counts/bytes and actual source
- **AND** verification/content/S3 success SHALL NOT substitute for its remaining gates
