# Verbatim public acceptance and scope

Source: docs/architecture/system-design.md at base62a73f0. These excerpts remain normative; package sequencing below does not delete later acceptance.

## Ingest and workflow — source lines336–359

## 8. Durable ingest, processing, artifacts and event flow

### 8.1 Upload lifecycle

1. Authenticated permitted staff requests upload initialization with format and size metadata; enforce file limits **100 MB**, PDF **1000 pages** (after inspection), max 5 concurrent uploads per tenant and resource quotas.
2. Create tenant-scoped **quarantine** upload slot/token; complete upload and verify real file signature, SHA-256, structural constraints, OCR limits and malware scan. Nonconforming input rejected/quarantined with audit; no downstream parsing.
3. Atomically save DocumentVersion, ProcessingRun, ProcessingIntent and outbox record in DM DB; return `202 Accepted` with `ACCEPTED_PENDING_DISPATCH`, not misleading `PROCESSING` status.
4. Dispatcher claims intents, starts Temporal with deterministic workflow ID; reconciles timeout/AlreadyStarted responses, restarts after crash without duplicate run. Exactly-once external side effects are **not** assumed.
5. Parse via Docling (isolated service); persist structured artifact plus source maps; chunk with Docling HybridChunker and table/list-aware adapters, initial 512 target/768 max tokens, verified Qwen tokenizer.
6. Generate embeddings with pinned Qwen3-Embedding-0.6B, cosine profile, 1024 dimensions. In parallel, hybrid deterministic+Qwen3-4B/Ollama extraction generates graph assertions that must pass ontology/entity/evidence validation.
7. Index `Chunk` text and vectors in Neo4j as **staged**, with validated graph assertions as optional enrichment; create index manifest with checksums, embedding profile, ontology and source IDs.
8. Generate versioned preview using safe renderer. Human reviewer inspects generated preview or policy-approved original and approves exact version.
9. Validate membership revisions and activate publication pointer; emit outbox Kafka events. Queries enforce current version, ACL and KB membership.
10. Reconciliation detects missing artifacts, failed index stage, stale Neo4j state and stalled processing intents.

### 8.1.1 Package sequencing clarification — DKP-004 development specification

Accepted by independent specification reviews #89/#90 and recorded DKP-004 development approval: BP-03 upload completion issues a durable verified-artifact receipt but creates no DocumentVersion. The existing explicit append command consumes that receipt atomically with version creation and command recording, returning only `{documentId,revision}` with `201`. Neither outcome claims processing acceptance or changes the active publication pointer.

The final step3 requirement above remains mandatory. BP-05 owns atomic DocumentVersion/ProcessingRun/ProcessingIntent/outbox acceptance and `202 ACCEPTED_PENDING_DISPATCH`. Its specification must explicitly reconcile existing append API compatibility and versions created before processing exists. Returning202 without all four records, silently skipping existing versions or dropping the final atomic-ingest requirement is forbidden. This development sequencing grants no architecture/security-control waiver or production acceptance.

### 8.2 Temporal workflow responsibilities

Workflow executes deterministic orchestration only; non-deterministic I/O belongs to activities. Version activity contracts; pin or version long-running workflow code to support rolling upgrades. Configure bounded retries with backoff/jitter, per-activity heartbeat, deadlines and cancellation. Define concrete terminal statuses and user-visible error classes. Use separate task queues for parsing/OCR, chunking, embedding, graph extraction, indexing, preview; configure tenant fairness and concurrency separately. Document-level timeout initial **30 minutes**; special exceptions require approved configuration. Maximum active processing workflows: **50** globally baseline, not 50 simultaneous heavy OCR calls.

## Acceptance gates — source lines541–564


| Gate ID | Test class | Normative scenario |
|---|---|---|
| SEC-01 | Tenant isolation | A cannot retrieve B's documents, assertions, snippets, counts, node existence or paths |
| SEC-02 | Revocation | ACL revoked during in-flight query excluded if revoked before response-boundary check |
| SEC-03 | Publication | Version stage ready, human-approved, audience revision current, single active pointer |
| SEC-04 | Membership races | Concurrent KB change cannot expand effective audience; stale revision detected |
| SEC-05 | Content | Direct S3/signed URL forbidden for strict revocation; gateway access rechecked |
| SEC-06 | Malware | Parser never receives unscanned/failed-scan file; bomb and macro tests |
| SEC-07 | AI extraction | Invalid relations/prompt injection do not bypass ontology/evidence gates |
| WF-01 | Dispatcher | Crash after DB commit or Temporal start, repeated dispatch does not create duplicate run |
| WF-02 | Checkpoints | Parser/embedding/graph failure reuses compatible immutable artifacts |
| WF-03 | Cancellation | Explicit cancellation/timeout returns safe terminal or compensating state |
| DAT-01 | Purge | Retention and legal hold enforced; cross-store invalidation reconciled |
| DAT-02 | Reindex | Reconstruct Neo4j from pinned artifacts and profiles; preserve references |
| SCH-01 | API compatibility | OpenAPI/AsyncAPI/Java/TS generated clients respect semantic version changes |
| PERF-01 | Search | 50 rps, P95<=3s P99<=6s incl. ACL, representative 100k-doc corpus |
| PERF-02 | Search quality | Bounded recall measured across tenant sizes and allowed scopes, no invented threshold |
| REL-01 | DR | Tiered RTO/RPO demonstrated in recoverability tests |
| REL-02 | Infrastructure faults | Neo4j, Kafka, Temporal, OIDC and object-store outage modes |
| OPS-01 | Supply chain | Signed/pinned images, SBOM, dependency scans and audit trails |

