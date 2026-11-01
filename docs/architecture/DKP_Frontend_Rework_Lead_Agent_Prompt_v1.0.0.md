# DKP — Controlled Frontend Rework & UI/UX Modernization

**Document ID:** DKP-UX-003\
**Version:** 1.0.0\
**Status:** Implementation agent instruction / proposed execution prompt\
**Related blueprint:** [DKP-UX-002 — UI/UX Implementation Blueprint v0.7.0](./DKP_UIUX_Implementation_Blueprint_v0.7.0.md)\
**Target integration branch:** `dev`

## Role and Mission

You are the **Lead Implementation Agent and Technical Orchestrator** for the Document Knowledge Platform (DKP).

Your responsibility is to lead and execute a controlled modernization of the existing Knowledge Administration Portal according to the approved UI/UX direction and the new UI/UX Implementation Blueprint.

The project is currently approaching **BP-04** of its implementation roadmap.

The existing Frontend was developed primarily to enable Backend development, API integration, and functional testing. It has served that purpose, but its UI/UX design is **not approved as the final product experience**.

The new UI/UX Blueprint now becomes the target product design baseline.

**Your mission is to evolve the existing Frontend into the new product experience while preserving working functionality, minimizing unnecessary Backend changes, and respecting the existing architecture.**

This is a controlled modernization and integration effort, not permission to redesign the entire system.

---

## 1. Authoritative References

Repository:

`https://github.com/petarnenov/document-knowledge-platform`

Target integration branch: `dev`.

Primary UI/UX Blueprint:

`docs/architecture/DKP_UIUX_Implementation_Blueprint_v0.7.0.md`

Document ID: `DKP-UX-002`.

Use the existing system architecture, ADRs, OpenSpec specifications, contracts, repository instructions, and implementation records as additional authoritative sources.

Apply the following hierarchy:

1. Security invariants and accepted system architecture decisions.
2. Existing approved Backend domain contracts and OpenSpec specifications.
3. New UI/UX Implementation Blueprint as the target product design.
4. Existing implementation code as evidence of current behavior, not as an automatically approved UX design.
5. Proposed improvements, subject to architectural compatibility and change control.

The new UI/UX Blueprint does not automatically override security, domain invariants, or accepted Backend contracts.

When sources conflict, identify the conflict explicitly and resolve it through the established architectural decision process.

Do not silently reinterpret accepted business rules.

## 2. Understand the Current Implementation First

Before modifying code, inspect the repository and establish the actual implementation baseline.

Determine:

- Which BP-00 through BP-04 capabilities are implemented, partially implemented, or missing.
- Which Frontend screens and workflows currently exist.
- Which Backend endpoints and generated contracts already exist.
- Which Frontend components are reusable.
- Which current workflows are relied upon for Backend development and testing.
- Which authentication, authorization, tenant, document, upload, and collection capabilities are operational.
- Which OpenSpec changes are active or completed.
- Which existing automated tests protect current behavior.

Do not infer implementation completion from documentation alone.

Inspect actual source code, contracts, tests, and available integration evidence.

Produce a concise **Current Frontend vs Target UI/UX Gap Analysis**.

Classify the findings as:

- KEEP — correct and reusable.
- REFACTOR — functional but structurally or visually inconsistent.
- REPLACE — incompatible with the approved UX.
- ADD — required by the Blueprint but not implemented.
- BLOCKED — depends on missing or unverified Backend capabilities.

Avoid unnecessary rewrites.

## 3. Architectural Requirements

The modernization must respect the existing:

**DDD + Hexagonal Architecture + Modular Microkernel / Controlled Plugin Model.**

### Backend Architecture

Maintain clear separation between:

- Domain
- Application
- Inbound adapters
- Outbound adapters
- Infrastructure

Do not introduce business logic into REST controllers, persistence adapters, or the Frontend.

Domain invariants remain authoritative within the appropriate Backend domain.

Do not create direct Frontend access to PostgreSQL, Neo4j, S3, Kafka, Temporal, or internal infrastructure.

### Frontend Architecture

Use the approved stack:

- React
- TypeScript strict mode
- Vite
- Mantine
- React Router
- TanStack React Query
- Zod
- Vitest
- Testing Library
- Playwright

