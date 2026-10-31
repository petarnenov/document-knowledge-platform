# DKP-003 / BP-02 delivery status

Scope: System Design §§6.1,6.4,6.5,7.2,8.5,10,14; full-project goal remains active.

## Tracking and current gate

Parent #34; specification/design adjudication #50; draft authoring #52; independent security review #56 and architecture/persistence review #57. Issues preceded their new execution/delegation. Initial read-only version research predates mandatory issue-first adoption and is retrospective; no earlier issue timestamp is invented.

Current state: approved development specification integrated through PR64 (commit6e53b810, devfb08ff4), with integrated-dev Foundation41221800467 all9PASS. Specification tasks50/52 closed after verified completion. Runtime implementation58–63 is in progress; all independently assigned work/fixes have prioritized issues before execution. Full runtime integration is not complete.

BP-01 implementation: PR #4 / dev merge `3f32dc573e6d2dfc58e7c6162c04c6e89b49bd56`, all final-head and integrated-dev Foundation checks passed. Its archive and owner-selected RustFS requirement integrated through reviewed PR #55 / `5486b295456974a1b0ae4f0011227d8bfbbcf254`, with all 11 final-head checks successful; integrated-dev run 41220185954 passed all nine jobs; BP-01 parent #6, archive #32 and RustFS recording #53 are closed with saved handoffs. The isolated BP-02 worktree was fast-forwarded to this current dev before approval.

## Recorded design adjudication

