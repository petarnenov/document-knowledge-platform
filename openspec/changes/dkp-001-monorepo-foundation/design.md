# DKP-001 Design

Status: Proposed; automatic approval is authorized by the project owner on 2026-10-31, conditional on independent review and validation.

## Context

Base: `origin/dev` at `c9b02668375a141ec7223f35a48005dd747c6fa9`. The repository contains public design, hygiene CI and placeholders. See proposal.md for motivation. System Design v1.0.0-rc1-public is a selected design baseline, not production certification.

## Goals / Non-Goals

Deliver only executable engineering foundations. No document entities, persistence, migrations, upload, parsing, search, publication, authentication provider, arbitrary plugins or production deployment. No weakening of future tenant checks. No employer or customer content.

## Decisions

1. **Physical modules enforce hexagonal direction.** Each domain has `domain`, `application`, `adapters-in`, `adapters-out`, `bootstrap` Gradle subprojects. Application owns abstract ports. Bootstrap alone assembles Micronaut. Empty layers retain documented boundaries without fake business objects. ArchUnit plus Gradle dependency checks reject forbidden inward and sibling dependencies. This avoids the weaker alternative of naming packages without enforcing their graph.
2. **Existing paths remain canonical.** Use `apps/admin-portal` and `infra/{docker-compose,kubernetes,gitops}`. System Design §5.2 paths are suggested examples. No directory rename or new architectural decision is necessary.
3. **Minimal surface.** Implement only the two probe endpoints from service-foundation. A lifecycle readiness state supports unavailable testing without an externally writable toggle. Disable other management endpoints; no database libraries or permissive authentication profiles. Anonymous, minimal probes are a deliberate operational exception to denial of resource access; they cannot identify a tenant or return resources. Test business/sensitive paths with fabricated tenant headers.
4. **Frontend shell.** React Router implements home, foundation and fallback routes; dark/light presentation only. TanStack Query and Zod remain selected for future server-state/forms, without fake API calls merely to use them. Vite development and the production static server support route fallback.
5. **Version selection as executable evidence.** Before implementation, resolve official maintained releases compatible with Java 25 and pin a Gradle wrapper, Micronaut BOM, plugins, Java patch, Node LTS, pnpm and frontend tooling. Record source URLs, exact versions and compatibility checks in `docs/development/versions.md`; include PostgreSQL and Neo4j Community selection under CID-01 even though these runtimes are not started. Compatibility for unused database adapters remains explicitly unproven and must be revalidated at their introduction. Never infer compatibility from a version number alone.
6. **Contracts without invented business behavior.** Publish one shared operational OpenAPI 3.1 v1 contract applied to each service. Validate it, generate Java/TS clients with pinned tooling, compile them and check drift. Create AsyncAPI v1, workflow v1 and plugin v1 directories with explicit no-contract documentation; exercise AsyncAPI validation with isolated synthetic valid/invalid fixtures. Do not create an event topic merely to populate a directory. This covers BP-00 SDK mechanics while deferring business contracts to their own changes.
7. **Quality.** JUnit/Micronaut integration and ArchUnit cover probes, negative surfaces and forbidden dependency fixtures. JaCoCo enforces 75% line and branch coverage for each service's implemented source, with empty layers recorded as not applicable. Vitest/Testing Library with V8 enforces 75% line/branch/function/statement frontend source coverage; Playwright covers built shell navigation, reload, theme and fallback. No exclusion of implemented source to reach thresholds. Tooling tests exercise malformed contracts and dependency violations.
8. **Infrastructure subset.** Compose runs only the portal and three APIs; loopback host bindings, no credentials. Multi-stage Dockerfiles build independently from root context with per-service targets; pinned base digests, non-root runtime and health probes. Kubernetes/GitOps README structure documents future ownership, no deployable production resources. Container and Compose startup must actually be tested where Docker is available; unavailable runtime evidence remains pending.
9. **Delivery and authority.** The owner explicitly authorized automatic specification approval and all future merges until revoked, plus uninterrupted autonomous work. Independent review and green applicable gates precede approval/merge. These session instructions supersede the earlier human-only/no-auto-merge workflow requirements; record the standing authorization in repository guidance. They do not authorize production release or changes to selected architecture/security invariants. Feature PRs continue to target dev.

