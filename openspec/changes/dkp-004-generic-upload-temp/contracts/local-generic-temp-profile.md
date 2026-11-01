# Proposed generic upload temporary ownership profile

Draft #308. These are explicit development candidates, not recorded approval. Existing public/domain limits and prior reviewed #299/#249 scopes are unchanged. Values are decimal payload bytes, excluding fixed filesystem metadata overhead.

| Role | Active local operations | Durable lanes | Per-payload maximum | Proposed payload total |
|---|---|---|---|---|
| TRANSFER | Existing maximum10; retain existing per-upload active exclusion | NEW10 retained-capacity lanes | trusted declaredSize1..100000000 | NEW1000000000 |
| PREAPPEND | NEW maximum2 globally, no per-actor quota selected | NEW2 retained-capacity lanes | trusted receipt size1..100000000 | NEW200000000 |

A lane is occupied by an active operation OR its retained file/record, never freed merely because execution or remote quota ended. The generic payload sum is at most1200000000bytes per DM instance after this protocol is actually implemented/proven. This is not verification/content quota, cluster-wide storage sizing or whole-DM resource acceptance.

## Exact namespace

The configured trusted private spool root contains `.upload-temp.lock` and `.upload-temp/`. The directory contains the fixed `.owner` marker plus optional `transfer-00.json`..`transfer-09.json`, `preappend-00.json`..`preappend-01.json`, and their corresponding `.next` publication paths. No other child names are accepted. Marker bytes are exactly ASCII `dkp-generic-upload-temp-v1\n`.

Every directory is0700; files/marker/lock/payload are0600 and service-owned regular nonsymlink files. Payloads and metadata must not be hard links. Validate configured root ancestry and existing S3 private-root guards. A valid process exclusively holds the fixed lock inode; no timed replacement/recreation of a live lock is permitted.

Payload names are exactly `dkp-transfer-{operationId}-{reservationId}.bin` and `dkp-preappend-{operationId}-{reservationId}.bin`, directly under the spool root. IDs are fresh canonical lowercase server UUIDs. Role, lane, operation, reservation and epoch come only from the capability/committed record, never request path/JSON selectors. An operation/reservation payload name is never reused, even after successful cleanup.

The exact ownership record is `ownership.schema.json`, at most2048 UTF-8 bytes. At most twelve committed records and twelve publication `.next` files exist; metadata/marker/lock overhead stays fixed and separate from payload counts. Validate actual bytes before parse; reject duplicate/unknown keys, invalid UTF-8/JSON, unexpected nulls, role/lane mismatch, wrong state/owner/mode/type or extra payload generation.

`tenantDigest` is SHA256 of exact trusted tenant UTF-8 bytes. `actorDigest` is SHA256 of4-byte big-endian UTF-8 lengths followed by exact verified issuer and subject bytes. Document/upload/receipt IDs and declared size come from current server-owned state. These private digests/records convey neither grants nor command success/evidence. Raw credentials, bearer tokens, actor values, idempotency keys and client filenames are not stored.

TRANSFER records have lane0..9 and receiptId=null. PREAPPEND has lane0..1 and exact trusted receipt UUID. All states bind role/lane/ownerEpoch/operationId/reservationId and the same trusted actor/tenant/document/upload identity. Missing or conflicting identity never falls back to a path-only allocation.

## Publication and ownership transitions

Hold the exclusive manager lock. Publish the complete RESERVED record using CREATE_NEW lane.next, force it, atomic rename to lane.json and force the ownership directory. Only successful durable publication permits the next action. Publish MAY_EXIST and force it before CREATE_NEW payload or bytes. No truncation/overwrite of an existing payload is allowed. Read/write capabilities require the current active MAY_EXIST operation; DELETE_PENDING/ABSENT, released records and closed capabilities never permit reopening or recreating a payload.

