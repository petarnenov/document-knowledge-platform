# Document Knowledge Platform — UI/UX Implementation Blueprint

**Document ID:** DKP-UX-002\
**Version:** 0.7.0-draft\
**Date:** 2026-11-01\
**Language:** English\
**Status:** Proposed frontend implementation blueprint — requires contract and product acceptance\
**Scope:** Knowledge Administration Portal UI/UX, frontend architecture, accessibility, mocked prototype, integration, and frontend tests. **Not** a replacement for the overall system blueprint.\
**Authority:** `DKPS-SDD-001 v1.0.0-rc1` for system invariants; `DKP-UX-001 v0.6.0-draft` for existing UX proposals; approved UX direction (Balanced Workspace, Comfortable Density, dark default).\
**Change policy:** Do not infer business permission rules or change DM-001–DM-072, upstream ADRs, service ownership, security model, or approved contracts by implementing this document.

---

## 1. Executive summary

Build a **single coherent, accessible, desktop-first React portal**, not a collection of disconnected demo screens. The primary journey is `Select workspace → Create draft → Upload/verify → Track processing → Preview → Organize in collections → Review exact version → Publish → Search → Open cited source → Create next version → Govern retention and legal hold`. Every surface reuses the same document/version/run/publication data, while all actual permission and security decisions remain server-authoritative.

**Release strategy:** (1) a local, clearly labeled synthetic-data prototype with connected workflows; (2) feature-by-feature API integration using generated clients and verified capabilities; (3) end-to-end hardening, accessibility and security verification. None of the local mock actions prove real malware scanning, persistence, authorization, audit immutability or successful deletion.

**Explicit decisions from product design:** React + TypeScript, Mantine, balanced application shell, comfortable density, English copy, dark mode as default with user-selectable light mode, responsive mobile fallback, no AI chat or generated search answers.

**Implementation gates:** Precise capability/action matrix, versioned API DTOs, review rules, policy revisions, retention rules, evidence locator shapes, preview delivery and upload reconciliation have to be confirmed before the affected real features can ship. See §12.

## 2. Source of truth and classification

| Classification | Meaning | Examples |
|---|---|---|
| **SDD invariant** | Normative for UX implementation until formally revised | One active published version; immutable versions; response-boundary authorization; 100,000,000-byte limit; quarantine; separate processing readiness/approval/publication |
| **Approved UX direction** | Product design selection, not a backend contract | Balanced workspace, comfortable density, dark default, connected end-to-end journey |
| **Blueprint proposal** | Suggested frontend structure; reviewable and changeable | Route hierarchy, hooks, component contracts, implementation sequencing |
| **Backend-dependent / pending** | Cannot be asserted by mock UI | Actual roles/permissions, review quorum, classification levels, processing progress/ETA, lifecycle command availability, version diff, bulk actions |

The upstream SDD states that architecture is blueprint-ready but not production-approved. Numeric performance and recovery figures are targets, not test evidence. The prototype's personas are demonstration viewpoints, **not** authenticated backend roles. The sample classification labels `Internal`, `Confidential`, `Restricted` are fixtures until CID-07 confirms the real taxonomy.

## 3. Experience architecture and screen inventory

### 3.1 Global app structure

```text
Knowledge Administration Portal
├── Auth / Workspace selection (OIDC, session, account context)
├── App Shell
│   ├── Header: product, workspace, search entry (if allowed), theme, account
│   ├── Sidebar: Home, Documents, Collections, Reviews, Search,
│   │            Operations, Governance, Audit (capability filtered)
│   ├── Main: heading/breadcrumbs, task content, contextual status
│   └── Context panel (desktop only, contextual—not a second navigation)
├── Document workspaces
│   ├── Library → Draft → Document Detail → Versions → Upload
│   ├── Processing Runs (version + run scoped)
│   └── Preview / Citation-linked source viewer
├── Knowledge workspaces
│   ├── Collections → Membership / Audience Policy
│   └── Search → Results → Graph Evidence → Citation
└── Control workspaces
    ├── Reviews → Exact-version Approval → Publication
    ├── Governance → Retention / Legal Hold / Withdrawal / Purge request
    └── Operations / Audit
```

**Global layout:** left sidebar stays visible on desktop; header establishes tenant context; contextual inspector appears only when useful. Page content width stays comfortable for reading; wide tables may use horizontal overflow with labeled headers. The current workspace remains clearly visible on every page. Permission-filtered navigation is a convenience, **not** the security boundary.

