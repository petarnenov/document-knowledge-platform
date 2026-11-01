# AI Coding Agent Instructions

- Source of truth: approved OpenSpec changes, public architecture guidance, and versioned contracts.
- Do not invent approval for architecture, security controls, or production-readiness gates.
- Maintain hexagonal dependency direction and logical database ownership.
- Use Java LTS and compatible pinned maintained library versions.
- Add tests with every change; minimum 75% automated source coverage plus E2E happy paths where relevant.
- Treat all uploaded content as untrusted. Never weaken tenant checks for local development.
- Never commit credentials, private repositories, employer/customer data, or internal architecture documents.
- Owner selection: the first object store introduced by this project SHALL be RustFS behind the S3-compatible storage port. Implement and verify it in BP-03; pin a reviewed maintained release and immutable image digest then, preserving quarantine, scoped credentials and content-access controls.
- Always open PRs for independent review; do not automatically merge. The latest owner-provided AGENTS.md instruction supersedes the earlier standing automatic dev-merge authorization. Approved OpenSpec, independent review and every applicable acceptance/coverage/security gate remain mandatory. Automatic specification approval remains authorized after independent review and passing checks. This grants no architecture, security-control, CID or production-release sign-off.
- The active delivery goal is the fully implemented project, not an individual package. Follow docs/implementation/queue.md and continue to the next ready package after verification/integration without asking for renewed approval. Do not mark the full goal complete at a package boundary. Use this project's implementation protocol; do not apply GeoWealth-specific workflows.
- Before starting any task, create a GitHub issue with an explicit priority, acceptance criteria and verification plan. This applies to specifications, implementation, fixes, tests, reviews and delivery/protocol work. Link the issue in the OpenSpec task and PR, report progress against it, and close it only after verified completion with evidence. Apply the issue lifecycle in docs/development/worktree-workflow.md; backfill preexisting work honestly rather than claiming an issue preceded it.
- After each task is verified complete and its GitHub issue is closed, persist a resumable handoff and run `/compact` (the environment's current-session context-compaction command) before starting the next task. Preserve the full goal, issue/PR state, evidence, decisions, blockers and next step. If no current-session compaction mechanism is exposed, record the pending command explicitly; never claim it ran or substitute a different session.
