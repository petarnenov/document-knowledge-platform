## Why

Independent review #284 found that a failed verification-source unlink leaves a private file without durable ownership. Later S3 cleanup can release the slot quota while that file remains in the persistent DM spool. Timing corrections #277/#286 do not account for those bytes across restart or new admission. Remediation #296 requires this specification #299 before implementation.

System Design §§7.5,8.1,14 requires constrained untrusted-content resources and admission. Existing S3 reservations cover quarantine/original objects; the separate content gateway has its own200MB profile. Neither establishes an approved local verification-temp limit. This amendment proposes one explicitly.

## What Changes

- Propose one durable local verification workspace per DM instance, retaining the existing single-verification execution lane.
- Reserve one source and one reusable scratch buffer, each at most100000000 bytes; propose a distinct200000000-byte verification-local maximum.
- Cover source spooling, the S3 adapter's local copy scratch and the application's readback scratch under the same ownership; COPY and READBACK reuse the scratch only after confirmed cleanup.
- Keep failed/unknown local cleanup reserved independently of SQL/S3 quotas. Validate exclusive ownership and recover only recorded files before reuse, including after crash/restart.
- Preserve current authority, error precedence, whole240s/shared-storage60s budgets, one scan/inspect job, remote-copy uncertainty and receipt semantics.

## Capabilities

### New Capabilities

- `verification-temp`: bounded private verification workspace, durable reservation, safe cleanup, cancellation and recovery.

### Modified Capabilities

- `local-runtime`: singleton workspace wiring, strict startup/readiness and lifecycle ownership.

## Impact

Application-owned technical ports and outbound filesystem/storage adapters change. Public DM/BFF routes, OpenAPI, SDK DTOs, domain database ownership and S3 quota meaning do not change. New files are private operational metadata, not verification evidence or client authority. No production source, dependency or runtime change belongs to this draft.

## Scope and Approval Boundary

This is an unapproved development proposal. Independent specification/security review and strict validation precede recorded development approval and #296 source changes. It grants no CID, legal-policy, production or architecture/security-control sign-off.

Generic transfer buffers, receipt/pre-append preflight buffers and the content gateway are separate lifecycles. Their remaining orphan risks must be tracked before full BP03 resource acceptance; this amendment neither silently adopts them nor claims to bound all DM temporary disk.
