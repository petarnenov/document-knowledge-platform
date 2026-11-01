## Context

Audit #307 baseline87296c7f6c4504864ba77acfae5d969ffcc7704a uses unchanged production classes. Its four real-filesystem diagnostic methods prove retained partial/full TRANSFER and repeated PREAPPEND files after controlled permission-denied unlink, plus three simultaneous preflight files for one receipt. Synthetic SQL/S3/clock evidence is not actual process/provider restart or elapsed-time acceptance.

Approved System Design §§7.5,8.1,12.1 requires untrusted-resource bounds/admission. Frozen BP03 content-and-limits preserves1..100000000byte sources, ten global/five tenant active slots,300s transfer/30s idle, COMPLETE240/SCAN120/INSPECT60/shared-STORAGE60, current authority and exact original/receipt semantics.

## Decisions — unapproved development candidates

### 1. Numeric scope

TRANSFER retains its existing maximum ten executing application calls. The NEW local physical-payload selection is ten durable lanes, each bounded by trusted declaredSize<=100000000bytes:1000000000payload bytes per DM instance. Retained files continue occupying their lanes after execution ends; remote quota is not their reservation.

PREAPPEND proposes a NEW independent two-global-lane profile, each bounded by the trusted receipt size<=100000000bytes:200000000payload bytes per instance. Two is a development availability/resource choice awaiting review, not an existing approved numeric guarantee. No extra actor quota is introduced; all records still bind exact actor/tenant/resource identity.

The two generic roles together permit at most1200000000payload bytes. Filesystem metadata/marker/lock overhead is separately fixed and bounded by the closed namespace. This arithmetic is not proof of an implemented whole-DM disk budget. Verification #299's proposed200MB and content #249's200MB remain separate; no shared quota release or namespace adoption follows.

### 2. Application ports and capabilities

Application owns immutable server-derived operation identity, role/lane/reservation capabilities and technical workspace ports. Outbound filesystem code owns NIO, process locking, fsync/rename, file validation and controlled recovery. Domain policy imports neither filesystem nor SDK/HTTP types. Runtime creates exactly one generic manager shared by UploadService TRANSFER and ReceiptArtifactAuthority PREAPPEND.

A capability binds role, lane, ownerEpoch, operationId, reservationId and trusted tenant/actor/document/upload identity; PREAPPEND additionally binds receiptId. No request supplies a path, epoch, lane or reservation ID. Metadata/digests are operational ownership, never authorization, command success or verification evidence. There is no runtime Path-only fallback or per-request manager.

Local helper primitives may be reused with reviewed #299 without editing its frozen scope or sharing its reservations. Names, locks, markers and role limits remain distinct from verification, content and remote cleanup. S3 direct-parent source validation stays intact because generic payloads reside directly under the configured trusted spool root.

### 3. Authority, admission and errors

Keep current membership/catalog/operation/document ACL and exact slot actor/tenant checks before local resource admission. PREAPPEND keeps DocumentService's current authorized original-command replay before any receipt preflight; only a missing command enters local work. Trusted receipt lookup checks actor/issuer/tenant/document/expiry before reading its exact immutable original. No fake actor is created for recovery.

A role with all lanes actively executing returns429 QUOTA_EXCEEDED. A known inactive retained record, invalid/unknown state, failed recovery or unsupported filesystem makes new generic admission503 UNAVAILABLE until safe reconciliation. Pending callers do not queue. Existing401/403/hidden404/409 and unavailable-authority ordering is unchanged before those local responses; no paths, occupancy or counters are returned.

Each admitted operation owns one lane and at most one payload generation. Retained capacity cannot move to a new role, tenant or upload merely because SQL/S3 counters are zero. Active TRANSFER calls retain existing per-upload exclusion. Slot initialization remains remote admission only; it cannot authorize local allocation or declare local capacity restored.

### 4. TRANSFER sequence and replay

After current authority and trusted transfer plan, reserve a TRANSFER lane durably before untrusted body read or file creation. Preserve exact streamed-size/hash checks and three-attempt/domain-state rules. Pre-write count enforces declared size and100MB; no whole-artifact memory buffering. SDK PUT uses only the owned closed-source capability and existing conditional/exact-version rules.

Before new QUARANTINED/transfer command success is persisted, settle local streams, delete the payload, confirm absence and durably release its record inside the original300s transfer operation. Preserve immutable size/hash facts in memory. All final current-authority/lease/slot checks still run in the later SQL UoW; no filesystem/network work runs under SQL locks.

If a remote write occurred before local cleanup fails, preserve existing known/unknown writer facts, exact remote cleanup/fence and charges. Local cleanup cannot settle the remote write. If input failed before remote dispatch, existing INITIALIZED reset may occur; its local record remains occupied independently until safe cleanup, even when expiry later releases SQL reservations.

