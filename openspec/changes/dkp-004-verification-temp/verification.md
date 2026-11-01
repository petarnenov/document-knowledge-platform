# Verification plan — no implementation or approval claimed

Specification issue #299; implementation #296; parents #35/#222. Baseline source dependency is #277/#286 manifest189fde569790f65c5d32caa63473a38043447feef8e9ce22568076a3b2b31f11. Its96 application/22 adapter tests and timing review do not prove this new workspace. Actual four-minute TCP component evidence also does not prove local physical accounting.

| ID | Required causal evidence |
|---|---|
| VLOCAL-01 | Real filesystem source and scratch, exact100000000 and oversend100000001; before-each-write bound, declared-size mismatch, zero/truncated payload. Count only this namespace; never allocate a whole artifact in memory. |
| VLOCAL-02 | Pre-fix failed-unlink RED: retain actual private file, then remote slot expiry/cleanup/quota release and new work; show the old bytes can outlive their reservation. Label synthetic SQL/S3 fixtures honestly. |
| VLOCAL-03 | Corrected first-unlink failure keeps exact local reservation even after remote counter release. New verification cannot create another generation; bounded owned recovery restores capacity after actual deletion. |
| VLOCAL-04 | SOURCE+COPY scratch, then SOURCE+READBACK scratch: shared port/manager, no third file, previous scratch settlement/absence before reuse; actual full source/readback SHA remains verified. |
| VLOCAL-05 | Fail publication before/after record force/atomic rename/directory force, before CREATE_NEW, during partial write and before cleanup acknowledgment. Reservation precedes bytes; unsupported/ambiguous state stays503. |
| VLOCAL-06 | Native unprivileged Linux parent permission failure retains real bytes. Restoring permissions enables only owned retry; foreign files and other spool families remain unchanged. No root-only/mock deletion claim. |
| VLOCAL-07 | Actual separate JVM holds the exclusive lock; second JVM denied. Kill/restart first with real bytes and partial metadata at phase barriers; new owner recovers only recorded files, proves physical absence before reuse. |
| VLOCAL-08 | Stale closed owner, stale capability and old generation after replacement cannot write/delete/release new files; delayed old unlink cannot name the new generation. Test successful and failed constructor/close paths. |
| VLOCAL-09 | Initial read+copy+readback+cleanup+metadata fsync share60s: immediate-before/exact/after injected-clock boundaries. Cleanup blocks/cancels without late receipt; one tracked local worker/admission remains until actual settlement. |
| VLOCAL-10 | Scanner/inspector rejection, source error, copy error and readback/hash error use only remaining STORAGE cleanup. Exhausted budget never causes an unbudgeted implicit unlink or forgets recorded bytes. |
| VLOCAL-11 | Unknown S3 copy, delayed PUT, failed exact remote deletion, legal hold and uncertain COMMIT preserve their existing markers/fences/charges. Local cleanup cannot infer remote settlement or alter hold/receipt outcomes. |
| VLOCAL-12 | Current missing membership/action/ACL, foreign tenant/issuer/actor, unknown slot and unavailable catalog keep existing error ordering before local busy/unsafe capacity responses. Repeat on command replay and receipt boundary. |
| VLOCAL-13 | Wrong marker/version, unknown fields/state/role, extra generation, symlink, hard link, wrong owner/mode, oversized metadata/payload, malformed publication and legacy remnants fail503 without adoption/deletion. |
| VLOCAL-14 | Enabled factory creates one shared port, source/copy/readback cannot bypass it, native direct-parent S3 guard stays intact. Disabled BP02 legacy mode remains unchanged; readiness/liveness and lifecycle are truthful. |
| VLOCAL-15 | Actual current real PG/RustFS/standard ClamAV/isolated inspector receipt→append→discovery→full/range SHA journey and both-theme strict HTTPS regression. No provider substitute, shortened limit or skipped gate. |
| VLOCAL-16 | At least75% changed source and branches, architecture direction, all relevant per-area floors, contract/SDK drift, dependency/secret checks, independent review, PR/CI and integrated-dev checks. |

## Evidence discipline

Record exact reviewed file hashes, commands, source/branch counters, actual bytes/files, provider identities and terminal results. Do not print local paths, tenant/actor values, content, credentials or private metadata in acceptance output. Keep a closed safe result with test ID, pass/fail, bounded byte counts and phase categories; private detailed fixtures stay ignored.

Distinguish deterministic clock tests, Java-only blocking/failure seams, real filesystem permission/process behavior and actual remote/provider/HTTPS evidence. A canceled future is not worker settlement. A same-JVM object recreation is not a physical restart. A source-review result is not resource or production acceptance.

The new maximum is explicitly proposed for verification only. Generic transfer and receipt/pre-append temp lifecycles remain separate unresolved risks until their own issue-first analysis/coverage. Content-gateway200MB and remote2GB accounting are not substitutes. Keep #296 and aggregate BP03 resource gates open while these boundaries remain unproven.

## Specification checks

Run strict OpenSpec validation for this change and all changes, JSON Schema2020 strict compilation plus valid/invalid closed-record fixtures, and `git diff --check`. Review the largest legal serialized record against2048bytes. No runtime approval follows from author validation alone.
