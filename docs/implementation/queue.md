# Autonomous implementation queue

Goal: **the fully implemented Document Knowledge Platform described by the public System Design**, including the complete administration portal and the security, processing, retrieval, reliability and operational test evidence. Completing one package does not complete this goal.

The owner confirmed this scope and uninterrupted autonomous execution on 2026-10-31. Continue immediately to the next ready package after its predecessor is independently reviewed, verified and integrated. The latest owner instruction explicitly authorizes automatic specification approval and PR merging into dev for all developed tasks, including specifications and implementation, after independent review and passing checks. Use isolated worktrees from current origin/dev, official OpenSpec changes, versioned contracts, independent reviews and passing checks. Do not use GeoWealth-specific delivery workflows. Production deployment/release remains separately gated.

## SDLC and reporting

Follow requirements and architecture analysis → OpenSpec/contract/design/test plan → independent specification review and recorded automatic approval → implementation with tests → verification/coverage/security/contract/E2E evidence → independent implementation review and fixes → PR/CI → authorized automatic integration into dev → completion audit/archive. Preserve scope and evidence at every transition.

Before starting each task in this queue, create its prioritized GitHub issue with acceptance and verification criteria, then link it in the task checklist and PR. Track independently executable/delegated items separately beneath a package issue. Close issues only after their verified completion/integration evidence is recorded. This covers specifications, code, tests, fixes, reviews and protocol/delivery work; preexisting work is backfilled explicitly. See the mandatory issue lifecycle in `docs/development/worktree-workflow.md`.

After every verified task closure, save the resumable handoff and run `/compact` in the current session where supported before the next task. Preserve the full goal and unresolved work; record a pending compaction checkpoint honestly when the environment provides no invocation mechanism.

While work is active, publish a structured status at least every 15 minutes with completed, in-progress and blocked tasks, executed verification/results and next actions. Brief findings between these reports do not replace the periodic task/execution status. Resume the full queue after interruptions; package completion is a milestone, not the end of the project goal.

## Queue and dependencies

Package names and acceptance come from System Design §14; DKP identifiers below are delivery identifiers, not claims that external issue tickets exist. Split a large package into reviewable OpenSpec changes when needed without reducing its acceptance or dropping unfinished work.

