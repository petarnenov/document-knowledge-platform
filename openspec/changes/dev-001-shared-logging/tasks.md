## 1. Protocol and reviewed contract — #457

- [x] 1.1 Persist and independently review the owner BLOCKER requirement, all-build shared destination and Beacon semantics in the local protocol/queue (LOCAL_VERIFIED in defcb04; dev adoption remains pending).
- [ ] 1.2 Validate official OpenSpec artifacts and versioned ingestion/query contract; resolve architecture/security review findings and record exact-revision development selection before runtime edits.

## 2. Persistent telemetry boundary — #457

- [x] 2.0 Implement the separately selected framework-free build/execution/event identity foundation and dependency-purity checks. Local defcb04: independently verified60 pinned Linux tests,62/62 LINE and18/18 BRANCH; no API/database/Beacon/CI/integration claim.
- [ ] 2.1 Implement hexagonal logging service, owned PostgreSQL schema, migration, indexed scoped queries and idempotent commit acknowledgement with negative tests.
  - Local partial implementation `3fe6670`: framework-free domain/application, owned SQL migration, JDBC scoped atomic commit/replay/receipts and bounded observed-volume admission. Independently reviewed pinned Linux86 tests, including12 real PostgreSQL tests; zero failures/errors/skips. Domain78/80 lines49/50 branches, application32/32 lines36/36 branches, JDBC142/142 lines63/66 branches. Query/API, actual observer/runtime and full J1 request-deadline verification remain open; disposable test DB is not the shared persistent destination.
- [ ] 2.2 Implement scoped producer/operator credentials, Beacon ingress validation, redaction, bounds, loss/degraded reporting, retention and restart/outage tests.
- [ ] 2.3 Implement persistent shared-runtime lifecycle independent of build/task fixtures using reviewed existing immutable image pins; preserve owned cleanup boundaries.

## 3. All producers and build provenance — #457

- [ ] 3.1 Propagate explicit compilation buildId and execution runId through Gradle/Vite/images/manifests, runtime tools and CI; test repeated same-SHA compilations and rebuild provenance.
- [ ] 3.2 Instrument FE console/error/rejection/request/query/mutation/route/identity boundaries using bounded Beacon delivery; test sanitization, offline/unknown/rejected delivery, session transitions and nonblocking actions.
- [ ] 3.3 Instrument BE services and worker operations, DB/provider/retry/deadline/cancellation/cleanup boundaries and sanitized exceptions with bounded asynchronous emission; reconcile preserved #166 diagnostics without concealing its unresolved cause.
- [ ] 3.4 Integrate infrastructure/build/CI/runtime producers with the common API, durable sanitized spool/import and explicit reachability/persistence checks; preserve isolated test network controls.

## 4. Real acceptance and integration — #457

- [ ] 4.1 Prove FE/BE/infra events from two builds in the SAME persistent store, authenticated indexed filtering, restart persistence and reconstruction of a controlled real failed operation.
- [ ] 4.2 Verify delayed/outage Beacon nonblocking UI, recovery/deduplication, bounds/retention/loss, trusted identity, cross-tenant queries and secret/content exclusion.
- [ ] 4.3 Run applicable pinned Linux source/branch coverage floors, contracts/SDK, architecture, security and E2E checks; retain source-bound RED/GREEN evidence and independent final review.
- [ ] 4.4 Open reviewed PR to dev, verify applicable exact-head terminal-success CI, authorized merge and post-dev checks; close #457 only with all acceptance evidence and save resumable handoff/current-session compaction checkpoint. Owner instruction2026-11-02: after this verified dev integration, persist the full handoff and PAUSE for harness restart before starting any next task. Preserve the full-project goal and the subsequent BP03/frontend order.
