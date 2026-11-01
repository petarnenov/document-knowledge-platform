# Development Branch and Worktree Protocol

This repository uses the same **dev-first, isolated-worktree** delivery approach as the public reference workspace.

## Branches

- `main`: release/promotion branch; do not implement directly here.
- `dev`: ongoing integration branch; all implementation PRs target `dev`.
- `spec/<id>-<slug>`: approved OpenSpec/contract preparation.
- `feat/<id>-<slug>`, `fix/<id>-<slug>`, `refactor/<id>-<slug>`, `docs/<id>-<slug>`: isolated changes based on `dev`.

Every change must include an approved OpenSpec change when it affects runtime behavior, contracts or architecture. Documentation-only updates may use `docs/` branches and a review checklist.

## Mandatory GitHub issue lifecycle

Owner requirement adopted on 2026-10-31: **have a suitable GitHub issue with an explicit priority before starting every task, and close it after verified completion**. This applies to specifications, implementation, bug fixes, tests, reviews, infrastructure, delivery and protocol changes. A task checklist in a spec or chat does not replace its GitHub issue.

1. Before task execution or delegation, search for and reuse a suitable existing issue. Create a new issue only after the duplicate check below; an existing suitable issue satisfies issue-before-work. Include the concrete problem/outcome, scope, acceptance criteria, verification plan, dependencies, delivery package and an explicit `priority:P0`, `priority:P1`, `priority:P2` or `priority:P3` label. Record the priority reason; do not leave it implicit in the title.
2. Use P0 for an active critical incident or immediate security/data-loss emergency; P1 for security, data integrity or a required delivery gate; P2 for normal planned feature work; P3 for optional improvements. Priority does not waive dependencies or review gates.
3. Map each independently executable or delegated scope to a suitable issue with distinguishable acceptance criteria. Reuse existing issues for that scope; delegation or a new agent does not itself require another issue. A package/tracking issue may group child issues, but does not replace their distinct acceptance tracking. Do not create an issue per individual assertion, shell command or other non-independent mechanical step.
4. Link issue numbers in the OpenSpec task checklist, delivery queue/status and PR. Keep the issue open while any acceptance criterion, required review, test or integration gate remains unfinished. Report the issue, execution state and verification results in the 15-minute status.
5. Update acceptance/evidence as work proceeds. The final evidence records implementation/specification/PR links, relevant tests and coverage, independent review and integration commit. A work item whose acceptance requires integration closes only after the reviewed PR is merged and applicable checks pass; a pure review/research item may close when its documented deliverable is accepted.
6. Close the issue as completed only after all of its criteria pass. Blocked or deferred work stays open with the blocker and next action visible. Superseded/duplicate/cancelled issues must say so explicitly and must not be reported as implemented.
7. Work already started before this requirement must be backfilled with its true execution state and evidence, explicitly marked retrospective. Never invent an earlier issue/approval timestamp. All new work follows issue-first ordering immediately.

## Mandatory duplicate check and consolidation