Counting happens before each bounded<=65536byte write. Payload size never exceeds trusted declaredSize or100000000bytes. Truncated/zero/oversized/hash-mismatched input fails without treating partial data as clean. MAY_EXIST covers partial files or unknown creation/write outcomes, not existence/completeness/scan evidence.

Before removing any owned payload, publish DELETE_PENDING. Confirm exact payload absence and force its parent before publishing ABSENT. Release the committed lane record only after all local readers/writers settled and payload absence was durably confirmed; remove record and force its directory. Publication/force/unlink failure retains ownership/capacity, never an implicit success or unbudgeted retry.

Normal new TRANSFER success/replay return and new PREAPPEND artifact return require payload absence and record release under their remaining budgets. Immutable hash/size/identity facts survive in memory; domain/command/receipt facts retain their original atomic SQL rules. Filesystem cleanup is never executed while holding SQL locks.

A partial `.next` cannot authorize a payload. Under valid marker/lock, a bounded private regular leftover `.next` may be removed before republishing/recovery; committed lane.json remains authoritative. A missing committed record with any corresponding/extra payload is unsafe503, not adoption from `.next`. Partial marker/directory initialization fails closed without repeatedly creating more files.

## Admission, budget and settlement

Current route/membership/catalog/document ACL and exact actor/tenant/resource checks precede local capacity responses. Existing domain/authority errors retain their order. Full active role capacity returns429; retained/invalid/unsafe namespace or unsuccessful recovery returns503. No waiting work queue, counter/path disclosure or new remote authority is introduced.

Every admitted call has one actual tracked local worker. TRANSFER's300s whole deadline starts at public operation entry before source read/claim work; incoming idle remains30s. PREAPPEND's60s phase starts at verification-port entry before trusted receipt lookup and includes admission/open/read/hash/cleanup/release. Check before dispatch and after return; equality is expired. Smaller existing transport limits remain smaller.

No expired/canceled worker may start a later payload/network phase or new success SQL. Caller timeout/cancel interrupts/closes only its owned I/O where supported and revokes forward progress; it is not local or remote settlement proof. Keep lane/process ownership until actual finally. Unknown already-dispatched SQL/COMMIT requires durable command reconciliation, not an assumed rollback.

Only actual local settlement plus durable absence permits reuse. Remote upload expiry, receipt consumption, SQL/S3 accounting release, lease expiry, same-key replay or manager/process recreation is never local release. Unknown S3 writes/exact deletions retain their existing independent durable markers/fences/charges; successful local cleanup cannot discharge them.

## Recovery and lifetime

One bounded recovery worker, no queue, validates at most twelve fixed records and exact expected generations. An attempt has60s total wait, further capped by the remaining TRANSFER/PREAPPEND budget when request-triggered. No overlapping retry follows caller timeout while the prior worker can still act. Recover only inactive validated payloads; active current work is not a recovery target.

A replacement owner needs actual OS-lock acquisition, a fresh epoch and durable rebind of the same recorded immutable identities before mutation. Supported synchronous local filesystem/lock semantics must be proven. Process exit does not settle remote work. Stale capabilities fail epoch/role/lane/reservation checks before dispatch; generation-specific names prevent a delayed old unlink from naming replacement data.

Startup validates/reconciles before readiness. Valid active operations are healthy; inactive retained/invalid state makes upload readinessDOWN until owned recovery succeeds. Shutdown stops admission and revokes progress while retaining lock/worker ownership until real settlement. Readiness/recovery mint no actor, grants, receipt, version or verification evidence.

Mixed old path-only writers and new managers on the same spool are forbidden. Upgrade is offline after all old writers are actually stopped; acquiring the new lock alone does not prove old code is absent. Fresh initialization rejects ambiguous legacy `dkp-bytes-*.tmp`/`verified-copy-*.bin` files and unknown generic generations. Existing verification/content/transfer families not owned by the exact record remain untouched. No general sweep, auto-adopt/delete flag or cross-namespace quota transfer is provided. Upgrade/rollback requires explicit offline investigation or a separately reviewed migration; path-only rollback with retained records is forbidden.