### 3.2 Client route map (proposals, not API endpoints)

| Route | Screen | Primary UI capability | Prerequisite / dependency |
|---|---|---|---|
| `/` | Home | Personal next actions, authorized recents | Session + authorized aggregation |
| `/documents` | Document Library | Filter, page, open records | DM list/read |
| `/documents/new` | New Draft | Enter validated metadata | DM create capability |
| `/documents/:documentId` | Document Detail | Metadata, readiness, publication summary | Exact resource read permission |
| `/documents/:documentId/versions` | Version History | Select immutable version; no presumed compare | Version read |
| `/documents/:documentId/upload` | Upload Wizard | Create upload, transfer, verify, version commit | Upload/init/complete contracts |
| `/documents/:documentId/versions/:versionId/processing` | Processing | Upload/verification + processing separately | Run/status + actions |
| `/documents/:documentId/versions/:versionId/preview` | Preview | PDF, Office rendering, sheet/cell evidence | Secure content/preview gateway |
| `/collections` | Collection Library | Browse, create if allowed | KB capabilities |
| `/collections/:collectionId` | Collection Detail | Membership, audience, revision/status | KB + DM authorization validation |
| `/reviews` | Review Queue | Find work assigned/allowed | Review list contract |
| `/reviews/:documentId/:versionId` | Version Review | Review, approve, publish if eligible | Approval, audience, revision/CAS contracts |
| `/search` | Knowledge Search | Text/semantic/hybrid/graph discovery | Search capability flags |
| `/search/citations/:citationId` | Citation Resolution | Jump to verified source locator | Secure citation resolution |
| `/operations` | Processing Operations | Inspect failures and permitted recovery | Operator capability and run contracts |
| `/governance` | Governance | Locate policy-constrained records | Privileged access |
| `/governance/documents/:documentId` | Governance Detail | ACL, retention, holds, withdrawal, deletion request | Current authoritative policy and commands |
| `/audit` | Audit History | Privileged filtered event view | Audit query contract |
| `/preferences` | Preferences | Theme, interface settings | Local preference / account policy |

Every deep link revalidates session and per-resource permission. Unauthorized resources must not leak existence through previews, breadcrumbs, counts, browser document titles, stale query caches, or differentiated error messages.

### 3.3 Working personas (UX only)

- **Contributor perspective:** create drafts, provide metadata, upload allowed document versions, inspect own authorized runs.
- **Reviewer perspective:** view allowed review queue and approve specific versions where server allows.
- **Knowledge manager perspective:** manage permitted collection memberships and audience settings.
- **Operations perspective:** diagnose processing issues and invoke only server-advertised recovery actions.
- **Compliance perspective:** inspect authorized retention/hold policies, request controlled lifecycle actions and audit evidence.

No persona is equivalent to a backend role. UI actions must be derived from **resource-scoped allowed operations** returned by the service or a verified entitlement API.

## 4. Design system specification

### 4.1 Mantine component mapping

| Experience | Preferred Mantine implementation | Custom component responsibility |
|---|---|---|
| Balanced shell | `AppShell`, `Burger`, `ScrollArea`, `NavLink` | `WorkspaceShell`, `TenantSwitcher`, `CapabilityNav` |
| Typography & spacing | `Title`, `Text`, `Stack`, `Group`, `Grid`, `Container`, `Paper` | `PageHeader`, `DetailField`, `ContextInspector` |
| Forms | `TextInput`, `Textarea`, `Select`, `Checkbox`, `Radio`, `FileInput` | Zod-derived field rules and backend-error mapping |
| Status | `Badge`, `Alert`, `Timeline`, `Stepper`, `Skeleton`, `Loader` | `LifecycleBadge`, `UploadVerificationStepper`, `ProcessingStageGraph` |
| Data views | `Table`, `Pagination`, `Tabs`, `Menu` | `DocumentTable`, `VersionList`, `SearchResultList`, `AuditTimeline` |
| Dialogs | `Modal`, `Drawer`, `Tooltip`, `Notification` | `OperationConfirmDialog`, `ConflictResolutionPanel` |
| Preview | `Paper`, `ScrollArea`, resizable pane wrapper | `SecurePreview`, `SourceHighlight`, `CitationInspector`, `SheetViewer` |
| Search & graph | `SegmentedControl`, `Select`, `Tabs`, `Paper` | `SearchModeSelector`, `EvidenceGraph`, `CitationLink` |

