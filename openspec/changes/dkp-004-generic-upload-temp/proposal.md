## Why

Audit #307 on committed87296c7 proves two local-ownership gaps: failed TRANSFER cleanup survives reset/expiry and repeat body replay; failed receipt/pre-append cleanup survives repeated requests without version/consumption. The same receipt also admits three concurrent local preflight files independently of new upload slots. Four diagnostic tests use actual unprivileged macOS files and controlled unlink faults; SQL/S3/expiry are synthetic, not restart/provider proof.

Approved BP03100MB sources, ten global/five tenant active upload slots,300s transfer/30s idle, current authorization and resource constraints remain controlling. Those remote reservations do not establish generic-local disk ownership. Reviewed #299 verification SOURCE/COPY/READBACK and content #249 are distinct lifecycles.

## What Changes

- Propose durable generic-local TRANSFER and PREAPPEND capabilities, shared application ports and one process-owned filesystem manager per DM instance.
- Preserve the existing maximum ten executing transfers. Propose ten durable TRANSFER lanes, each<=100000000bytes, as a NEW1000000000-byte local payload selection.
- Propose a NEW two-global-lane PREAPPEND local profile, each<=100000000bytes, maximum200000000payload bytes. No existing content/verification limit supplies this approval.
- Retain failed cleanup capacity independently of remote slot/receipt expiry, SQL/S3 accounting, replay and restart. Recover only inactive exact recorded private buffers before reuse.
- Preserve current original identity/hash checks, final ACL/receipt atomicity, remote fences, operation budgets and COMPLETE240/shared-storage60 behavior.

## Capabilities

### New Capabilities

- `generic-upload-temp`: distinct bounded TRANSFER/PREAPPEND local ownership, publication, cleanup, admission and recovery.

### Modified Capabilities

- `local-runtime`: singleton manager, distinct namespace, current-source readiness/shutdown and safe legacy handling.

## Scope and Approval

Specification #308 is created before drafting or production changes. #307 diagnostic acceptance remains independently reviewable. This draft grants no development approval, architecture/security-control, CID, retention-law or production sign-off. Independent specification/security review and applicable PR checks precede recorded development approval and a separate issue-first implementation.

No public route, SDK, domain table, object-delete grant, retention rule or general janitor changes. #299 is an unchanged reviewed dependency whose development approval is separately pending. Aggregate BP03 resource acceptance remains OPEN until all namespaces, actual source correction/tests/native/provider/HTTPS/CI and integration gates pass.
