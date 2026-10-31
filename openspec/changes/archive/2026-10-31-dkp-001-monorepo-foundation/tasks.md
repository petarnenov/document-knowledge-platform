# DKP-001 Implementation Tasks

Automatic approval is authorized by the owner; begin implementation only after independent specification review and strict validation. Checkboxes record executed evidence, not intent.

## 1. Specification and baseline
- [x] 1.1 Inspect required project documents, Git status/remotes/worktrees; isolate feat/dkp-001-foundation from current origin/dev.
- [x] 1.2 Initialize official OpenSpec and write proposal, capability deltas, design, acceptance matrix and tasks.
- [x] 1.3 Obtain independent specification review, fix findings, run strict validation, record automatic approval and reviewed revision.
- [x] 1.4 Record standing owner authorization for automatic specification approval/merge and retained review/check gates in project guidance.

## 2. Version and build foundation
- [x] 2.1 Resolve official maintained tool/library versions and CID-01 PostgreSQL/Neo4j selections; record exact sources, pinning and compatibility evidence.
- [x] 2.2 Add Gradle wrapper with verified checksum, Java 25 toolchain, central version catalog, dependency locks and independent service modules. Verify C1/C8.

## 3. Service foundation
- [x] 3.1 Scaffold three services with inward layer boundaries and lifecycle readiness; expose only minimal probes. Verify C1/C3.
- [x] 3.2 Add architecture graph/import checks and negative violation fixtures. Verify C2.
- [x] 3.3 Add unit/integration/security-negative tests and service coverage gates. Verify C3/C7.

## 4. Portal foundation
- [x] 4.1 Add pinned pnpm workspace, React/Vite/strict TS, lint and format configuration.
- [x] 4.2 Implement accessible themed shell, real routes and fallback without business behavior.
- [x] 4.3 Add component/coverage and built-shell Playwright tests. Verify C4/C5/C7.

## 5. Contracts and CI
- [x] 5.1 Add versioned operational OpenAPI 3.1 plus empty AsyncAPI/workflow/plugin locations with documented scope.
- [x] 5.2 Add contract validation, valid/invalid fixtures, Java/TS generation/compilation and drift check. Verify C6.
- [x] 5.3 Add CI independent Java builds, source quality/coverage, architecture, frontend, E2E, contract, dependency and secret checks. Verify C8.

## 6. Local runtime and delivery
- [x] 6.1 Add independent pinned multi-stage non-root containers and minimal loopback Compose; execute smoke checks. Verify C9.
- [x] 6.2 Add environment/version/development documentation and Kubernetes/GitOps structure. Verify C10.
- [x] 6.3 Run all integrated gates and inspect coverage/current artifacts; record actual evidence for C1–C10.
- [x] 6.4 Obtain independent implementation review, fix findings and rerun affected gates.
- [x] 6.5 Push feature branch, open PR to dev with evidence, verify remote CI/review, automatically merge under owner authorization. Verify C11. Never deploy production.

Integration evidence: PR #2 merged to dev at `0b8ab4c94fadfcd8a1fb3e69f89d6f5126cfea38` after every applicable remote check passed at `981bdd99a15147db623234577e4df25a65963730`. See the delivery record for test/coverage and review evidence.