**Rule:** prefer accessible Mantine primitives; only write custom interaction when Mantine lacks needed behavior. Avoid infrastructure jargon in ordinary user-facing screens. Show technical IDs, digests and workflow internals in advanced details.

### 4.2 Tokens and responsive rules (proposals)

- **Density:** comfortable input heights, consistent field spacing, readable paragraph line lengths, 8px-based spacing increments mapped to Mantine theme tokens. Do not hardcode a second conflicting spacing system.
- **Color:** dark default; light selectable. Use semantic variables for background/surface/border/foreground, success/warning/danger/info; never rely on color alone to express state.
- **Type:** clear hierarchy (`PageTitle` > section title > content > caption). Status chip size does not outrank page title.
- **Desktop ≥ ~1200px:** sidebar + content + optional inspector. **Tablet ~768–1199px:** collapsible sidebar, inspector as drawer. **Mobile < ~768px:** header + drawer navigation, vertical controls, responsive tables or record cards, modal alternatives that are keyboard usable.
- Breakpoints above are **UX suggestions** to validate with actual content; not service contracts.
- Theme preference persists locally only if allowed by security/browser policy; tenant and session protected data never persist in ordinary local storage.

### 4.3 Accessibility contract

- Keyboard-first task completion; sensible focus order and visible focus indicators across themes.
- Every form field has an accessible label; error text associated with the specific field; validation summary where multiple errors occur.
- Dialogs trap and restore focus; destructive confirmations require explicit text/reason when policy demands it.
- Status updates announced with appropriate `aria-live`; no continuous announcement spam from polling.
- Accessible names for icons, navigation landmarks, breadcrumbs, table headers, source results and graph alternatives.
- Source highlighting has text-based equivalent, keyboard focus target and screen-reader-accessible citation information.
- Reduced-motion preference honored; no animated fake upload/process progress.
- Contrast verified using automated and manual checks; target WCAG 2.2 AA for applicable interface components.

## 5. Frontend module architecture

### 5.1 Technology baseline

- React, TypeScript `strict`, Vite, React Router, TanStack Query, Zod, Mantine.
- Vitest + Testing Library for components/application behavior, Playwright for browser E2E.
- Versioned OpenAPI-generated TypeScript clients; independent schema validation at integration boundary where needed.
- Latest supported LTS where an LTS stream exists; otherwise maintained stable compatible versions, pinned through lockfile and controlled upgrades. Exact versions require build verification, not guesses in this blueprint.
- Internationalization structure can be localized later, but v1 copy is English only.

### 5.2 Suggested source tree

```text
apps/knowledge-admin-ui/
  src/
    app/
      providers/           # Mantine, QueryClient, Router, session boundary
      routing/             # routes, loaders, access/unknown states
      layout/              # WorkspaceShell, navigation, header
      theme/               # tokens, dark/light
    domains/
      documents/           # draft, metadata, versions
      uploads/             # upload lifecycle state machine
      processing/          # runs and stage graph
      collections/         # memberships, audience policy
      reviews/             # exact-version decisions/publication
      search/              # retrieval modes/results/evidence
      preview/             # secure viewer/citation resolution
      governance/          # retention/hold/withdrawal/purge
      audit/               # privileged event inspection
      operations/          # operator-level status
    shared/
      api/                 # generated clients + normalization
      auth/                # session/capability contracts
      components/          # accessible design-system wrappers
      errors/              # typed errors, conflict/unknown outcome
      fixtures/            # synthetic tenant-scoped demo dataset
      test/                # test factories, MSW handlers, fixtures
    styles/
  tests/e2e/
  public/
```

**Dependency direction:** domain UI → feature application hooks → typed API/client boundary; no direct DB, S3, Neo4j or Temporal access. Presentational components must not infer backend rights from persona name or document classification.

### 5.3 State ownership

| State | Owner | Storage rule |
|---|---|---|
| Session and currently selected tenant | Auth/session provider | Clear secured state on session/tenant transition |
| Server documents, runs, collections, policies, results | TanStack Query per authenticated session/tenant scope | Invalidate/remove on access change; never reuse cross-tenant cache |
| Form state and tentative upload selection | Feature-local React state/form utilities | Discard sensitive data on navigation/expiry as appropriate |
| Dialog confirmation and temporary UI preferences | Feature-local state | Never interpreted as server acceptance |
| Theme preference | User preference / permitted local persistence | No credentials, tokens or private contents |
| Demo fixtures | Isolated mock adapter only | Explicitly labeled and excluded from production builds |

