# Normal Java SDK durable queue recovery — #457

Implement the already selected normal producer spool/restart requirement before
isolated Java build/runtime adoption. The current local gateway certificate is
for localhost/127.0.0.1; isolated processes must retain the selected spool/import
route when the public authority is unreachable. This detail changes no ingress,
TLS hostname check, public protocol, actor/tenant authority or production gate.
Existing in-memory SDK and original build-manifest adapter remain intact.

## Small replaceable storage port

Add a `LogSpool` outbound port to `observability-java-sdk` and a replaceable
private-file adapter. The existing three-argument `LogProducer` constructor
retains its in-memory behavior; a spool-enabled overload owns the port. Keep the
kernel/metadata module independent of filesystem, transport and service layers.
The SDK may use the same JDK UnixSystem UID check already used by logging's
private-file adapters; extend only the explicit SDK dependency allowlist for that
JDK class, retaining negative service-layer checks.

Back the existing queue, including in-flight events: at most2048 events/8MiB of
sanitized encoded events. This increment persists that queue; it does not add a
second unbounded backlog or change admission limits. The total disk ceiling is
64MiB per producer, including stable/staging files and bounded metadata. A single
bounded snapshot and same-directory atomic replacement fit below that ceiling;
reject excess sizes explicitly. File/base64/envelope limits must be independently
bounded before decoding/allocation. No extra worker or unbounded per-event history.

## Original credential/scope and event binding

The spool binds the exact original ExecutionIdentity and an opaque SHA-256
fingerprint of the already-issued producer credential. Add a default optional
credential-fingerprint method to the replaceable transport port (retaining one
abstract method); JdkLogTransport derives it from its validated original token.
A spool-enabled producer requires that fingerprint and exact execution binding.
A different credential or execution cannot adopt/relabel pending records. This
fingerprint is private equality metadata, not a credential or authorization;
never persist the bearer token, registrar/reader/observer secrets or authority
claims in spool data. Server authentication/scope validation remains authoritative.

Persist only sanitized immutable event bytes and original event IDs. On load,
strictly validate the bounded private envelope, original binding, canonical IDs,
counts/bytes and every recovered event using the same typed closed wire policy
and installed vocabulary as emit. Reject malformed/unknown fields, identity
changes and duplicate IDs before any HTTP request; preserve refused evidence.
Every recovered event begins UNKNOWN and reconciles original scoped receipts
before idempotent replay. Never mint a new ID or use a new credential to make
old data replayable. New runtime registrations use separate queue instances;
normal bootstrap rollover remains explicit subsequent wiring, not claimed here.

## Filesystem and worker ownership

Require an explicit canonical private owner-owned directory and regular0600
owned files, no symlink traversal. Hold one exclusive file lock for the lifetime
of a queue to reject concurrent owners. Only fixed owned snapshot/staging/lock
names may be read or replaced; reject unknown files and unsafe preexisting state.
Sanitized snapshot writes use bounded bytes, file force, atomic replacement and
owned-directory sync. Retain previous good data on failure; never truncate an
existing snapshot in place or silently start empty after corruption. A known
owned incomplete staging file from an interrupted replacement is unconfirmed and
may be replaced after the stable snapshot/binding is verified under the lock.
No directory-wide cleanup, unrelated deletion, or actual shared runtime teardown.

Initialization/recovery may do filesystem work at bootstrap. `emit` does no
HTTP/disk work and never waits for those operations: use the one existing
background dispatcher. Copy bounded immutable snapshots outside the queue lock;
new admissions remain independent while disk/network is delayed. Persist an
admitted event before its first HTTP attempt. Track snapshot generation so
concurrent new admissions cannot be incorrectly marked spooled or overwritten.
A storage failure retains pending events, exposes degraded/unspooled status and
uses bounded retry. It cannot claim DB persistence or hide admitted crash-loss.

Only valid original scoped HTTP200 receipts establish COMMITTED. Remove confirmed
or explicitly terminal events from subsequent snapshots; a failed cleanup rewrite
may leave an old committed event on disk, which must reconcile safely after
restart. Do not turn cleanup failure into event loss or a second side effect.
Close stays nonflushing/nonblocking; bounded awaitClosed is optional. Release the
file lock/transport only after the actual worker exits. Closing/interruption does
not assert unconfirmed events were spooled, persisted, lost or physically stopped.
Expose bounded pending/spooled/unspooled/degraded state and existing delivery
counters without recursive logging.

## Normal proof and scope

Add meaningful tests for original typed sanitation and binding, real private file
write/load/lock/atomic replacement, refusal of corrupt/symlink/foreign credential
state, existing queue byte/event caps, explicit unspooled/degraded state, and
nonblocking emit/close during delayed storage. Prove two separate producer
instances restore the exact original IDs/identity and reconcile UNKNOWN before
replay. Cover normal outage/restart/recovery and commit-before-cleanup replay
without changing endpoint authentication, tenant checks or receipt honesty.
Enforce >=75% changed line and branch coverage in pinned Java25 Linux. Reuse the
actual SDK→TLS API/PostgreSQL/query acceptance with a normal process-restart
spool/replay path before reporting real recovery delivered. No mock-only E2E.

Exact independent detail review, strict OpenSpec validation and standing owner
development selection precede implementation; source-bound evidence and final
independent review precede local commit. Pathological filesystem/OS hangs,
sustained capacity/endurance and #458/#459 exceptional matrices remain OPEN/P3.
Actual Java build/Gradle/Vite/image provenance adoption, all component
instrumentation, JS/TS Beacon+Node and Python SDKs, real two-build common-store
acceptance and full #457 PR/exact-head CI/dev/post-dev gates remain OPEN.
