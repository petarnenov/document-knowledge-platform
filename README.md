# Document Knowledge Platform

An open, independently deployable reference project for secure document lifecycle management, document intelligence, knowledge graphs, and evidence-grounded hybrid search.

> **Status: engineering foundation.** Three Micronaut service scaffolds, a React portal shell and automated quality gates are implemented. Document/knowledge business behavior and production deployment are not implemented. No employer-owned documents, internal URLs, customer data, tokens, or proprietary specifications belong here.

## Scope

- Document management: uploads, versions, ACLs, reviews, publication, retention, and deletion.
- Knowledge collections: membership, audiences, and controlled sharing.
- Processing: secured parsing, structured chunking, self-hosted embeddings, evidence-validated graph extraction, indexing, and previews.
- Search: Neo4j-backed semantic, lexical, and graph retrieval with final authoritative authorization.
- Administration: a React/TypeScript portal.

## Planned stack

Java 25 LTS, Micronaut, PostgreSQL, S3-compatible storage, Neo4j Community, Temporal, Kafka, Docling, Ollama, Qwen embedding/extraction models, React, Vite, TypeScript, OpenSpec, Docker Compose, Kubernetes, and GitOps. Implemented foundation dependencies are pinned and tested; later components require their own compatibility validation and immutable version selections.

## Architecture

Each independently deployable service owns its data and has hexagonal domain/application/port/adapter boundaries. Plugins are versioned and deployment-controlled; there is no arbitrary hot loading. PostgreSQL owns document and policy state, S3 stores immutable artifacts, and Neo4j holds rebuildable search indexes. Production privileges must be deny-by-default and tenant-scoped.

See [public architecture overview](docs/architecture/overview.md). This public summary is not a substitute for an approved, implementation-ready system design.

## Planned component boundaries

| Directory | Capability |
|---|---|
| `apps/admin-portal` | React administration UI |
| `services/document-management` | Document/version/publication/ACL API |
| `services/knowledge-base` | Collections, audiences, membership API |
| `services/knowledge-search` | Hybrid search and evidence API |
| `workers/*` | Parsing, chunking, embedding, extraction, indexing, preview |
| `contracts` | OpenAPI, AsyncAPI, workflow and plugin contracts |
| `infra` | Local and Kubernetes deployment templates |
| `openspec` | Spec-driven design changes |

## Development approach

1. Approve a neutral public OpenSpec change.
2. Write versioned contracts and negative security tests before implementation.
3. Implement one service/worker boundary at a time.
4. Require ≥75% automated source coverage, integration tests, and E2E happy paths.
5. Verify tenant isolation, citation provenance, indexing freshness, and backup/restore before deployment.

## Build and run the foundation

Install Temurin Java **25.0.5+8**, Node **24.22.1** and pnpm **12.16.1**. The checked-in Gradle wrapper downloads and verifies Gradle **9.8.2**; no global Gradle is required. See [version sources and policy](docs/development/versions.md) and [environment boundaries](docs/development/configuration.md).

```bash
pnpm install --frozen-lockfile
./gradlew :services:document-management:bootstrap:build
./gradlew :services:knowledge-base:bootstrap:build
./gradlew :services:knowledge-search:bootstrap:build
./gradlew architectureTest
pnpm --filter admin-portal lint
pnpm --filter admin-portal typecheck
pnpm --filter admin-portal build
pnpm --filter admin-portal test:coverage
pnpm --filter admin-portal exec playwright install chromium
pnpm --filter admin-portal test:e2e
pnpm contracts:check
pnpm dependencies:test
pnpm openspec:check
pnpm format:check
```

Run one API with `./gradlew :services:document-management:bootstrap:run` (port 8080), or run the UI with `pnpm --filter admin-portal dev` (port 5173). Each API exposes only minimal `GET /health/liveness` and `GET /health/readiness`; readiness measures the scaffold process, not future database/index readiness. The shell provides navigation and theme selection. No business operations or credentials are configured.

With Docker Compose available:

```bash
docker compose -f infra/docker-compose/compose.yaml up --build --wait --wait-timeout 180
python3 tools/runtime/smoke.py
docker compose -f infra/docker-compose/compose.yaml down
```

Loopback ports: portal **18080**, Document Management **18081**, Knowledge Base **18082**, Knowledge Search **18083**. Each image builds independently from the repository root; Compose starts only these four components. Full processing infrastructure and Kubernetes/GitOps deployments require later specifications. The [DKP-001 delivery record](docs/implementation/dkp-001-status.md) tracks scope, approval, review and evidence.

## Security

Do not publish real document contents, employer/client data, production configuration, private design artifacts, or API credentials. Never commit `.env` files. Security-sensitive architectural decisions require review.

## License

No license has been granted yet. The repository is publicly readable, but its contents are **not** automatically licensed for reuse or redistribution. Add a license only after the owner selects one.