### 5.4 Suggested typed view models (not wire contracts)

```ts
type DocumentId = string;
type VersionId = string;
type ProcessingRunId = string;
type OpaqueRevision = string;

type CapabilityState = 'available' | 'unavailable' | 'unknown';
type AsyncOutcome =
  | { kind: 'idle' }
  | { kind: 'pending'; operationId?: string }
  | { kind: 'success' }
  | { kind: 'failed'; code: string; retryAllowed: boolean }
  | { kind: 'unknown'; operationId?: string };

interface DocumentView {
  id: DocumentId;
  title: string;
  latestVersionId?: VersionId;
  activePublishedVersionId?: VersionId;
  aggregateRevision?: OpaqueRevision;
  classificationLabel?: string;
  permissions: Record<string, boolean | 'unknown'>;
}

interface VersionView {
  id: VersionId;
  documentId: DocumentId;
  label: string;
  immutable: true;
  securityStatus: 'unknown' | 'pending' | 'cleared' | 'blocked';
  coreIndexStatus: 'unknown' | 'pending' | 'ready' | 'failed';
  graphStatus: 'unknown' | 'not_scheduled' | 'pending' | 'ready' | 'unavailable';
  previewStatus: 'unknown' | 'not_scheduled' | 'pending' | 'ready' | 'unavailable';
}

interface CitationView {
  citationId: string;
  documentId: DocumentId;
  versionId: VersionId;
  sourceLocator?: unknown; // replace with finalized contract union
}
```

Do **not** send these frontend view models as if they were agreed DTOs. Normalize authoritative API data with strongly typed adapters; define `sourceLocator` for PDF pages, PPTX slides, DOCX sections and XLSX sheet/cell only after the verified wire contract is available.

## 6. Core user journeys and acceptance behavior

### J01 — Auth/session/workspace

1. Sign in through supported OIDC, load the authenticated actor's available workspaces.
2. Enter a workspace, render only authorized navigation and data.
3. On tenant switch, logout, expiry or revoked entitlements: cancel in-flight requests where possible, clear protected Query caches and feature state, and revalidate all deep links.
4. If identity/authorization authority is down: fail closed; show a recoverable generic message without exposing content, document counts or titles.

**Acceptance:** no cross-workspace document preview/search snippet/list counts on switch; no stale cached results appear during loading.

### J02 — Metadata draft → secure upload → version commit

1. Save metadata-only draft; no file/version assumed.
2. Select PDF/DOCX/PPTX/XLSX, validate **size <= 100,000,000 bytes** and supported type before upload, while server independently validates actual type and structure.
3. Display distinct transfer, quarantine/malware scan, structural validation, version-recorded states.
4. Treat `verified` and `version recorded` as separate outcomes. Use a reconciliation operation when a completion request's outcome is unknown; do not blindly duplicate requests.
5. On version commit, show `ACCEPTED_PENDING_DISPATCH` until actual dispatch/queued/running signals arrive.

**Negative cases:** oversized file, unsupported/malicious file, quota exceeded, expired upload, scanner outage, network interruption, unknown commit outcome, permission changed during transfer.

### J03 — Processing and source preparation

1. Show current version's run and prior run history; distinguish unscheduled vs waiting vs actively running vs failed/cancelled.
2. Show parse/OCR, chunk/source maps, embeddings, lexical/vector index as applicable; optional graph and preview branches independently.
3. Allow retry/cancel only when the server returns an allowed action. Do not derive ETA or percent from completed stage count.
4. Expose meaningful failure class and next step; keep older active publication unaffected by new-version failure.

### J04 — Collection membership and audiences

1. Browse allowed collections; show members only with authorized visibility.
2. Initiate membership mutation, validate revision and classification compatibility with authoritative services.
3. On conflict or revoked policy, show stale-revision UX; never silently grant access or assume a collection overrides document ACL.
4. Confirm policy change only when backend mutation succeeds; do not report partial success as fully shared.

### J05 — Human review / publication

1. Open specific immutable version, review authorized source/preview and technical readiness.
2. Record approval for exact version under server-defined reviewer rules.
3. Publish only when malware-cleared, core semantic+lexical index ready, authorized review route available, valid approval exists, current KB audience revisions match and actor is permitted.
4. Use expected document aggregate revision, policy revision tokens and idempotency key in final command once contracts are defined.
5. On 409/403/503 or unknown response, preserve old active version and reconcile; do not optimistically update publication pointer.