**Test data:** synthetic and explicitly approved permitted corpora, multi-tenant adversarial fixtures, scanned PDFs, DOCX tables, PPTX slides, XLSX multi-sheet data, unsupported formats, broken refs, conflicting policies, large documents, missing model revisions and overlapping revisions. No production user data in public test repositories.


## Blueprint scope — source lines568–580


| Package | Deliverable | Dependencies / acceptance |
|---|---|---|
| BP-00 | Monorepo, Gradle/pnpm, CI, OpenSpec, code quality, SDK generation | Build and CI gates green |
| BP-01 | Auth, trusted BFF, workload identity abstractions | Standalone OIDC default deny; integration stubs nonpermissive |
| BP-02 | DM aggregates, versioning, ACL, retention, migrations | Immutable versions and CAS transaction tests |
| BP-03 | S3 port, quarantine/upload/scan and safe content access | Malware/tenant scope tests, <=100MB controls |
| BP-04 | KB collections, memberships, audience revision contracts | Cross-domain access + concurrent policy tests |
| BP-05 | Durable intent, dispatcher, Temporal skeleton and Kafka outbox | Crash/replay/idempotency tests |
| BP-06 | Parse/Docling and artifact schemas | PDF/DOCX/PPTX/XLSX golden fixtures |
| BP-07 | HybridChunker + source map | Table/header/cell citation accuracy |
| BP-08 | Qwen embedding profile/adapter and versioned vector artifacts | Pin runtime/revision and vector dimension verification |
| BP-09 | Ollama extraction, ontology, entity resolution, assertion validation | Invalid relation never accepted; audit provenance |

## Controlled decisions — source lines601–625


## 15. Controlled implementation decision register

These are not unresolved *business architecture* decisions, but must be resolved and evidenced before their specific implementation/production gates. No implicit “latest” is allowed.

| ID | Open implementation selection / validation | Owner role | Blocking milestone |
|---|---|---|---|
| CID-01 | Micronaut compatible stable release; Java 25 patch; PostgreSQL supported release and Neo4j Community compatible build | Backend/Platform | Build foundation |
| CID-02 | Immutable Qwen embedding and graph model revisions, tokenizer/quantization; self-hosted embedding server | AI Processing | Embedding/graph indexing |
| CID-03 | Exact Docling Serve + LibreOffice build, image digest, OCR quality and license validation | Processing/Platform | Parser/preview rollout |
| CID-04 | S3 implementation (self-hosted or approved cloud), versioning, encryption and legal-hold capability | Platform/Security | Artifact production |
| CID-05 | Temporal/Kafka chart/config, stateful backup/recovery and retained events | Platform | Staging reliability |
| CID-06 | Exact OIDC trust config, SPIFFE/SPIRE mTLS integration and corporate authorization interface | Security/Platform | Integrated staging |
| CID-07 | Formal content classification levels, role/action matrix, KB policy revision token semantics, concurrency tests | Domain/Security | Publication API release |
| CID-08 | Quality-aware parser limits and artifact schemas; exact office/PDF source locator fidelity | Processing | Content acceptance |
| CID-09 | Reranker implementation and measurable search profile/retrieval recall at 50 rps | Search | Search SLO approval |
| CID-10 | AI relation risk ontology validation coverage and quality review reporting; no implicit acceptance threshold | AI/Search | Graph rollout |
| CID-11 | Approved US region/provider residency and centralized storage/backup/audit policy | Security/Platform | Production deployment |
| CID-12 | Named operations/security owners, ticket/escalation routes, evidence-based DR/performance tests | System owner | Production sign-off |
| CID-13 | Storage/retrieval scaling, Neo4j Community offline backup window and licensing boundary assessment | Platform/Search | Production sign-off |
| CID-14 | Versioned OpenAPI/AsyncAPI and generated SDKs, request/response schemas, error conventions | Domain/Frontend | Cross-component integration |
| CID-15 | Audit retention, secret/key rotation interval and deletion policy specifics | Security/Compliance | Compliance approval |

**Change control:** If a CID changes an approved DM invariant or external principle, create a new local ADR, document alternatives and request explicit review. Do not silently promote a CID into an accepted design decision.


## BP05 ownership and later boundaries
BP05 delivers durable acceptance, direct Temporal dispatch/skeleton, separate Kafka relay, restart/replay/fault/cancellation evidence and a real authorized workflow checkpoint. BP06–BP13 retain real parsing/OCR/chunk/embed/graph/index/preview/review/publication and compatible immutable artifact reuse. A skeleton waiting for absent approved capabilities is not parsed, ready, approved, indexed or published. WF02 is tested now for compatible original/checkpoint reuse and incompatible evidence rejection, then re-executed against each real downstream artifact when that package exists. DAT01 prohibits deletion under hold now; cross-store purge completion remains BP13/later, never claimed by skeleton cancellation.
