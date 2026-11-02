## ADDED Requirements

### Requirement: Direct durable Temporal dispatch
Durable dispatcher SHALL claim committed intents then directly start real Temporal outside SQL using unique deterministic workflow ID and stored input/profile identity. Kafka delivery SHALL not gate dispatch. Conflicting or ambiguous starts SHALL reconcile exact identity, never invent a replacement run.

#### Scenario: Crash around start
- **WHEN** dispatcher crashes after DM commit or Temporal start before local acknowledgement
- **THEN** restart reconciles one exact execution and one run; AlreadyStarted is identity-checked, not blindly accepted

#### Scenario: Delayed start and no describe proof
- **WHEN** start times out and a describe initially reports not found
- **THEN** unknown remains fenced; deterministic retry cannot create a new logical run and history expiry cannot defeat the permanent local tombstone

### Requirement: Deterministic real skeleton
Workflow SHALL use versioned closed inputs, deterministic orchestration and real I/O activities only. It SHALL persist actual original-resolution evidence then WAITING_FOR_CAPABILITY when parsing is absent. It SHALL never claim parsed/indexed/READY/approved/published from a skeleton or dummy capability.

#### Scenario: Real workflow checkpoint
- **WHEN** real Temporal worker resolves a current-authorized immutable original through the fixed workload boundary
- **THEN** the exact checkpoint and honest waiting state survive restart without original bytes/secrets in history

#### Scenario: Unsupported future stage
- **WHEN** no approved parser is installed
- **THEN** no dummy success or unsafe plugin download occurs; the original30min deadline continues

### Requirement: Versioned artifact checkpoint reuse
Activities SHALL bind run/stage/contract/input digest and exact tenant, profile, producer/image/schema and original lineage. Compatible immutable checkpoints SHALL be reused after failures; unknown or incompatible evidence SHALL fail closed. Workflow upgrade SHALL replay real retained history on a compatible pinned worker.

#### Scenario: Crash and compatibility change
- **WHEN** worker dies after checkpoint or profile/producer/schema changes
- **THEN** retry reuses only exact compatible evidence; incompatible artifacts are not mislabeled successful

#### Scenario: Rolling deployment
- **WHEN** workflow code or activity contract changes
- **THEN** history replay/versioned worker routing passes before old workers are drained

### Requirement: Bounded workflows and tenant fairness
Processing SHALL enforce the public50 global active workflow and30minute document baselines with separate stage queues and bounded activity retry/heartbeat/cancellation. Global active accounting SHALL include ambiguous starts. Candidate tenant/pending/stage limits require reviewed pins and actual timing/fairness evidence.

#### Scenario: Global contention
- **WHEN** more than50 workflows across tenants dispatch concurrently
- **THEN** at most50 actual or possibly active executions exist; one tenant cannot starve ready peers

#### Scenario: Timeout and no capability
- **WHEN** a real waiting workflow reaches its original deadline
- **THEN** safe timed-out reconciliation begins without extending time, deleting originals or falsely reporting completed processing

### Requirement: Fenced durable cancellation
Cancellation SHALL commit current-authorized CANCEL_REQUESTED and reconcile the exact workflow/attempt fence. No lease, cancelled HTTP future or signal acknowledgement alone SHALL prove stopped external work. Terminal state SHALL require authoritative execution evidence and rejection of stale effects.

#### Scenario: Both start-cancel orders
- **WHEN** cancellation races committed start, delayed start response or worker restart
- **THEN** one current fence governs; no late start/checkpoint reverts cancelled state and original versions remain intact

#### Scenario: Noncooperative late activity
- **WHEN** an activity returns after timeout/cancellation
- **THEN** stale output cannot promote results, release ambiguous accounting or grant publication

### Requirement: Server-issued worker handoff
DM SHALL persist the stage attempt before possible Start and issue current epoch/revision through the closed attempt-handoff route after fresh actor/workload checks and authoritative execution/input binding. Dispatch claim and stage epochs SHALL be distinct. Early worker and lost Start ACK SHALL reconcile the same execution without a second start.

#### Scenario: Worker before acknowledgement
- **WHEN** the real worker calls handoff before the dispatcher records Start acknowledgement
- **THEN** exact start-history input evidence binds the pre-existing attempt and later acknowledgement cannot reset it; unknown evidence remains503

#### Scenario: Checkpoint acknowledgement lost
- **WHEN** resolution or checkpoint commits and its response is lost before activity restart
- **THEN** fresh-authorized retry retrieves the same immutable resolution and original checkpoint receipt without timestamp conflicts or duplicate effects, including compatible current-epoch reuse; stale absent writes, changed evidence and revocation fail closed