### J06 — Search → citation → verified source

1. Choose available lexical/semantic/hybrid/graph modes; clearly label degraded capabilities.
2. Display only server-authorized results, citations and permissible snippets; do not show model-generated answers or uncalibrated confidence as truth probability.
3. Resolve a citation through trusted API, open source preview at verified page/slide/sheet/cell position and highlight it where mapping exists.
4. Treat citation/content access as fresh authorization on each new request; if withdrawn/revoked between results and click, show generic unavailable state without cached protected content.

### J07 — New version while earlier version is published

1. Latest stored vN+1 is separate from active published vN.
2. During upload, processing, approval and conflicts, public/search results remain authorized for vN.
3. Only successful server publication pointer change switches to vN+1.
4. Earlier versions remain immutable and available only via separately authorized historical access.

### J08 — Retention / legal hold / withdrawal / purge request

1. Show authoritative policy resolution status, legal-hold state and permitted actions.
2. Active hold or unresolved/active retention policy blocks purge eligibility. Expired period is not itself blanket authorization to destroy.
3. Withdrawal immediately changes authoritative visibility; asynchronous reindex is not relied on for access control.
4. A purge **request** is distinct from purge **completion**. Display queued/in-progress/blocked/completed/partial-failure outcomes from backend where supported.
5. Capture reason and appropriate confirmation; use actual server policy and role checks, not frontend-only prevention.

## 7. State / action / error matrix

| Operation | Distinct UI states | Safety-sensitive behavior |
|---|---|---|
| Session | loading, signed-in, expired, denied, IdP unavailable | Clear sensitive data; generic denied responses |
| Draft | empty, editing, validating, saving, created, conflict, unknown | No implied file/version |
| Upload | selected, transferring, quarantined, malware check, structure check, verified, version adding, committed, expired, blocked, unknown | No parsing of unsafe/quarantined artifact; reconcile unknown outcomes |
| Processing | not scheduled, pending dispatch, queued, running, retry waiting, blocked, failed, cancelled, core ready | Parallel branches; no invented percentage/ETA |
| Preview | requested, authorized, rendering, ready, unsupported, failed, revoked | No unauthorized cached images/artifacts |
| Collections | loading, members, revision conflict, incompatible audience, denied | Membership cannot expand ACL |
| Review | awaiting decision, approved exact version, rejected (only if supported), stale, denied | Approval not publication |
| Publish | ready to request, validating, committed, blocked, conflict, unknown | No optimistic active-version switch |
| Search | searching, results, empty, degraded, denied, dependency unavailable | Suppress forbidden snippets, counts, graph edges |
| Governance | retained, held, withdrawn, deletion requested, purge in progress, purged, blocked | No purge under hold/unresolved policy |
| Audit | loading, filtered, masked, denied, unavailable | Never claim local mock log is immutable audit |

**Server error handling:** normalize 400/422 validation, 401 session, 403 or privacy-preserving 404, 409 stale revision, 429 quota, 503 dependency outage and network/timeout unknown outcome. Place errors beside the attempted action. Retry only with confirmed idempotency and server authorization; no assumption that network silence means rollback.

## 8. API / frontend integration boundaries

The following are **contract groups**, not a claim that the endpoints, payloads or permission DTOs have been implemented. The SDD offers illustrative REST paths; OpenSpec/OpenAPI remains the source for exact wire shapes.

| UI feature | Service authority | Required contract evidence | Current status |
|---|---|---|---|
| Auth/workspace | OIDC + trusted entry/BFF | Session, tenant enumeration, capabilities, expiry/revocation | Pending CID-06/07/14 |
| Document list/detail/drafts | Document Management | Cursor pagination, permissions, metadata, aggregate revision | Pending generated API |
| Upload / version | Document Management + secure artifact gateway | Initialize, transfer, complete, status/reconcile, size/quota/security outcomes | Pending exact DTOs |
| Processing runs | Document Management + orchestration view | Run history, stage statuses, resume/retry/cancel permissions | Pending exact status enums |
| Collections / audience | Knowledge Base | Membership revision, compatible audience, authorization decision | Pending CID-07/14 |
| Review / publish | Document Management + KB authority | Approval ref, exact version, expected revisions, idempotency, current publication | Pending CID-07/14 |
| Search / graph | Knowledge Search | Search mode availability, result/citation/evidence, degrade flags | Pending exact DTOs |
| Preview/citations | Secure content gateway | Authorized content delivery, source locator, safe fallback | Pending source locator contract |
| Retention / purge / hold | Document Management | Policy status, hold, allowed actions, deletion request/status | Pending compliance decisions |
| Audit | Privileged audit API | Filter, redaction, authorized event envelope | Pending CID-15 |

