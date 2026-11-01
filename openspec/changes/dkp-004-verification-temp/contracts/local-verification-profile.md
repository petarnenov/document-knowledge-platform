# Proposed local verification ownership profile

Draft #299, implementation #296. This is the proposed new development profile, not an already-approved resource allowance. It supplements BP03 COMPLETE only. All byte values are decimal.

| Item | Frozen candidate |
|---|---|
| Active workspace | 1 per DM instance, matching existing single-verification execution |
| Payload reservations | 1 SOURCE plus1 reusable SCRATCH |
| Per-payload bytes | 0..declaredSize while incomplete; declaredSize1..100000000; pre-write enforcement |
| Namespace physical payload maximum | 200000000bytes; derived from2 bounded buffers, separately proposed here |
| Scratch roles | COPY then READBACK; never overlapping, reuse only after exact cleanup/settlement |
| Chunk memory | <=65536bytes; no whole-artifact memory buffer |
| COMPLETE budgets | unchanged whole240s, SCAN120s, INSPECT60s, cumulative STORAGE/evidence60s |
| Local recovery attempt | <=60s caller/coordinator wait, one tracked actual worker, no queue; retained after unknown local settlement |
| Private ownership record | <=2048 UTF-8 bytes, exact closed schema `ownership.schema.json` |
| Directory/file modes | 0700 directories;0600 files; service owner, no symlinks/hard-linked payloads |

## Exact namespace

The configured private root contains `.verification-spool.lock` and `.verification-spool/`. The directory contains only `.owner`, optional `state.json` and optional `state.next`. The marker is exactly `dkp-verification-spool-v1\n` encoded ASCII. The process lock, marker and directory are validated before metadata or payload I/O.

Payloads are directly under that root: `dkp-verification-{operationId}-source.bin` and `dkp-verification-{operationId}-scratch-{reservationId}.bin`, with canonical lower-case server UUIDs from the committed record. Each scratch use gets a fresh reservationId. Only these two paths for that operation are allowed. Paths are never read from JSON, HTTP or caller selectors. No generated operation ID is reused.

Any other name in the ownership directory, extra verification payload generation, absent/corrupt owner marker, mismatched owner/mode/type, oversized record or inconsistent record/payload relationship yields503. No such file is adopted or deleted. Namespace initialization refuses legacy random `dkp-bytes-*.tmp`/`verified-copy-*.bin` remnants instead of inferring ownership.

Existing valid namespaces do not acquire ownership of other spool families. Transfer, pre-append preflight and content files remain outside this profile and must not be enumerated as owned payloads, removed or counted as proof of this namespace's limits.

## Identity and publication

`tenantDigest` is SHA256 of the exact tenant UTF-8 bytes. `actorDigest` is SHA256 of the concatenation of4-byte big-endian UTF-8 lengths and exact issuer/subject bytes. These private opaque bindings are not authorization, verification evidence or a public API. Document/upload IDs and operation/owner UUIDs are server-derived.

Under the held lock, publish the complete closed record to CREATE_NEW `state.next`, enforce2048bytes, force the file, atomically rename it to `state.json` and force the directory. Validate the current epoch/operation before each update. Only successful durable publication allows the next payload action. No fsync/rename failure is treated as success. Reject duplicate/unknown keys, malformed or invalid UTF-8 JSON, unexpected nulls and noncanonical identities; never decode corruption into an allow decision.

`state.next` is a reserved metadata-publication path, never a payload selector. Under a valid marker/lock, bounded private regular leftover `state.next` may be removed before recovery/republication; its content never authorizes payload adoption. If no committed state exists, any payload generation still causes503. Missing/partial marker initialization fails closed without repeated new initialization files.

## Reservation and states

Publish both SOURCE and SCRATCH as RESERVED before the first source read or creation. A slot's state changes RESERVED→MAY_EXIST durably before CREATE_NEW or byte writes. MAY_EXIST covers partial creation, incomplete bytes and unknown local outcome. It is not a claim that content exists, is complete or clean.

Before deleting an inactive payload, publish DELETE_PENDING. Confirm the exact owned file is absent and force its parent, then publish ABSENT. Failed deletion or uncertain local execution keeps DELETE_PENDING/MAY_EXIST and the operation's full local admission. A cleanup-attempt flag never means physical absence.

SOURCE is not reused within an operation. SCRATCH can change role only after its previous local readers/writers have settled and its payload is ABSENT. Publish a fresh scratch reservationId, the next role and RESERVED before creating its new generation. Validate role and reservationId on every scratch capability; a prior COPY token cannot act on READBACK. No COPY/READBACK simultaneous use or extra random scratch is allowed.

Normal completion deletes both local payloads, confirms absence and removes/forces the committed record before receipt persistence, all within remaining STORAGE60s. The in-memory verification lane remains occupied until actual worker finally; its later release performs no disk I/O. Receipt/evidence facts retain immutable size/hash; they do not reference the deleted temporary path.

## Recovery and stale owners

A recovery owner needs the exclusive lock and a fresh epoch bound to the same committed operation and fixed payload generation. It can recover only inactive owned buffers. Every method validates its lock, epoch and capability before touching files. A stale/closed owner cannot delete, write, release or reuse a later owner's payload.

After true process exit, validate filesystem support and committed state before deleting recorded remnants. Incomplete/partial recorded payloads0..declaredSize remain reserved until confirmed absent. Missing recorded payloads can be marked absent only under that exclusive settled ownership; this rule cannot settle an S3 PUT/delete or its durable fence.

On caller timeout or interrupted cleanup, retain admission and a tracked worker until actual local settlement. If no safe cleanup can finish, future verification remains503 rather than forgetting bytes. Remote slot expiry, S3 cleanup, SQL counter release, process restart or a fresh object key cannot free this local reservation.

No general directory sweep, retention purge or remote delete capability is introduced. Unsafe foreign/legacy state needs explicit offline investigation or a separately reviewed migration; never a permissive bootstrap flag.