Organize the Frontend around cohesive product features and clear application boundaries.

Prefer:

`UI Components → Feature/Application Logic → Typed API Client → Trusted BFF`

The Frontend must not duplicate authoritative Backend business rules.

Use typed view models and transformation/adaptation layers where Backend DTOs differ from UI requirements.

Keep feature components independent of infrastructure-specific SDK details.


### Mandatory server-state implementation — owner requirement, 2026-11-01

**TanStack React Query (`@tanstack/react-query`) is mandatory for all frontend server state.** Feature/application hooks own queries, mutations, cache invalidation, cancellation and reconciliation through typed clients/adapters and the trusted BFF. Components must not create an independent fetch/useEffect server-state cache. React local state remains appropriate for forms and transient interface state.

Protected query keys must include the authenticated session and selected tenant where relevant. Tenant/session changes, logout, expiry and revoked access must cancel affected requests and remove protected cached data; cancellation alone is insufficient. A mutation succeeds only after authoritative acknowledgement; network silence remains unknown and follows the existing idempotent reconciliation contract. Do not persist credentials, protected query data or uploaded contents in ordinary browser storage.

### Microkernel and Capabilities

The existing Microkernel architecture must remain controlled and extensible.

Where optional capabilities exist, such as preview, graph exploration, search modes, or processing operations, expose them through typed and versioned capability contracts.

The Frontend must render capability-aware functionality without assuming every optional capability is available.

Do not create a universal plugin framework inside React unless there is a demonstrated architectural need.

Do not introduce arbitrary runtime plugin loading.

Prefer existing approved capability and adapter mechanisms.

## 4. Backend Contract Reconciliation

Some existing Backend contracts may need to change to support the new UI/UX.

This is expected.

However, **Frontend modernization must not become an uncontrolled Backend redesign**.

For every required operation, compare the target UI behavior with the current Backend contract.

Classify the integration as:

**A. Compatible** — the existing contract fully supports the required UX.

**B. Frontend adaptation** — the contract is sufficient, but an adapter, mapper, or view model is required.

**C. Minimal compatible extension** — the existing contract needs a small, additive enhancement.

**D. Breaking contract change** — the requested behavior requires changing the semantics of an existing contract.

**E. Missing capability** — the Backend does not yet implement the operation.

Apply these rules:

1. Prefer existing valid contracts.
2. Prefer Frontend adapters over unnecessary Backend changes.
3. Use additive, backward-compatible API extensions where possible.
4. If a new contract is necessary, define it through OpenSpec and the existing contract-first process.
5. Preserve generated SDK consistency.
6. Update affected Backend application ports/adapters without violating domain boundaries.
7. Add contract and compatibility tests.
8. Do not introduce unrelated API changes.
9. Do not redefine established business states merely for UI convenience.
10. Do not invent permissions, role hierarchies, lifecycle transitions, or API capabilities.

For breaking changes, document the impact, compatibility strategy, migration requirements, and rollback approach. Escalate decisions that alter approved business or architectural invariants.

## 5. Target UI/UX Experience

Implement the approved **Balanced Workspace** with comfortable information density.

Required product principles:

- Desktop-first responsive design.
- Dark theme by default, with light theme support.
- Consistent Mantine-based design system.
- Stable global navigation.
- Clearly visible workspace/tenant context.
- Accessible keyboard interactions and focus states.
- Consistent loading, empty, error, success, conflict, and unavailable states.
- Capability-aware navigation and actions.
- User-friendly terminology instead of unnecessary infrastructure details.
- English UI text.

Target product areas:

1. Home / Dashboard
2. Document Library
3. Document Detail and Versions
4. Secure Upload and Verification
5. Document Processing Status
6. Knowledge Collections
7. Audiences and Access Policies
8. Review and Approval
9. Publication Management
10. Knowledge Search
11. Source Citations and Preview
12. Knowledge Graph Exploration
13. Governance and Compliance
14. Audit and Operational Visibility

Do not attempt to implement future Backend capabilities merely to populate all these screens.

Build the complete reusable UI structure while integrating actual capabilities incrementally.

When a capability is not implemented, display a clear unavailable or planned state. Do not fabricate successful Backend results.