An already committed same transfer command still verifies the current authorized body fingerprint before replay. Its new local attempt gets its own durable capability; cleanup must finish before returning the old success. Failure never deletes/rewrites the original command fact, invents new success, skips body verification or permits untracked payloads. A prior retained record must reconcile before another generic attempt is admitted.

### 5. Receipt/pre-append sequence

The original append replay still returns its stored minimal201 only after current authorization, without new preflight. A genuinely new append resolves its trusted receipt and acquires a PREAPPEND capability before opening/reading the exact receipt original. All current exact hash/size and producer checks remain; mutable latest, ETag, caller paths or a successful local metadata read never substitute.

The preflight's bounded60s phase includes trusted receipt lookup, local admission, original open/read/hash, stream settlement, owned deletion and metadata release. This makes the existing60s preflight bound explicit across its local lifecycle; it does not relax any existing limit or extend COMPLETE storage budgets. The subsequent append SQL UoW remains separately atomic and rechecks current authority/receipt/expiry/consumption.

Only after confirmed absence and durable record release may preflight return immutable artifact facts. A timeout/failure suppresses a new append or consumption; no canceled worker may later enter append SQL. Already-dispatched SQL/COMMIT is not declared rolled back by a timeout. Original command reconciliation and current-authorized replay remain mandatory where commit outcome is unknown.

### 6. Closed files and publication

`contracts/local-generic-temp-profile.md` freezes names, record/schema and lifecycle. Under one exclusive process lock, publish RESERVED through CREATE_NEW/forced state.next, atomic replacement and parent force before bytes. MAY_EXIST is durable before CREATE_NEW payload/write. Pre-write enforcement prevents excess bytes; metadata itself is bounded2048 UTF-8 bytes per record.

Payloads are private service-owned regular files, no symlinks/hard links; root ancestry, markers, files and filesystem support are checked. Generation-specific names and role/lane/epoch/reservation validation prevent stale capabilities from writing/deleting/releasing new data. Lock files are never replaced while a worker can still act. Unsupported/ambiguous state is503, not permissive recreation.

### 7. Budgets, cancellation and release

TRANSFER stays within300s whole operation/30s incoming-body idle, including local ownership I/O/release and remote work; smaller existing SDK limits stay smaller. PREAPPEND's60s phase includes all its local I/O/release. Coordinator waits and per-invocation local/transport cancellation are bounded by remaining time; elapsed equality fails closed. No runtime budget override is introduced.

A caller timeout/cancel revokes further application progress, not physical settlement. Track each actual local worker/stream until finally; keep its lane and process lock while it can act. No future cancellation, expired lease, process-local semaphore release or remote quota event transfers ownership. No singleton transport close or cancellation-as-S3-settlement inference is allowed.

Release requires readers/writers settled, exact payload absence, parent force and durable record removal. Later execution-lane release performs no filesystem I/O. Failed unlink/metadata force/unknown local execution keeps the record/capacity; no unbudgeted implicit retry. Earlier input/hash/network errors can clean only under their remaining role budget, otherwise leave controlled retained state.

### 8. Recovery, shutdown and legacy data

One bounded recovery worker per manager, no queue, examines at most twelve fixed lane records. Each startup/background recovery attempt has one60s total wait; request-triggered recovery also consumes that request's remaining role budget. It touches only inactive recorded generations. Timeout retains ownership until actual worker settlement; it cannot spawn overlapping retries or mint evidence/authority.

On true process exit a replacement must reacquire the OS lock and validate closed state before local reuse. This relies only on explicitly supported synchronous local-file/lock semantics; process exit does not settle S3 writes/fences. Every stale old capability fails before new filesystem dispatch. An old dispatched unlink cannot name a later generation because payload names are never reused.

Shutdown stops admission and revokes progress, retaining exclusive ownership while a worker can act. Active valid work is healthy. Inactive retained/invalid records make upload readinessDOWN while liveness remains unaffected; controlled reconciliation must finish before readiness/admission recovers. Readiness cannot return a share/content authority decision.

Fresh initialization refuses ambiguous legacy dkp-bytes/verified-copy remnants. Existing namespaces never adopt other spool families or extra generations. No arbitrary directory sweep, auto-delete/adopt flag or timeout-only lock handoff is allowed. Unknown/foreign/unsafe state requires explicit offline investigation or separately reviewed migration, not an implementation shortcut.

## Migration / rollback / remaining gates

No domain DB/public contract migration is proposed. A future separately tracked implementation must preserve legacy-mode behavior when uploads are disabled and pass diagnostics/RED-GREEN, unprivileged Linux/file/process, source/branch floors, native providers, strict HTTPS, CI and reviewed integration. Reverting to path-only logic with retained records is forbidden; retain safe ownership or fail closed.

This draft is not implementation or resource/production readiness. Audit #307 and independent review #309 are complete; independent #308 specification/security source review #315 PASS. Development approval remains PENDING applicable PR checks and separately pending #299; no production implementation is authorized yet. The aggregate BP03 gate stays OPEN; no other lifecycle's successful test is substituted.