**Integration rules:** use generated SDKs from versioned OpenAPI, validate/correlate normalized errors, isolate policy/capability checks in application hooks and server-confirmed operation DTOs. Never call persistence systems from browser. Avoid local-only allowed-permission caches; remove tenant-scoped query data on session/tenant transitions. Query cancellation alone is not a substitute for clearing cached protected data.

## 9. Demo prototype and fixtures

The UI prototype must use one deterministic dataset with tenant-separated fixtures, including:

- `Employee Onboarding Guide` — PDF, previously published, source citation.
- `Information Security Policy` — DOCX, old published version with newer candidate awaiting approval.
- `Platform Architecture Overview` — PDF, latest processing incomplete, previous published version remains active.
- `Quarterly Budget Summary` — XLSX, draft and active legal hold fixture.
- `Product Roadmap 2027` — PPTX, published and a retention-boundary fixture.
- Separate second-workspace fixture used for tenant-switch tests.

**Fixtures must connect** `Document → DocumentVersion → ProcessingRun → CollectionMembership → Approval → Publication → IndexEvidence → Citation → Preview → Governance → Audit`. Synthetic evidence must have stable IDs and locators. Every test/fake state carries an obvious `Demo/Mock` label. Use mock service worker (MSW) or equivalent adapter; no real files or credentials are sent to external services.

**Important:** the v0.1–v0.5 in-chat prototypes were self-contained local visual demonstrations, not one executable React project. This blueprint is the plan to produce a unified real codebase.

## 10. Work sequencing and dependencies

### Phase F0 — Foundation and shell (BP-00/01; cross-cutting BP-14)

- Bootstrap pinned React/Vite/TS/Mantine tooling, lint, format, unit/component tests, Playwright, accessible theme and shell.
- Establish `SessionProvider`, `TenantBoundary`, generated API adapters and `Capabilities` unknown/loading/denied states.
- Build shared domain fixture registry and mock transport; demonstrate switching workspaces without stale data.

**Exit:** shell routes render, keyboard navigation works, tests pass, no cross-workspace fixture leakage, explicit mock banner.

### Phase F1 — Documents and secure version journey (BP-02/03/14)

- Build Library, Draft, Detail, Versions, Upload Wizard, error handling and unknown-operation reconciliation UI.
- Model file verified vs version committed vs processing intent accepted distinctly.

**Exit:** complete demo J02 and negative scenarios; real integration gated by generated DM/upload contracts.

### Phase F2 — Processing, collections and preview (BP-04/05/06–10/12/14)

- Add processing run timeline/branch graph, history, per-stage errors; member/audience revision UI; secure viewer/source locator adapter.
- Do not pretend Docling/Qwen/Neo4j are run in the frontend; consume backend statuses only.

**Exit:** J03/J04 and preview fallback/authorization tests.

### Phase F3 — Review, publish, retrieval (BP-11/13/14)

- Build exact-version review, readiness checklist, conflict confirmation, non-optimistic publication and current-version search/citations/graph evidence.
- Model graph enrichment degradation separately from core readiness.

**Exit:** J05/J06/J07 in MSW integration; production integration requires CID-07/14.

### Phase F4 — Governance, observability and hardening (BP-15/14; validation BP-17)

- Add retention/hold/withdrawal/purge request tracking, authorized audit filtering, operations diagnostics, session revocation and failure states.
- Run accessibility, cross-tenant, E2E and contract tests; security review and production staging evidence outside frontend scope.

**Exit:** J08, test matrix, design acceptance and documented known limitations.

### Parallel work and ownership

- **Product/UX:** terminology, task hierarchy, accessibility reviews, permission-driven affordances.
- **Frontend:** shell, components, routes, state normalization, test harness and E2E.
- **DM/KB/Search backend owners:** versioned API contracts, command semantics and allowed actions.
- **Security/Compliance:** taxonomy, permitted operation matrix, retention/hold criteria, privacy and audit redaction.
- **Platform:** environments, OIDC integration, BFF routing, safe preview gateway, observability contracts.

