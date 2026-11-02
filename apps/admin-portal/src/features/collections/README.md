# Collections presentation — #446

`CollectionsWorkspace` consumes the shared `.card`, `.rowline`, `.detail`, `.toolbar`, `.sub`, `.hint`, `.grow` and heading contracts established in #443. The `/collections` route mounts it without props on shell baseline 5dcfcb1. It is not connected to a service: it renders unavailable collection, membership and audience sections, unknown policy facts and disabled commands. No sample records enter production code. This route integration is presentation only, not real collection implementation or completion.

`CollectionsView` is a presentation model, not an API DTO or authorization result. Only a future typed adapter backed by authenticated tenant/session-scoped TanStack queries may supply currently authorized rows. Boundary variants cannot carry stale records. A supplied membership or audience ALLOW never enables content links, download, publication or changes; commands remain unavailable in this slice. Selection is transient UI state, restricted to the current supplied records.

## Inspected contracts and unresolved integration

- Baseline `origin/dev` de27965 has no KB OpenAPI or generated collection client. The approved BP-04 specification does not prove integrated runtime capability.
- Read-only inspection of BP-04 integration c2b7305 shows `Collection` metadata, status, ceiling and revision fields; bounded `CollectionPage` and `MembershipPage`; and audience rules/revision. Those branch files were not copied or changed.
- Membership rows provide document IDs, not authorized document titles. Collection and membership pages provide no aggregate linked counts. This component shows IDs as inert text and explicitly unavailable totals; it never infers titles, hidden counts, authorization, classification compatibility or mutation success.
- A future integration needs reviewed generated contracts/adapters, current list/detail/membership/audience authorization, session/tenant cache isolation and cancellation, opaque-cursor handling, 401/403/404/409/503 mapping, revision-safe commands and real backend acceptance. This component supplies none of those operational capabilities.

## Supplemental host verification

Use pinned Node 24.22.1 and pnpm 12.16.1. From the repository root:

```sh
corepack pnpm --filter admin-portal exec vitest run src/features/collections/CollectionsWorkspace.test.tsx --coverage --coverage.include=src/features/collections/CollectionsWorkspace.tsx
corepack pnpm --filter admin-portal typecheck
corepack pnpm --filter admin-portal exec eslint src/features/collections --max-warnings 0
corepack pnpm --filter admin-portal exec node src/features/collections/testing/render.mjs
```

The browser runner starts and closes its own Vite server on an ephemeral loopback port. It never targets the manual preview or another stack. Twenty dark/light, 1440/390 journeys cover available test fixtures and unavailable/unknown/denied/conflict boundaries, consistent nested notices, keyboard-compatible selection, disabled commands, absent API calls, no overflow, and the measured 13px/17px card geometry. Output defaults to `/private/tmp/dkp446-collections`; `DKP_COLLECTIONS_VISUAL_OUTPUT` can select another output directory. `report.json` records component/harness hashes and the bounded proof claim.

`testing/harness.*` is a test-only entry and an explicit stand-in for the measured shared theme contract. The production application does not import it. Its synthetic records and screenshots are not real backend evidence or proof of integrated theme fidelity. Reference screenshots were inspected before implementation; supported content differs deliberately from prototype fixture taxonomies/counts and active mock actions. Final shared Linux checks, independent review, integrated reference comparisons and applicable real acceptance remain required. The full Collections feature is not complete.

The shell integration's focused App/component tests additionally verify direct navigation, session 200/401/503 boundaries, absent KB requests, disabled commands and unchanged neighboring capability routes. No browser server, runtime or port was started for this integration window; earlier standalone fixture screenshots do not establish integrated route fidelity.
