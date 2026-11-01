## Context

Specification #299 precedes implementation #296. Its source dependency is #277/#286's18-file manifest189fde569790f65c5d32caa63473a38043447feef8e9ce22568076a3b2b31f11. The independent #284 timing review is narrower than local-capacity acceptance. Existing source retains only a transient path after failed unlink; S3-only cleanup can later uncharge its slot.

The controlling architecture is System Design §§7.5,8.1,14: untrusted content, bounded disk/resources,100MB artifact maximum and resource admission. Frozen BP03 `content-and-limits.md` fixes one scan/inspect job,240s COMPLETE, shared storage/evidence60s and remote quotas. The proposed local profile below adds a reviewed limit; it does not reinterpret existing S3 or content limits.

## Goals / Non-Goals

Provide durable ownership before any verification-source/scratch bytes, bounded actual files, conservative cleanup/reuse and truthful restart behavior. Preserve independent current authorization, private exact object identities, accounting and receipt atomicity.

Do not add a public API, client path, domain database, retention rule, object-deletion grant, general spool janitor or global S3 uncertainty lock. Do not use ContentSpoolStore's reservations to claim verification capacity. Do not claim transfer/pre-append orphans solved.

## Decisions

### 1. Proposed development profile

The exact candidate is one verification operation, one SOURCE reservation and one SCRATCH reservation per DM instance. Each payload is bounded by the operation's declared size and the existing100000000-byte artifact cap. SOURCE plus SCRATCH therefore cannot exceed200000000 physical bytes in this namespace. This is a new explicit verification-local limit, independently reviewed before adoption.

The source is retained while scanning, inspection and copy/readback run. The S3 adapter uses SCRATCH for its conditional-PUT copy preparation. After its local readers/writers settle and exact scratch deletion is confirmed, the application may reuse SCRATCH for immutable-original readback. There is no third verification payload or parallel COPY/READBACK scratch.

This profile preserves the existing single verification lane, rather than serializing all S3 activity. A locally settled but remotely unknown S3 copy still blocks that upload under its existing durable marker/fence. Other uploads may verify once local reservations are safely reusable; an unknown remote outcome alone does not retain a global workspace.

### 2. Hexagonal ownership

DM application defines the workspace port, immutable server-side operation identity and opaque workspace/slot capabilities. The filesystem adapter owns Java NIO, file locking, restrictive files, fsync and recovery. S3 storage depends inward on those technical capabilities; application does not import an outbound implementation or AWS type.

The production factory shares exactly one workspace adapter between UploadService and verification-copy storage. There is no Path-only verification-copy fallback or per-request new manager. Existing normal transfer PUT and separately credentialed remote cleanup retain their scopes. All source/scratch allocation paths in COMPLETE must use this one port.

Before any resource-admission response, current membership/catalog/operation, document ACL and exact slot actor/tenant ownership retain their existing403/404/503 ordering. A legitimately busy local operation yields429 QUOTA_EXCEEDED. Invalid ownership, unknown metadata or retained failed cleanup yields503 UNAVAILABLE; neither discloses paths or counters. Final current authority and receipt checks remain mandatory.

### 3. Closed durable namespace

Use one process lock and one private ownership directory under the configured private DM spool. Payloads stay directly under that root so the existing S3 direct-parent source guard remains intact. The exact names/closed metadata contract are frozen in `contracts/local-verification-profile.md`; no request, metadata field or client filename selects a path.

Require private directories0700 and metadata/payload/lock files0600, expected service ownership, regular files, no symlinks, no hard-linked payloads and a trusted configured root. Every mutation requires the still-valid exclusive lock plus matching owner epoch, operation, role and reservation capability. Unsupported locking/atomic publication/fsync or unsafe state fails503.

Create the operation record atomically and fsync it and its parent before any payload creation or source read. Persist MAY_EXIST before CREATE_NEW or bytes; never truncate or overwrite a prior payload. Pre-write counting enforces declared size/MAX_BYTES. Incomplete/unknown files remain reserved, not inferred absent or clean.

Fresh scratch reservation IDs distinguish COPY from READBACK capabilities. Generation-specific payload names prevent an already-dispatched old unlink from naming a new operation's file. A lock/epoch check protects stale objects before every filesystem action. A completed operation's path is never reused for another operation.

### 4. Cleanup, budgets and receipt boundary

COPY scratch deletion, readback scratch deletion, source deletion, metadata updates and release fsync all belong to the same remaining STORAGE60s allowance inside whole COMPLETE240s. Scanner120s and inspector60s remain unchanged. Each I/O stage checks current ownership and budget before dispatch and after return; elapsed equality fails closed.