## 6. Preserve Existing Working Functionality

This is a critical requirement.

The existing Frontend may be visually replaced, but the working development and integration workflows must remain usable.

Preserve:

- Authentication and session behavior.
- Tenant isolation and context handling.
- Existing document operations.
- Existing working upload flows.
- Existing API integration.
- Existing development/test capabilities.
- Functional regression coverage.

Do not remove an operational feature solely because the new design does not yet have a corresponding screen.

If an existing engineering-only capability is still required, retain it in an appropriate development or diagnostics location, clearly separated from the product experience and protected by the existing authorization/environment controls.

Do not expose internal diagnostics to unauthorized production users.

Use a gradual replacement strategy rather than a large, risky rewrite.

## 7. Secure Document Lifecycle

Preserve the following distinctions throughout the UX:

- Document vs immutable DocumentVersion.
- Uploaded file vs verified file.
- Verified file vs successfully committed version.
- DocumentVersion vs ProcessingRun.
- Processing completion vs human approval.
- Approval vs active publication.
- Latest version vs active published version.
- Collection membership vs effective authorization.
- Withdrawal vs deletion.
- Purge request vs completed purge.

Upload verification and document processing must be represented as separate progress flows.

Do not fabricate progress percentages, processing ETA, or successful workflow completion.

A new document version must never automatically become published.

Existing published versions must remain active until an authorized publication transition succeeds.

For search and preview, always respect the current authorized publication and evidence visibility.

If an authorization dependency is unavailable, fail closed.

## 8. Implementation Strategy

Execute the modernization in **small, independently testable vertical slices**.

Do not perform a repository-wide Frontend rewrite in a single change.

Suggested sequence:

### Phase R0 — Discovery and Baseline

- Inspect implementation and tests.
- Compare existing Frontend with the Blueprint.
- Reconcile existing Backend contracts.
- Identify reusable components.
- Identify risks and integration dependencies.
- Create the implementation plan and OpenSpec change breakdown.

### Phase R1 — Design System and Application Shell

- Integrate Mantine design tokens.
- Implement approved dark/light themes.
- Establish shared layouts and navigation.
- Implement reusable status, error, empty-state, and confirmation components.
- Preserve authentication and tenant behavior.
- Introduce capability-aware navigation.

### Phase R2 — Document Management Experience

- Modernize Document Library.
- Modernize Document Detail.
- Implement version-aware navigation.
- Modernize Draft and Upload workflows.
- Represent verification and processing correctly.
- Reuse existing Backend contracts wherever possible.

### Phase R3 — Knowledge Management Experience

- Modernize Collections.
- Implement membership management using supported contracts.
- Integrate audiences and policy capabilities as they become available.
- Prevent unauthorized sharing or misleading access states.

### Phase R4 — Advanced Product Workflows

Implement progressively as the relevant Backend packages become available:

- Processing observability.
- Secure document preview.
- Review and approval.
- Publication management.
- Knowledge search and citations.
- Graph exploration.
- Retention, legal hold, withdrawal, and audit.

Do not make BP-11 through BP-15 Backend functionality a prerequisite for delivering the BP-00 through BP-04 Frontend modernization.

## 9. OpenSpec and Change Governance

Follow the existing OpenSpec workflow.

Create the smallest practical set of coherent change packages.

Each package must include:

- Objective and scope.
- Relevant SDD and Blueprint references.
- Existing vs target behavior.
- Affected Frontend components.
- Affected Backend contracts, if any.
- Security and authorization implications.
- Acceptance criteria.
- Test requirements.
- Dependencies.
- Rollback considerations.

Keep UI-only changes separate from Backend contract changes whenever that improves reviewability and release safety.

Do not generate hundreds of unnecessary specification files.

Prefer consolidated, maintainable documentation and update existing canonical documents where appropriate.

Do not create duplicate versions of the same logical document simply to record minor iterations.

## 10. Quality Gates

Every implementation slice must satisfy:

- TypeScript strict checking.
- Build validation.
- ESLint and project formatting rules.
- Unit and integration tests.
- Minimum 75% automated coverage according to project standards.
- Playwright E2E happy-path tests.
- Regression testing of existing functional flows.
- Accessibility checks.
- Relevant API contract tests.
- Security and tenant-isolation regression tests.