Owner correction on 2026-11-01, tracked in [#5](https://github.com/petarnenov/document-knowledge-platform/issues/5): issue-first means **find or create**, never create by default.

Before every new issue:

1. Search **both open and closed** issues using component, symptom, exact error, acceptance criterion and related BP. Use several focused queries; a title-only match or absence of one title is insufficient. Read likely issue bodies and relevant comments, linked evidence, dependencies and resolution history.
2. For the same unresolved problem, update the existing issue. A new commit, failed repeated test, new CI run, diagnostic step or clarification belongs there; none alone justifies another issue. Create a separate issue only when an independently proven problem has its own distinguishable scope and acceptance criteria.
3. For recurrence of a closed problem, explicitly decide whether to reopen the original or create a linked regression. Record the reason, the historical fix/source and the new evidence; do not erase the original completed result.
4. For distinct new scope, link parent/related issues and explain the difference. Include this short record in the new issue body:

   ```text
   Duplicate check
   Searches: <open + closed queries; component/symptom/error/acceptance/BP>
   Candidates read: <issue links and scope/resolution comparison>
   Decision: <why existing issues do not cover these acceptance criteria>
   Ownership: <responsible agent/workstream; final recheck time>
   ```

5. With parallel agents, the lead reserves one owner for the scope and coordinates issue creation. Recheck open and closed matches immediately before creation; a previously completed search is not a reservation. If another agent has already opened a suitable issue, reuse it and update the ownership record.

Before changing an overlapping issue set, publish a short map of the primary task, related tasks, distinct scopes and probable duplicates. Confirm duplicates from their actual acceptance and evidence, rather than a shared error label. Different component/security boundaries or independently testable fixes may remain distinct.

For proven duplicates, first transfer or explicitly cross-link **all** acceptance criteria, evidence, dependencies and related/PR links into the canonical issue, including unfinished gates. Add bidirectional references and a rationale to both issues. Preserve the original bodies, comments and artifacts. Mark the duplicate as `duplicate` or `superseded`, and close it with GitHub's **not planned** reason, never completed. The canonical issue stays open until all consolidated acceptance passes. Report exactly which IDs were consolidated and which were deliberately retained separately; consolidation is not implementation success.

Local verification, CI confirmation and dev integration remain separate states. Recording this rule does not alone prove adoption: the delivery evidence must show the searches, the reuse/new-scope decision, any consolidation, independent review and actual PR/CI/integration result. No change to security, runtime or production acceptance gates is authorized by this workflow.

## Mandatory compaction after task closure

Owner requirement adopted on 2026-10-31: after **every completed and closed task**, run `/compact` using the environment's actual current-session context-compaction mechanism before beginning the next task. This is part of the issue lifecycle, including specification, implementation, review, research and delivery tasks.

Before compaction, save a resumable handoff: full-project goal, current worktree/branch/commit, closed issue and completion evidence, PR/CI/integration state, decisions and approval boundaries, open blockers, next prioritized issue and exact next action, plus the next 15-minute status deadline. Keep the repository task/queue records and local delivery state current. Compaction must not reset the full-project goal, lose unresolved acceptance criteria or imply that unfinished issues are complete.

Invoke only a real mechanism that compacts the **current session**. If the environment exposes no such tool or command, record `compaction pending: /compact` with the saved handoff, disclose the limitation and preserve the pending checkpoint for the next supported session boundary. Never report compaction as executed without evidence, start a separate assistant process as a substitute, or delete session history. Automatic platform compaction may satisfy the step when observed; do not force it through destructive or unrelated actions.

Standing automatic development-specification approval does not remove this lifecycle. Always open PRs for independent review; merge only after explicit terminal success of all applicable checks under the owner’s renewed 2026-11-01 authorization. The owner renewed dev merge authorization on 2026-11-01, subject to those gates; a CLI auto-merge request alone does not enforce them. Architecture, security-control, CID and production sign-off must never be inferred.

## Mandatory local verification and publication order — #427

Every publication follows **local verification in the CI environment → independent review and fixes → push → CI confirmation**. PR review remains required. Implementing this protocol requires working shared commands and automated evidence checks, plus its first demonstrated application to BP-03 #421; documentation alone is insufficient.

1. Identify all affected checks from the workflow and approved acceptance plan. Execute the same commands and settings locally and in CI, using the same pinned runtime versions and immutable Linux/container images. Host macOS checks may supplement this evidence but cannot establish Linux parity. Record any remaining legacy workflow difference explicitly.
2. Keep a sealed source snapshot and record commit, source fingerprint, image digest/platform, runtime and coverage versions, exact command, result and evidence/log locations. Validate that evidence against the source before publication. Source, test, configuration or command changes invalidate affected results; rerun affected checks and obtain review of the final change.
3. For a CI failure, retain the original failing test/log before a fix. First reproduce the concrete failure locally, retain RED evidence, apply the correction and retain GREEN evidence. Then run the complete affected suite and applicable source/branch coverage checks. A controlled fault that demonstrates a possible failure mechanism is not proof of the original CI cause; label it accordingly.
4. Do not diagnose primarily by successive pushes. If a failure is specific to GitHub's runner and remains unreproducible, record the observed environment differences, attempted reproductions, remaining unknowns, a specific hypothesis and a bounded diagnostic CI plan before publishing that experiment. A passing diagnostic run does not by itself prove causality.
5. Use task-owned isolated verification containers, networks, storage, ports and evidence directories. Never restart, reconfigure or clean another task's runtime or the manual acceptance environment. Unit verification containers must not receive the Docker socket or unrelated credentials. Real integration checks retain their genuine providers in separately owned environments.
6. Never weaken assertions, time limits, security controls or coverage thresholds to obtain green checks. Do not replace real integrations with mocks. Long acceptance scenarios remain separate and mandatory before integration; fast expiry tests do not replace the genuine 60-minute acceptance.
7. Obtain independent review after local checks. Fix findings and revalidate changed source before push. After publication, observe the exact published commit's required CI checks; a previous commit's green CI is insufficient.

Use these distinct status terms, with source and evidence links:

- **LOCAL_VERIFIED**: applicable local checks passed in the pinned CI environment; identify any outstanding acceptance gates.
- **CI_VERIFIED**: required CI checks passed for the published commit; identify any separately pending long acceptance or review gate.
- **INTEGRATED**: the reviewed PR is merged into dev and required integration checks passed. Local or CI success alone does not imply integration.

The shared runner and its evidence validation command live under `tools/ci/`; the workflow invokes the same entrypoint. Keep the supported commands and profile scope documented alongside the runner. A partial Python parity profile must not be described as parity for Java, browser or real provider checks it does not execute.

### Shared Python runtime profile

From a checkout containing the reviewed runner, verify a canonical source checkout (including its tracked working changes) and then validate its receipt:

```bash
python3 tools/ci/verify.py python-runtime --source /absolute/source-checkout --evidence /private/tmp/task-owned-new-evidence
python3 tools/ci/verify.py validate-evidence --source /absolute/source-checkout --evidence /private/tmp/task-owned-new-evidence
```

The evidence directory must be fresh and outside the acceptance runtime. The runner seals tracked public source, records its commit/index/fingerprint, builds the pinned Linux/amd64 Python/Java/coverage image, and runs the common Python profile without network or a Docker socket. CI uses the same entrypoint. Preserve the private build log, test log and receipt. Validation must fail after affected source or runner changes; rerun rather than editing a receipt. Stage new intended source files before checking: untracked files are not part of the sealed source.

This profile covers Python runtime/bootstrap tests and their applicable coverage floors. It does not execute Java service suites, browser tests, real provider integration or the long expiry scenario; those remain separately required using their own pinned shared commands. The new pinned Python environment is a deliberate reproducible baseline, not a claim to reconstruct the unknown Python patch/core of historical CI.

## Owner-selected object store

Owner decision on 2026-10-31: use **RustFS** when the project first introduces object storage. Tracking: [#53](https://github.com/petarnenov/document-knowledge-platform/issues/53), implementation [BP-03 / #35](https://github.com/petarnenov/document-knowledge-platform/issues/35). Keep the storage port S3-compatible; validate the exact required operations against the [official S3 compatibility guidance](https://github.com/rustfs/docs.rustfs.com/blob/main/content/en/administration/protocols/s3.md) and [official container documentation](https://docs.rustfs.com/en/installation/container) when integrating. Select a maintained version and immutable image digest during BP-03, with actual quarantine/private-bucket/scoped-credential/content-access and failure tests. This selection does not waive upload scanning, tenant isolation, retention or production gates. BP-02 may define verified-artifact contracts without starting an object-store runtime.

## Local worktree commands

Run from an existing clone:

```bash
git fetch origin
git switch dev
git pull --ff-only origin dev
git worktree add ../dkp-feat-001 -b feat/dkp-001-foundation origin/dev
cd ../dkp-feat-001
# Implement exclusively in this worktree; stage intended public files explicitly.
git add path/to/changed-source path/to/tests
git commit -m "feat: scaffold domain services and contracts"
# Run the applicable shared pinned-environment checks and validate their receipts.
# Obtain independent review; commit fixes and repeat affected verification.
# A new commit invalidates receipts bound to the prior commit.
git push -u origin feat/dkp-001-foundation
gh pr create --base dev --head feat/dkp-001-foundation --draft
```

After review and merge to `dev`:

```bash
cd ../document-knowledge-platform
git fetch origin
git switch dev
git pull --ff-only
git worktree remove ../dkp-feat-001
git branch -d feat/dkp-001-foundation
```

Promotion from `dev` to `main` uses a separate reviewed PR with a compatibility manifest and tested release evidence. Do not auto-merge.

## PR gates

1. Suitable prioritized GitHub issues exist before task execution; mandatory duplicate search/reuse decisions are recorded before new issue creation; OpenSpec proposal/design/tasks are approved before implementation.
2. Contracts, compatibility checks, and security scope reviewed.
3. Java/TypeScript tests, dependency/secret scans, architecture boundaries and coverage pass.
4. Integration/E2E and negative authorization tests pass for affected behavior.
5. Evidence and rollback plan are attached for deployment-related changes.
6. Local pinned-environment verification and independent review precede push; CI confirmation binds the published commit. No unrelated cleanup or confidential files. Automatic development-specification approval remains authorized after independent review and passing checks. Always open PRs; merge only after explicit terminal success of all applicable checks under the owner’s renewed 2026-11-01 authorization. All applicable acceptance, coverage, security and production gates remain mandatory.

This workflow documents commands; **it does not create a worktree on a developer's machine**.
