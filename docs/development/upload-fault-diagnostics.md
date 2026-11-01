# Safe authenticated-fault failure diagnostics

The authenticated upload fault gate retains its nonzero failure exit and private
state, browser logs and exact owned-provider restoration evidence. Its public
failure output now includes one `DKP_UPLOAD_FAULT_DIAGNOSTIC` JSON line. That line
is suitable for CI console evidence; private files remain excluded from public
artifacts.

The closed summary contains only `schemaVersion`, `result` (always `FAIL`),
`phase`, `service`, `checkpoint`, `category`, `restoration` and `locations`.
Every string comes from a finite source-owned allowlist. Checkpoints distinguish
source binding, provider stop/readiness, browser execution/result validation,
read-only equality, candidate admission/failure accounting, recovery and final
consumption. Browser failures distinguish execution failure, timeout,
unavailability, bounded-output rejection and invalid/missing real PASS results.
Restoration is `not-attempted`, `restored` or `unknown`; a failed start or health
proof remains unknown and never becomes success merely because cleanup ran.

At most three source locations may be included. Only the two fixed public fault
browser source filenames and positive six-digit-bounded line/column numbers
are extracted. Arbitrary log fragments, exception messages, source lines, URLs,
identifiers, SQL, environment, credentials and runtime state are never copied
into the public summary. This is structural selection, not redaction of free
text. Unrecognized metadata falls back to a finite conservative value.

The original PR223 failure remains failed and its cause remains unknown until
a reviewed diagnostic run identifies the actual failing invariant. Adding these
diagnostics does not establish that the original problem was transient or
resolved, waive any assertion or authorize broad evidence uploads. Genuine
providers and their owned restoration must still pass the unchanged acceptance
gate; mocked diagnostic tests establish only reporting safety and control flow.