## Architecture compliance

| Baseline | DKP-001 treatment |
|---|---|
| DM-023/024/057/058, P02/P03/P19/P20 | Domain monorepo, OpenSpec, independent builds, versioned operational contract and SDK gate |
| §5, DM-006 | Enforced inward dependencies; no shared entities or runtime plugin loading |
| DM-034, P08 | Logical database ownership documented; no persistence introduced |
| DM-003/016/020/063, P06/P10 | No resource operations; minimal probes only, negative surface tests, no tenant-header trust |
| DM-022/059, §12.2 | Minimal foundation Compose subset, future Kubernetes/GitOps structure |
| CID-01 | Select Java/Micronaut/PostgreSQL/Neo4j versions with sources; verify foundation compatibility; database adapter compatibility deferred explicitly |
| §11 | Minimal accessible themed shell; complete portal journeys remain later work |
| Other processing/security/reliability DM choices | Preserved, unimplemented, no production acceptance claimed |

## Verification and acceptance matrix

All commands below are the implementation interface to supply, not claims of execution. Run from repository root. Each service substitutes its own slug for `<service>`.

| Criterion | Required evidence |
|---|---|
| C1 Java foundation and independent lifecycle | `./gradlew :services:<service>:bootstrap:build` for each service; runnable artifacts; Java 25 output |
| C2 Dependency direction and domain isolation | `./gradlew architectureTest`; valid graph passes, forbidden import/dependency fixtures fail |
| C3 Operational behavior and security surface | Per-service integration tests assert exact 200/503 bodies and non-success sensitive/business routes with forged tenant headers |
| C4 Portal shell | `pnpm --filter admin-portal lint`, `typecheck`, `build`, `test:coverage`; component assertions for all routes/theme/keyboard |
| C5 Browser behavior | `pnpm --filter admin-portal test:e2e` against production build; navigation/reload/theme/fallback |
| C6 Contract quality and SDKs | `pnpm contracts:check`; valid OpenAPI/AsyncAPI fixtures pass, malformed fixtures fail; Java/TS generated output compiles and regeneration has no drift |
| C7 Coverage | JaCoCo service-level reports and enforced 75% line/branch thresholds; frontend V8 thresholds and reports |
| C8 Reproducibility and CI | Frozen pnpm install, Gradle locks/wrapper verification; all PR build/test/lint/type/architecture/contract/security checks green |
| C9 Independent local runtime | Each image builds independently; `docker compose -f infra/docker-compose/compose.yaml up --build --wait`; HTTP probes and portal deep links verified, non-root processes inspected |
| C10 Configuration/docs | Reviewed environment docs, no secrets/permissive profiles, Kubernetes/GitOps guidance only; dependency source/compatibility ledger |
| C11 Delivery | OpenSpec strict validation; independent spec and implementation review; PR to dev, automatic merge only after green checks and review |

## Risks / Trade-offs

- [Java 25/tool compatibility] → verify maintained versions and a real clean build before locking; never silently downgrade Java.
- [Empty layers make architecture tests vacuous] → graph validation plus explicit negative fixtures, no fake domain behavior.
- [Probe configuration leaks details] → exact-body assertions and sensitive-management-path tests on all services.
- [Minimal Compose is mistaken for full platform] → label scope, enumerate missing infrastructure and retain production readiness gates.
- [Tool supply chain] → pin versions/digests/locks, wrapper integrity and CI action SHAs; scan dependencies and tracked secrets.
- [Docker/remote checks unavailable] → preserve pending status and exact error, continue independent work; never claim runtime or CI success from static validation.

## Migration Plan

No data migration. Add the foundation on the isolated branch; approve the reviewed spec under owner authorization, implement tasks, publish PR evidence, review and merge into dev once checks pass. Rollback is reverting the foundation commit(s); no production deployment is involved.

## Open Questions

No business architecture question blocks this scoped design. Exact tool/library versions are an implementation selection task with official-source and compatibility gates before production code. Database adapters, standalone OIDC, workload identity, complete Compose and production ownership are later OpenSpec changes, not implicitly accepted implementations.