A caller timeout/cancel revokes progress but does not prove a blocked local I/O settled. Retain the active local operation and tracked worker until its actual finally/stream closure. No queued replacement worker, file reuse, physical-capacity release or receipt may follow merely from a canceled future or expired lease.

Before receipt persistence, both payloads must be authoritatively absent, their readers/writers settled, and the durable operation record removed/fsynced under STORAGE60s. Retain the in-process verification execution lane until the worker finally completes; its final release requires no filesystem I/O. SQL receipt/command/evidence persistence still consumes remaining STORAGE and preserves unknown-COMMIT reconciliation.

A failed unlink or budget exhaustion retains the reservation and exact operation record. Record a cleanup attempt without claiming deletion success. Earlier scan/inspection failures use only remaining STORAGE for local cleanup; once unavailable, leave the recorded file for controlled recovery rather than making unbudgeted implicit retries. No new receipt/evidence is created.

### 5. Recovery and admission independent of remote quotas

A retained workspace remains occupied even if its SQL slot expires, S3 work completes, remote quota becomes zero or another slot is initialized. Local admission is released only after recorded local payload absence and settlement. Remote quota is not a proxy for local bytes; no SQL cross-database transaction is introduced.

Recovery acquires the exclusive workspace lock and validates the closed record, owner, generation-specific names, file types/modes/links and sizes. Only inactive recorded slots are recovery targets. It may remove those redundant private buffers, never S3 objects, content files, retained originals, arbitrary root files or foreign generations.

Each recovery attempt is bounded60s, with at most one tracked local worker and no queue. If the underlying I/O outlives that wait, retain ownership/admission until actual settlement. A new request performs its authority checks first and shares its remaining STORAGE60s with any local recovery it triggers. Background/startup recovery cannot create fake actors or verification evidence.

A true process restart must reacquire the OS lock, validate all persisted state and reconcile recorded files before reuse. Process death permits local recovery only on a supported filesystem with the required synchronous local-file/lock semantics; it does not settle remote S3 writes or fences. Unsupported/ambiguous state stays503; restart is never an automatic capacity reset.

### 6. Startup, shutdown and upgrade

Initialize/recover one singleton workspace before declaring upload readiness. Active valid work is healthy; inactive unreconciled retained bytes or invalid metadata make upload readinessDOWN, with liveness unaffected. No readiness probe mints receipts or authoritative upload grants.

Shutdown stops new admission and revokes owned work. Hold the lock while a local reader/writer/cleanup attempt can still act; do not transfer ownership on a timer. Closed or stale objects cannot mutate a later owner's operation. Resource cleanup is narrowly bound to owned files; second-process/restart tests prove the lifecycle.

Fresh namespace creation must refuse indistinguishable legacy `dkp-bytes-*.tmp` or `verified-copy-*.bin` remnants. Never adopt/delete them or fabricate their slot binding. Once this namespace exists, unrelated transfer/pre-append files stay outside its ownership and proof. Upgrade requires explicit offline investigation or separately reviewed migration for legacy leftovers.

## Risks / Trade-offs

Failed local deletion can make verification503 indefinitely until safe owned recovery succeeds. This is a proposed development availability cost, not retention-law approval. It preserves physical accounting rather than assuming eventual remote cleanup removes local bytes.

Transfer and pre-append temp paths can have similar orphan risks. They remain outside this profile and must receive separate issue-first analysis/verification before full resource readiness. Content-gateway200MB and S3 quota checks do not substitute. Filesystem tests must include actual unprivileged Linux behavior; mock callbacks alone are insufficient.

## Migration / Rollback

No domain schema or public contract migration is proposed. Deploy only after this amendment is reviewed/approved and #296 coverage/filesystem/native/factory/current-provider/HTTPS/CI gates pass. Do not revert to a process-local-only implementation while owned metadata/files exist. Rollback must fail closed or retain the newer workspace manager until exact cleanup and shutdown are proven.

## Review State

Independent specification/security source review #304 accepts the frozen9-file8ee41c34 packet, with strict OpenSpec13/13 and schema4valid/14invalid checks PASS. Development implementation approval is recorded in approval.md after exactffa0cc1 Foundation41269885883 and hygiene41269885910 SUCCESS. #296 may implement this reviewed technical profile; actual implementation review and filesystem/process/provider/current-source acceptance remain mandatory. #296 and full BP03 resource/production gates remain open.
