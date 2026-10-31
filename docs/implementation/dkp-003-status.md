# DKP-003 / BP-02 delivery status

Scope: System Design §§6.1,6.4,6.5,7.2,8.5,10,14; full-project goal remains active.

## Tracking and current gate

Parent #34; specification/design adjudication #50; draft authoring #52; independent security review #56 and architecture/persistence review #57. Issues preceded their new execution/delegation. Initial read-only version research predates mandatory issue-first adoption and is retrospective; no earlier issue timestamp is invented.

Current state: development specification approved after independent re-reviews and strict checks; runtime implementation not started. No BP-02 runtime source or permissive authority binding has been added. Runtime tasks need their own prioritized issues before execution.

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