- Run `20261031T161752Z-claude-46789`: Option B, explicit local admin-owned read-only grant catalog behind the existing authorization contract; no new JWT action claim surface, wildcard or generic administrator authority. Missing/malformed catalog fails unavailable, known absent permission denies, and all actions also require the DM-owned ACL. External authority stays unavailable until configured.
- Addendum `20261031T163021Z-claude-62291`: add candidate `document.inspect-draft`; administrative draft list/detail/ACL visualization requires that action and ACL. Ordinary `document.read` never grants draft visibility. Creator ACL bootstrap contains only explicit catalog-authorized actions, rechecked on every later request.
- Verified fact: `DefaultAuthorities.resources()` returns DENY; entitlements return unavailable membership and workloads return UNAVAILABLE. No permissive existing binding is being replaced.
- The owner selected RustFS as the first object store (#53, independent review #54 PASS), with runtime integration in BP-03 / #35. BP-02 defines a verified-artifact boundary whose runtime binding remains unavailable until that package.

These are development implementation candidates. They do not approve CID-07 classification/action policy, CID-15 retention durations, production trust, publication, purge or release.

## Independent review findings and amendment

Initial reviews #56/#57 both found blocking contract ambiguity: mutation responses exposed inspection-only facts without inspection permission, and ACL mode/rules did not define removals or DENY restoration. Security review #56 also found inconsistent missing-inspection 403/404 outcomes. No finding was waived.

Run `20261031T163854Z-claude-72144` selected exact minimal `CommandReceipt {documentId,revision}` for successful mutations, PATCH ACL with explicit four-operation changes, scoped self-restriction/delegation with no incidental DENY removal, atomic final rule bounds and one route-specific authorization order. Amendment applied across design, deltas, both DM/BFF contract paths and negative-test matrix. Independent re-reviews #56/#57 passed; strict OpenSpec7/7, configured Redocly with zero warnings and ten mutation-success schema checks passed. Development approval is recorded in the change approval.md.

## Required verification

Before approval: complete frozen API/OpenSpec, independent architecture and security review, resolved findings and strict validation with reviewed artifact fingerprint. Before integration: pure invariant/ACL tests, real PostgreSQL migration/constraint/role/restart/concurrent-CAS tests, HTTP authorization/idempotency/anti-enumeration tests, real authenticated metadata/BFF/portal happy path, at least75% automated changed-source coverage, contract/SDK/architecture/secret/dependency checks and independent implementation review. Unknown or pending evidence remains unfinished.

## Continuation and handoff

Continue the full queue after package verification/integration; BP-03 is next and will introduce RustFS. Publish structured task/execution status every15minutes. After each verified issue closure, save a resumable handoff and use current-session `/compact` when available; currently no manual invocation mechanism is exposed, so pending compaction checkpoints are recorded without claiming execution.

## Implementation evidence checkpoint — 2026-10-31 17:18 UTC

- #58/#59: catalog/domain/application tests exceeded75% lines/branches. Independent#68 found ancestor replacement and final per-action creator/delegation revalidation defects; fixes#69/#70 and re-review required.
- #60: twelve adapter tests passed, including eleven real PostgreSQL tests; persistence93.33% lines/88.46% branches. DML/migration roles, transaction/CAS/rollback and real restart tested.
- #61/#65: four published contracts, twelve contract/helper tests, Null helper100% lines/branches/functions, deterministic SDK drift and Java/TS compile PASS. Approved null-only schema retained. Additional persisted DM contract guard test is being verified. HTTP#66/#67 and key-path#72 remain open; focused real HTTP metadata/ACL/hold happy path passed; full negative suite pending.
- #62: BFF27testsPASS94.87% lines/84.13% branches, portal58testsPASS98.58% lines/96.62% branches and built Playwright4PASS. Real authenticated metadata journey has not run against the new stack. Independent#71 in progress.
- #63: runtime17+bootstrap5Python tests PASS, affected Python sources each>=75% lines/branches. Hardened seven-component Compose configured; old six-container stack has not been recreated. Real provider/E2E/DB outage-recovery verification outstanding.

No implementation PR/CI/merge or production readiness is claimed. After closures50/52 resumable handoffs were saved; compaction pending: /compact (manual current-session mechanism unavailable).

## Path-integrity adjudication — review fixes69/72

Away decision `20261031T171835Z-claude-16968`: shared strict complete-path helper; ownerUID0/current process only, no symlink or group/other write or sticky components, bounded regular-file read. Linux POSIX ACL write grants are constrained by the checked group mask; a supported Java ACL view additionally rejects untrusted mutating ACEs. Unsupported non-Linux ACL inspection fails closed. In particular macOS host fails closed by design; real-platform authority integration runs in Linux CI/Compose. No `/bin/ls` subprocess ACL parser is introduced; native unit policy tests inject a platform descriptor only through test seams, never runtime configuration. Apply and independently re-review, >=75% helper source coverage and real Linux evidence required; no production approval.

## Linux and combined verification — 2026-10-31 17:32 UTC

Isolated Linux source snapshot, nonroot10001 and pinned Temurin25.0.5+8: actual SYSTEM catalog11/11PASS zero skips,92.47% lines/88.68% branches. Eleven real PostgreSQL tests passed; catalog bridge initially failed because its fixture remained under shared writable/tmp, correctly rejected by new path policy. Fix#73 moves only test tempdir into protected module build path; fresh Linux bridge/PG/application/HMAC run13408 pending. Root architecture, identity/security modules and BFF/KB/Search builds PASS21s82tasks. No production policy weakened.

HTTP raw4216byte negative request reproduced heap exhaustion in Linux as on macOS (diagnostic run23319 failed, not acceptance); jcmd attach could not allocate/respond, JVM thread dumps preserved locally. Only owned failed testworker was terminated after diagnostics. Agent away diagnosis run20261031T172106Z-claude-20092 remains live; no verified fix or waiver. Actual seven-component runtime/E2E remains pending.

## Independent stable-scope review accepted — 2026-10-31 17:35 UTC

#68 finalPASS closed with saved handoff, compaction pending:/compact (manual invocation unavailable). ActualLinuxcatalog11PASS0skip; freshbridge+PG12PASS0skip; Hmac7/readiness1/realHTTPkeyvalidation1=9PASS0skip, Hmac52/56lines92.86%/34of34branches100%. Persistence70/75lines93.33%/46of52branches88.46%. Reviewer inspected reports, accepted69/70 andtest-only73 sourcecorrection. Pure review completion does not close implementation/fix tasks or replace root71/fullHTTP/realstack/CI/integration.

## Bounded HTTP body correction — 2026-10-31 17:40 UTC

Initial away diagnosis20092 timed out900s withoutdecision; retry20261031T173701Z-claude-38028 approved framework InputStream body binding capped readNBytes16385 on existing BLOCKING executor, unchanged strictcodec/16KiBserverlimit/security/pins/heap/coverage. Appliedunder#74. IsolatedLinux focusedrun96159: raw4216negative400PASS, validmaximumtitle256+description4096successPASS; noOOM. Oversizebodyreturned400insteadrequired413; size-exceptionmappingfixforward andfull16HTTP+legacy/coverageverificationremainpending. This verifies the workaround path so far, not a proven upstream framework rootcause. Diagnostic heap retains onlysynthetic testmaterial in ownedlocalDocker; classmetadataanalysis65017stillrunning, no actualcredentials exported.

Diagnostic87364 preserved firstOOM with coverage/heap unchanged. Read-only classmetadata analysis65017 of local synthetic-test heap completed: DelayedExecutionFlowImpl OnErrorResume7,058,944objects169,414,656bytes; Map4,705,964/112,943,136; FlatMap4,705,963/112,943,112; OnComplete2,352,981/56,471,544; Then2,352,981/56,471,544; primitive byte arrays only7,610,076bytes. Evidence indicates runaway deferred-flow accumulation on original path, not just a large payload byte copy; exact upstream caller remains unproven. No raw heap, token values or actual user data published.

## Actual runtime acceptance checkpoint — 2026-10-31 17:57 UTC

Final production stream code passed all33bootstrap tests/17HTTP inLinux38s; source96.38%L93.41%B. Additional factory-binding#76 tests passed Linuxfullbuild26s:35bootstrap/0skip, Factory100%L87.5%B, service96.38%L93.96%B; actual external/unconfigured bindings ignore standalone catalog and do not acquire datasource.

All7hardened Compose images built and7componentshealthy. Root verified old6containers belonged exclusively to ownedBP01worktree before stopping; no unrelated service touched. Newignoredsyntheticsecurity generated privately; smoke/strictHTTPS/realprovideraccountmutation+subjectbinding/PGscopedroles+migration+fsync checksPASS. InitialactualKeycloakHTTPSbrowsermetadatajourney1PASS5.2s, no mocks. Rootauditedandfound ACLstepswerepermittednoops; #77 now asserts actualreadALLOWaddition/removal+persistedrevision+UI but awaits rerun.

ActualPGoutage#52396 exposedunboundedreadiness: JDBCgetConnection onNettyeventloop withdefaultlongpoolwait caused10sHTTPtimeouts (independentcurl3stimeout). RootinterruptedONLYknownownedPythonPID56033withSIGINT; scriptfinallyrestoredPG andall7componentshealthyverified. No outagePASSclaim. NewP1#78 precedes boundedpool/blocking-readinesscorrectionandrealPGnegative test; actualE2E/outage/recoverymust rerunafterfix.


## Local acceptance — 2026-10-31 18:08 UTC

Source frozen. Linux full build PASS29s:36 bootstrap tests (18 HTTP), no skips; service coverage96.43% lines/93.99% branches. Real paused-PG test also independently PASS20s. Strict-HTTPS real Keycloak/browser journey PASS4.1s with persisted ACL addition/removal verified by fresh GET and UI. Actual PostgreSQL outage/readiness/liveness/restart and all-seven-table snapshot PASS; post-recovery smoke verifies all seven containers healthy. Runtime19+bootstrap5 tests PASS. Full requirement/test mapping and limitations are in the change verification.md.

Root review71 accepts the new readiness offload/default-pool bounds,77 real-ACL regression and79 database-only complete snapshot after direct source review and real execution. Review68 previously accepted its separate stable slice. PR/final-head CI/integrated-dev evidence remain pending under75; implementation/fix issues remain open. BP03 RustFS remains next.
