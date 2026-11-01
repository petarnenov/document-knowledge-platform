## ADDED Requirements

### Requirement: Distinct bounded verification workspace
DM SHALL use one durable verification workspace per instance, with one SOURCE and one reusable SCRATCH reservation. Each payload SHALL be bounded before writes by declaredSize<=100000000bytes; their combined physical maximum SHALL be200000000bytes. This proposed verification-local profile SHALL remain distinct from S3 reservations and content-gateway limits.

#### Scenario: Reserve before untrusted bytes
- **WHEN** an authorized COMPLETE begins local verification
- **THEN** both local reservations and server-derived ownership SHALL be durable before source read or payload creation
- **AND** oversend SHALL fail before an out-of-bound write

#### Scenario: Existing retained file despite remote quota release
- **WHEN** prior failed unlink retains a recorded payload and its SQL/S3 slot is later reclaimed
- **THEN** local admission SHALL remain occupied until owned cleanup confirms absence
- **AND** new slots or restart SHALL NOT forget those bytes

### Requirement: Shared source and scratch ownership
The application and S3 verification-copy adapter SHALL share the same workspace port and singleton owner. SOURCE SHALL cover initial verification spooling. SCRATCH SHALL cover local S3 copy preparation and immutable-original readback, with no overlap and no reuse before prior writers/readers settle and exact cleanup succeeds. No runtime Path-only fallback SHALL create an unowned verification payload.

#### Scenario: Copy then readback
- **WHEN** conditional immutable copy completes its local work
- **THEN** its scratch SHALL be confirmed absent before scratch is reserved for readback
- **AND** no third verification file SHALL be created

### Requirement: Closed private ownership and crash-safe publication
The implementation SHALL follow the closed names, schema, bounded records, epoch and states in contracts/local-verification-profile.md. It SHALL enforce exclusive process ownership, private service-owned nonsymlink files, durable reservation before creation and atomic forced publication. Unsafe or unsupported state SHALL fail503 without adoption, arbitrary deletion or evidence creation.

#### Scenario: Partial payload or metadata publication
- **WHEN** the process crashes before or after payload creation or state publication
- **THEN** restart SHALL validate the marker, lock, committed record and exact file generation before reuse
- **AND** incomplete recorded bytes SHALL stay reserved until controlled absence confirmation

#### Scenario: Unknown legacy or foreign generation
- **WHEN** namespace initialization sees indistinguishable legacy files or recovery sees unexpected verification files
- **THEN** the operation SHALL fail503 and SHALL NOT infer ownership or delete them

### Requirement: Cleanup remains within existing execution budgets
All verification workspace I/O, cleanup, metadata publication and release SHALL consume the remaining cumulative STORAGE60s within COMPLETE240s. SCAN120s and INSPECT60s SHALL remain unchanged. Before receipt persistence, both payloads SHALL be settled and absent, with durable operation metadata released. Timeout, failed unlink or cancellation SHALL NOT permit a new receipt or unbudgeted implicit retry.

#### Scenario: Cleanup consumes the final storage allowance
- **WHEN** initial read, copy, readback and cleanup cumulatively reach60s
- **THEN** COMPLETE SHALL fail503 without new receipt/evidence
- **AND** an uncertain or undeleted buffer SHALL retain local ownership

#### Scenario: Earlier scanner failure leaves little storage time
- **WHEN** scanner or inspector rejects a source and fallback cleanup cannot finish within remaining STORAGE
- **THEN** only bounded controlled cleanup SHALL be attempted and retained ownership SHALL survive unwinding

### Requirement: Local settlement and independent remote uncertainty
Caller cancellation SHALL revoke progress but SHALL NOT free a workspace while local I/O can still act. One tracked worker SHALL retain admission until actual local settlement. Local recovery SHALL NOT settle remote S3 attempts, consume receipts, change tenant authority or release remote fences. Unknown remote outcome SHALL block its own upload without introducing an indefinite global S3 lock.

#### Scenario: Cancellation during blocked unlink
- **WHEN** the caller returns503 while its owned unlink remains blocked
- **THEN** no new local verification worker or receipt SHALL be admitted
- **AND** capacity SHALL be reusable only after actual settlement and confirmed owned cleanup

### Requirement: Stale owners and safe recovery
Recovery SHALL require the valid exclusive lock and current epoch. It SHALL touch only inactive exact recorded files under the closed namespace. Generation-specific names SHALL prevent an old dispatched unlink from naming a new operation's payload. Failed cleanup SHALL remain reserved across shutdown/restart; unsafe recovery SHALL remain503 rather than reset capacity.

#### Scenario: Old object after owner replacement
- **WHEN** a former closed owner is invoked after a new process or epoch takes ownership
- **THEN** its write/delete/release SHALL fail before filesystem mutation
- **AND** the new owner's payload and reservation SHALL remain unchanged

#### Scenario: True restart with retained bytes
- **WHEN** an exclusive process exits while recorded private bytes remain
- **THEN** the replacement SHALL preserve the reservation, reconcile only owned inactive files and confirm cleanup before new verification

### Requirement: Scope separation and remaining risks
This workspace SHALL NOT adopt transfer buffers, pre-append preflight files, content-gateway files or remote retained originals. Full BP03 resource acceptance SHALL remain incomplete until separate temp lifecycles and remaining orphan risks are tracked and verified. No existing content200MB or S3 quota result SHALL substitute for verification-local proof.

#### Scenario: Review aggregate resource evidence
- **WHEN** verification workspace tests pass
- **THEN** evidence SHALL name only the covered namespace and SHALL NOT claim all DM temporary disk or production resource readiness