Frontend work may proceed against explicit mocks while backend contracts are pending, but **real action enablement must not ship on invented assumptions**.

## 11. OpenSpec change breakdown

Proposed UI-focused changes in the project's existing OpenSpec workflow (not new approved architecture decisions):

| Change ID | Name | Main outputs | BP mapping |
|---|---|---|---|
| UX-001 | Portal foundation and capability-aware shell | Theme, layout, routing, auth/tenant boundary | BP-00/01/14 |
| UX-002 | Documents and metadata drafts | Library, detail, version history, pagination | BP-02/14 |
| UX-003 | Secure upload and version-confirm UX | Distinct verification/commit state machine | BP-03/14 |
| UX-004 | Durable processing run visibility | Stage DAG, run history, errors, recovery actions | BP-05–10/14 |
| UX-005 | Knowledge collections and audience UI | Membership/policy revision, conflict UI | BP-04/14 |
| UX-006 | Secure preview and citation navigation | PDF/Office/XLSX views, source locator, revocation | BP-12/14 |
| UX-007 | Version-specific review and publication | Readiness, approve, non-optimistic activation | BP-13/14 |
| UX-008 | Search and graph evidence | Modes, authorized results, citation inspector | BP-11/14 |
| UX-009 | Governance and audit | Retention/hold/withdrawal/purge request, events | BP-15/14 |
| UX-010 | Cross-cutting accessibility and test hardening | A11y, responsiveness, E2E, contract testing | BP-14/17 |

Every change requires: purpose, dependencies, user stories, normative requirements vs assumptions, component/state diagrams, authorized failure behaviors, API field dependencies, tests, migration/rollout notes, acceptance owner and review outcome. Create actual OpenSpec files only when implementation work is authorized in the relevant repository.

## 12. Open decisions / integration gate register

| Gate | Blocking decision or verification | Affected user experience | Owner to confirm |
|---|---|---|---|
| G-01 / CID-07 | Exact classifications, entitlements, resource actions and review policy | All action visibility; review; sharing | Security + Domain |
| G-02 / CID-14 | Generated OpenAPI schemas, pagination, errors, idempotency, revisions | All integrated data/mutations | API owners + Frontend |
| G-03 / CID-06 | OIDC session and tenant transition behavior | Auth, global shell, deep links | Security + Platform |
| G-04 | Upload commit/status/reconciliation semantics | Retry, unknown outcome, duplicate prevention | Document Management |
| G-05 | Processing DAG/status and allowed recovery commands | Stages, cancel/retry, error copy | Processing + DM |
| G-06 | Authorized preview modes and source locator precision | PDF, Office, XLSX, citation highlight | Preview + DM/Search |
| G-07 | KB membership/policy revision tokens and policy compatibility | Collections and publication | Knowledge Base + DM |
| G-08 | Retention, legal hold, purge approval and backup semantics | Governance, destructive confirmations | Compliance + DM |
| G-09 / CID-15 | Audit query, redaction and retention | Audit history / privileged event views | Security/Compliance |
| G-10 | Measured performance and supported search modes | Search loading/degraded UX | Search team |

**Stop rule:** if a missing answer changes a business invariant or an approved local decision, do not decide it inside frontend code. Open a tracked contract/decision request and retain disabled/unknown UI state.

## 13. Acceptance criteria and E2E verification

### 13.1 Baseline gates

- TypeScript strict mode, lint/typecheck/build, tests, generated API compatibility checks.
- At least **75% automated source coverage**, plus mandatory critical user-journey E2E and explicit security-negative cases. Percentage alone is not enough.
- Screen reader/keyboard acceptance for core flows and visible focus/contrast in both themes.
- Server authority preserved on every resource read/mutation; no cross-tenant exposure.
- No fake progress, ETA or hidden background execution; unknown operation results explicitly reconciled.

### 13.2 Playwright E2E matrix (minimum planned set)

