# Design

## Authority and baseline
Current visual baseline origin/devde279657e6bb954c5f4f3703617f693c1f89c347; earlier functional baseline85c4454. Primary visual/interaction reference: rendered Interactive Design v0.5. Architecture/accessibility inputs: both canonical UI/UX documents in docs/architecture, accepted system design, approved portal/theme/upload OpenSpec and versioned BFF contracts. Domain/security invariants remain authoritative.

## Current and target
The portal already uses strict TypeScript, Mantine, Router, Query and Zod with working session, tenant, draft and upload flows. Retain these boundaries and upgrade presentation through a responsive shell and independently composed feature components. Existing route URLs, labels used by operational tests, CSRF, idempotency, tenant checks and reconciliation remain intact. Map shared Mantine theme tokens to the rendered prototype while preserving preference persistence, forms and server-state boundaries.

## Ownership and slices
Root owns integration, OpenSpec, shared dependencies/configuration, routing integration and combined E2E. #435 owns shell/shared primitives; #436 owns document/upload presentation; #437 owns pure adapters and compatibility matrix. The original streams used isolated worktrees. During #443 integration, a separate shared worktree preserves the live434 manual preview; each agent has exclusive file ownership (root routing/Home/config/docs, A shell/theme/shared CSS/primitives, B documents/uploads, E visual tests/helper). No two owners write the same file. First integrate reviewed shell and supported document behavior; collection/access and advanced capabilities follow separate dependency-aware slices. See docs/development/uiux-contract-compatibility.md for A–E classifications; no wire extension is proposed here.

## Risk and verification
Retest mobile navigation/focus, both themes, zoom, escaped untrusted titles, real identity/tenant transitions, draft creation, upload limit, verification/append distinction and uncertain-outcome reconciliation. Preserve protected cache purge and deny/unavailable states. Local host checks supplement shared pinned Linux CI checks; require independent review, published-source CI and applicable real acceptance before merging. Record fingerprints/environment/commands/results/evidence. Do not mutate another task's stack or waive long acceptance.

## Mandatory server-state completion (#441)
The existing Identity tenant-selection and logout commands still use an imperative local busy wrapper. Move these commands into TanStack mutation ownership through a typed feature hook while preserving exact BFF/CSRF requests, fail-closed errors, cancellation and protected cache purge. Context reads remain queries. This closes an identified implementation gap under the already approved mandatory server-state requirement; no security or wire contract changes are authorized. Require identity regression and real OIDC checks.

## Rollback
Revert the reviewed frontend slice through a PR; no schema/data migration or backend rollback is required. Unavailable navigation is informational and cannot grant capability or content access.


## Manual preview (#440)
Use the existing hardened foundation services and pinned images with an explicit isolated override at24443/24444, own private generated certificates/identity files, project network and DM volume. Remove published API probe ports; change every OIDC/origin/callback/TLS mount consistently. Existing fixed-port acceptance scripts must not be invoked against other stacks. An explicit owned runner can reuse the strict TLS browser argument builder with verified preview IDs. Initial preview covers real identity/tenant/drafts; secure upload needs its actual provider runtime and remains unavailable here. Do not automatically change global browser trust or share localhost cookie profiles. Record source/resource identities and exact cleanup scope.

## Reference comparison boundary (#443)

Capture reference and React in the same browser, viewport and theme. Exact measured shell comparison is a bounded gate; it does not establish full-page pixel equivalence or backend acceptance. Retain paired screenshots and all meaningful deviations. API fixtures in browser visual tests remain explicitly supplemental; production never imports prototype mock actions or synthetic state. Global totals, latest version, review/publication/processing and recency cannot be inferred from bounded draft pages or aggregate revision. Material intentional deviations require explicit product approval. The latest owner AGENTS requires independent PR review and no automatic merging.
