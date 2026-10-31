# DKP-001 — Monorepo Foundation

Status: **Approved for implementation on 2026-10-31 after independent review and strict validation**. Production approval is outside this change.

## Why

The repository has public architecture and placeholder directories but no executable engineering foundation. Independent, reproducible builds and enforceable boundaries are needed before implementing any document or knowledge business behavior.

## What Changes

- Establish a Gradle multi-project Java 25/Micronaut scaffold for Document Management, Knowledge Base and Knowledge Search, with independent build/test/container entry points and architecture enforcement.
- Establish a pnpm React/Vite/strict TypeScript portal shell with lint, formatting, Vitest/Testing Library and Playwright.
- Add non-sensitive liveness/readiness contracts and fail-closed configuration, without business APIs or persistence.
- Establish dependency pinning, source coverage gates, OpenSpec workflow, contract validation/generation and GitHub Actions.
- Add minimal local Compose and Kubernetes/GitOps structure; preserve the full selected platform stack for later changes.

## Capabilities

### New Capabilities

- `service-foundation`: Independently executable domain services with enforceable inward dependencies and safe operational probes.
- `portal-shell`: Accessible, navigable administration shell without simulated business operations.
- `engineering-gates`: Reproducible quality, contract, dependency and specification gates.
- `local-runtime`: Independently containerized components and minimal local startup with explicit environment boundaries.

### Modified Capabilities

None. There are no existing versioned specifications.

## Impact

Future implementation affects root Gradle/pnpm configuration, `services/{document-management,knowledge-base,knowledge-search}`, `apps/admin-portal`, `contracts`, `.github/workflows`, `infra` and development documentation. No existing business API, event or database changes. Selected architecture remains Java 25, Micronaut, logical PostgreSQL ownership, Neo4j Community, Temporal, Kafka, S3, Docling and controlled plugins; unused infrastructure and plugin execution are outside DKP-001.

The [design](design.md) records scope reconciliations and pre-implementation decisions. The [tasks](tasks.md) track executed evidence. The owner's standing authorization permits automatic approval after independent review and strict validation; record the exact reviewed revision and decision in the delivery record. Artifact completion alone grants no approval and no production acceptance.