| ID | Scenario | Expected result |
|---|---|---|
| E2E-01 | Sign in → select workspace | Only permitted workspace shell appears |
| E2E-02 | Switch workspace while document open | Protected prior data/caches cleared |
| E2E-03 | Deep link to unauthorized document | Generic denied/not-found, no title or count leak |
| E2E-04 | Create metadata-only draft | No version/upload assumed |
| E2E-05 | Upload valid PDF/DOCX/PPTX/XLSX fixture | Verification and version-commit states distinct |
| E2E-06 | Upload 100,000,001-byte file | Rejected by exact limit |
| E2E-07 | Scanner down / malware / bad structure | Quarantined/blocked; never processed |
| E2E-08 | Interrupted completion / unknown outcome | Reconcile; no duplicate version |
| E2E-09 | Processing intent not dispatched | Clear `Pending dispatch`, no false running spinner |
| E2E-10 | Parallel optional graph failure | Core readiness displayed independently |
| E2E-11 | Previous published version during new processing | Current publication stays old version |
| E2E-12 | Collection incompatible classification | Mutation/publication cannot widen visibility |
| E2E-13 | Collection revision conflict | 409 handling with reload/revalidation |
| E2E-14 | Exact-version approval | Approval tied to version, not whole document |
| E2E-15 | Publish with stale document revision | No optimistic active-pointer switch |
| E2E-16 | Publish with KB policy authority down | Blocks activation; old pointer intact |
| E2E-17 | Successful publish after valid checks | Exactly one active version shown |
| E2E-18 | Search authorized hybrid results | Correct source/citation and scope only |
| E2E-19 | Search degradation / Neo4j unavailable | Honest degraded or unavailable state |
| E2E-20 | Open citation to verified source | Page/slide/sheet/cell target where supported |
| E2E-21 | Revoke source access after search | No cached unauthorized preview |
| E2E-22 | Active legal hold | Purge request disabled and server denied |
| E2E-23 | Unresolved retention | No destructive action offered |
| E2E-24 | Withdrawal and purge request | Visibility changed; request != completed purge |
| E2E-25 | Audit authorization/redaction | Unauthorized actor sees no privileged records |
| E2E-26 | Accessible keyboard/modal workflows | Operable in dark/light and narrow view |
| E2E-27 | Session expiry during mutation | No success claim; protected state purged |
| E2E-28 | Network timeout on publish | Unknown state reconciled, not blindly retried |

Where tests rely on server security guarantees, browser tests must be complemented by API/service integration and adversarial tests owned by backend/security teams.

## 14. Design handoff deliverables

- Approved route/screen map and responsive behavior matrix.
- Mantine theme tokens, page layouts and reusable component catalog.
- Screen-state table covering loading/empty/success/denied/conflict/failed/unknown-outcome.
- Versioned synthetic corpus and MSW scenarios with resettable deterministic state.
- Storybook-style visual component examples (optional tool selection; not a mandatory new dependency).
- OpenAPI/AsyncAPI field and capability dependency register linked to SDD and CID records.
- Playwright E2E suite plus accessibility checks and annotated failure examples.
- OpenSpec changes UX-001–UX-010 with acceptance owners, linked to BP-00–17.
- Open decisions log with blockers, owner, evidence and resolution; no silent substitutions.

## 15. Definition of Done and non-goals

**Prototype-ready:** consistent shell; one cross-screen dataset; interactive happy and negative fixtures; accurate distinction between mock and real; keyboard usability; no phantom capabilities.

**Frontend integration-ready:** necessary APIs and capability semantics generated and tested; protected state cleared across tenant/session changes; server errors/revisions handled; review/publish/purge flows do not rely on optimistic assumptions.

**Production-ready:** additionally requires backend/security production approvals, tested performance and disaster recovery, secure uploads/preview, operational runbooks, actual audit, and environment evidence established by the system-wide implementation program. **This frontend blueprint alone cannot grant production approval.**

**Non-goals:** changing backend architecture, building agents/chat, implementing AI models in the UI, substituting frontend policy checks for service authorization, introducing speculative bulk operations, version diff, new permissions, arbitrary infrastructure admin screens, or direct access to persistence.

---

## 16. Implementation kickoff checklist

- [ ] Product owner approves this UX blueprint and explicitly labels open assumptions.
- [ ] Backend/API owners review the capability contract dependency table.
- [ ] Security/Compliance approves release-critical classification/review/retention semantics.
- [ ] Frontend team validates Mantine component accessibility and theme tokens.
- [ ] Synthetic demo corpus is versioned and tenant-separated.
- [ ] OpenSpec UX-001 is created in the implementation repository when authorized.
- [ ] CI verifies typecheck, unit/component tests, Playwright, accessibility and coverage.
- [ ] No existing Library or upstream architecture record is overwritten without explicit approval.

**End of blueprint — DKP-UX-002 v0.7.0-draft.**