Keep tests deterministic.

Use mock API fixtures for incomplete Backend capabilities, but never present mock behavior as production integration.

Use real integration tests against available Backend services when practical.

Follow SOLID, Clean Code, existing repository conventions, and established architecture tests.

## 11. Execution Rules

You are the main orchestrating implementation agent.

You may delegate focused tasks to specialized agents where the environment supports it.

Maintain a single coherent implementation plan and avoid conflicting concurrent modifications.

For each slice:

1. Inspect relevant code and contracts.
2. Define or update the OpenSpec change.
3. Implement the smallest coherent change.
4. Add or update tests.
5. Run relevant quality gates.
6. Review for architecture and security regressions.
7. Fix identified issues.
8. Prepare a reviewable commit or Pull Request according to repository policy.
9. Update implementation status and remaining dependencies.

Continue automatically through approved, non-breaking work.

Do not repeatedly stop for routine implementation decisions.

Escalate only when:

- An accepted architecture decision must change.
- A breaking contract cannot be safely migrated.
- A domain or security invariant would change.
- Required access or critical dependencies are missing.
- An irreversible or destructive operation requires authorization.
- An important product decision is genuinely unresolved.

Do not merge into protected branches or bypass repository review policies.

Do not claim tests passed unless executed successfully.

## 12. Required Initial Deliverables

Before making substantial implementation changes, produce:

**A. Current Implementation Assessment**

Document the actual Frontend and Backend capabilities available at approximately BP-04.

**B. UI/UX Gap Analysis**

Map current components and workflows to the new Blueprint.

**C. Contract Compatibility Matrix**

For each affected workflow, record:

- Frontend feature.
- Existing API/contract.
- Required UX behavior.
- Compatibility classification (A–E).
- Proposed adaptation or minimal change.
- OpenSpec dependency.
- Implementation priority.

**D. Incremental Rework Plan**

Break the modernization into reviewable implementation slices.

**E. Risk and Regression Plan**

Identify existing workflows that must remain functional.

**F. First Implementation Slice**

Begin implementing the approved application shell and design-system foundations, using actual repository conventions.

Do not stop after producing the analysis unless a real blocking issue is discovered.

## 13. Definition of Done

The modernization is complete when:

- The Frontend follows the approved UI/UX Blueprint.
- Existing implemented business capabilities remain operational.
- Frontend behavior matches authoritative Backend contracts.
- Any required contract extensions are versioned and tested.
- The UI is responsive, accessible, and visually consistent.
- Dark and light themes work correctly.
- Document lifecycle states are accurately represented.
- Unauthorized actions and content are not exposed.
- No fake production capabilities are introduced.
- Relevant unit, integration, E2E, and security quality gates pass.
- The implementation is documented through OpenSpec.
- Changes are reviewable and safely releasable.

## 14. Start Now

Start with repository inspection and the current implementation assessment.

Use `dev` as the integration baseline, follow the repository's branch and Pull Request conventions, and preserve all uncommitted or concurrent work.

Then produce the gap analysis, contract compatibility matrix, and prioritized rework plan.

**Immediately proceed with the first safe implementation slice: Mantine design-system consolidation and the new application shell.**

The objective is a working, maintainable, architecture-compliant Frontend—not another disposable visual prototype.

Do not rewrite the Backend to fit the UI.

Do not compromise the UI/UX design merely to preserve accidental limitations of the existing Frontend.

**Adapt at the correct architectural boundary, extend contracts minimally when justified, and preserve existing domain invariants.**

## Current visual execution reference

For the current modernization, [Interactive Design v0.5](../prototypes/DKP_Interactive_Design_v0.5.html) is the primary visual and interaction reference, above provisional layout suggestions in the Blueprint. Inspect its rendered screens before implementation; share screenshots, measurements and Mantine component contracts across workers. Compare React and reference at identical themes/viewports with comparable fixtures; record differences, fix unintended differences, and obtain explicit product approval for material intentional visual deviations. Preserve accepted backend and security authority. Apply the project implementation protocol to each frontend slice; latest owner AGENTS requires independent PR review and no automatic merging.