| Delivery | Blueprint | Outcome | Depends on | Status / authoritative evidence |
|---|---|---|---|---|
| [DKP-001 #33](https://github.com/petarnenov/document-knowledge-platform/issues/33) | BP-00 | Monorepo, independent builds, CI, OpenSpec, architecture/coverage/contract gates and SDK generation | None | Complete: PRs #2/#3 merged; canonical foundation specs and delivery record |
| [DKP-002 #6](https://github.com/petarnenov/document-knowledge-platform/issues/6) | BP-01 | Standalone/external OIDC, trusted BFF, tenant context, default-deny identity/authorization ports and workload abstractions | BP-00 | Complete: PRs #4/#55 integrated; dev5486b29 and all checks passed; canonical archive/evidence; parent #6 closed |
| [DKP-003 #34](https://github.com/petarnenov/document-knowledge-platform/issues/34) | BP-02 | DM aggregates, immutable versions, ACL/retention and owned PostgreSQL migrations | BP-01 | Complete: PR80 implementation and PR85 canonical archive integrated; final dev3de7840 /41229510527 all9checks passed; parent34 and verified children closed |
| [DKP-004 #35](https://github.com/petarnenov/document-knowledge-platform/issues/35) | BP-03 | RustFS S3-compatible quarantine/upload/scan and secured content access | BP-02 | Specification88 integrated via PR101; runtime91–99 in progress. Current Linux284zero-skip, forced RustFS69zero-skip and real HTTPS all4formats/both themes plus negative matrix pass; fresh bootstrap137 diagnosis, remaining fault gates and delivery141 still open; RustFS first store |
| [DKP-005 #36](https://github.com/petarnenov/document-knowledge-platform/issues/36) | BP-04 | KB collections, audiences, memberships and policy revisions | BP-01/BP-02 | Queued |
| [DKP-006 #37](https://github.com/petarnenov/document-knowledge-platform/issues/37) | BP-05 | Durable processing intent, Temporal dispatch and transactional Kafka outbox | BP-02/BP-03 | Queued |
| [DKP-007 #38](https://github.com/petarnenov/document-knowledge-platform/issues/38) | BP-06 | Isolated Docling parsing and versioned source artifacts | BP-03/BP-05 | Queued |
| [DKP-008 #39](https://github.com/petarnenov/document-knowledge-platform/issues/39) | BP-07 | Structure-aware HybridChunker and accurate source maps | BP-06 | Queued |
| [DKP-009 #40](https://github.com/petarnenov/document-knowledge-platform/issues/40) | BP-08 | Pinned self-hosted Qwen embedding adapter, tokenizer and vector artifacts | BP-07 | Queued |
| [DKP-010 #41](https://github.com/petarnenov/document-knowledge-platform/issues/41) | BP-09 | Ollama extraction, controlled ontology, scoped entity resolution and evidence validation | BP-07 | Queued |
| [DKP-011 #42](https://github.com/petarnenov/document-knowledge-platform/issues/42) | BP-10 | Neo4j lexical/vector/graph indexes, staging/manifests and rebuild | BP-08/BP-09 | Queued |
| [DKP-012 #43](https://github.com/petarnenov/document-knowledge-platform/issues/43) | BP-11 | Hybrid search/fusion/reranking, graph exploration, citations and final authoritative ACL checks | BP-04/BP-10 | Queued |
| [DKP-013 #44](https://github.com/petarnenov/document-knowledge-platform/issues/44) | BP-12 | Isolated Office/PDF/XLSX preview workers and source mappings | BP-03/BP-06 | Queued |
| [DKP-014 #45](https://github.com/petarnenov/document-knowledge-platform/issues/45) | BP-13 | Review/approval/publication, audience revision checks and atomic active pointer | BP-04/BP-05/BP-10/BP-12 | Queued |
| [DKP-015 #46](https://github.com/petarnenov/document-knowledge-platform/issues/46) | BP-14 | Complete accessible portal journeys, secured preview and honest rejected/error states | Relevant domain contracts | Queued; deliver UI slices with preceding domain packages where useful |
| [DKP-016 #47](https://github.com/petarnenov/document-knowledge-platform/issues/47) | BP-15 | Governed retention/legal hold/purge, append-only audit and observability | Domain/processing workflows | Queued |
| [DKP-017 #48](https://github.com/petarnenov/document-knowledge-platform/issues/48) | BP-16 | Full selective Compose, Kubernetes/GitOps, immutable artifacts, secrets, workload mTLS and network controls | Implemented components | Queued; local dependencies arrive with their owner packages |
| [DKP-018 #49](https://github.com/petarnenov/document-knowledge-platform/issues/49) | BP-17 | Load/security/DR evaluation, restore drills and operational runbooks | BP-01–BP-16 | Queued; never infer production acceptance from implementation |

## Owner-selected portal foundation — #102 / specification #105

[Owner102](https://github.com/petarnenov/document-knowledge-platform/issues/102) requires Mantine across existing and future portal screens, with accessible persistent light/dark themes. [Specification105](https://github.com/petarnenov/document-knowledge-platform/issues/105) prepares the immediate common foundation; it is not deferred to BP-14 and does not renumber packages or claim implementation. See [specification status and delivery gates](mantine-portal-theme-status.md). Backend identity, tenant, document/content trust and production gates remain intact; independent review and recorded spec approval precede runtime work.

## Full-project completion audit

Do not mark the goal complete until all of the following have current, direct evidence:

- The System Design's in-scope document lifecycle, collection/audience management, secured parsing/chunking/embedding/extraction/indexing/preview and authorized retrieval work end to end, using the selected real runtimes rather than permissive stubs or fabricated results.
- The complete portal in §11 supports its defined workflows, accessibility, themes, capability statuses and error/rejection paths. Backend enforcement is authoritative.
- Every applicable invariant and test scenario in §§6–10 and gate SEC-01–SEC-07, WF-01–WF-03, DAT-01–DAT-02, SCH-01, PERF-01–PERF-02, REL-01–REL-02 and OPS-01 has an explicit evidence location. Unknown or unavailable evidence remains unfinished.
- Each domain/worker builds and deploys independently; database ownership and hexagonal direction are enforced; versioned contracts/SDKs, pinned supply chain, source coverage floors and E2E happy paths pass.
- Full local integration is reproducible with generated development secrets and synthetic fixtures. Production-capable configuration and runbooks are implemented and tested without claiming a production deployment or responsible-owner sign-off occurred.
- Controlled implementation decisions CID-01–CID-15 have recorded selections and validations for their applicable milestones. Approval-only production decisions remain explicit owner gates; no architecture/security approval is invented.
- Every queue item and any added implementation subtask is finished and independently reviewed, required CI is green, and delivery PRs are reviewed and integrated into dev after passing the required gates. No hidden TODO, dummy adapter, skipped gate or package boundary substitutes for project completion.

When an external resource or a production-only owner decision blocks a criterion, preserve it visibly and continue every independent ready task. Never redefine success around the completed subset.

## BP03 development sequencing — #87/#88

The reviewed BP03 development specification uses separate verified-upload completion and existing explicit version append201. It must not expose processing readiness. BP05 retains atomic version/run/intent/outbox creation with202ACCEPTED_PENDING_DISPATCH and must explicitly reconcile legacy append compatibility and pre-processing versions. Independent security89/architecture90 accepted this sequencing and development approval is recorded in DKP-004. It does not change final project acceptance or grant production approval.
