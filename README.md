# Document Knowledge Platform

An open, independently deployable reference project for secure document lifecycle management, document intelligence, knowledge graphs, and evidence-grounded hybrid search.

> **Status: architecture starter / pre-implementation.** This is a public, neutral repository scaffold, not a production-ready implementation or a published corporate design. No employer-owned documents, internal URLs, customer data, tokens, or proprietary specifications belong here.

## Scope

- Document management: uploads, versions, ACLs, reviews, publication, retention, and deletion.
- Knowledge collections: membership, audiences, and controlled sharing.
- Processing: secured parsing, structured chunking, self-hosted embeddings, evidence-validated graph extraction, indexing, and previews.
- Search: Neo4j-backed semantic, lexical, and graph retrieval with final authoritative authorization.
- Administration: a React/TypeScript portal.

## Planned stack

Java 25 LTS, Micronaut, PostgreSQL, S3-compatible storage, Neo4j Community, Temporal, Kafka, Docling, Ollama, Qwen embedding/extraction models, React, Vite, TypeScript, OpenSpec, Docker Compose, Kubernetes, and GitOps. All runtime and dependency versions will be pinned after compatibility validation.

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

## Security

Do not publish real document contents, employer/client data, production configuration, private design artifacts, or API credentials. Never commit `.env` files. Security-sensitive architectural decisions require review.

## License

No license has been granted yet. The repository is publicly readable, but its contents are **not** automatically licensed for reuse or redistribution. Add a license only after the owner selects one.
