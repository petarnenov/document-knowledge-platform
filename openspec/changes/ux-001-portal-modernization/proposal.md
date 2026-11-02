# Change: Modernize the portal foundation and document workspace

## Why
Implement the owner-approved Interactive Design v0.5 as the primary visual and interaction reference, with Blueprint v0.7.0 architecture/accessibility guidance and frontend rework instructions v1.0.0, incrementally while preserving working BP-00–BP-03 behavior and independent BP-04 development.

## What Changes
- Faithful Mantine Balanced Workspace shell, measured prototype tokens, persistent responsive icon rail, shared accessible states, comfortable density and dark/light themes. Rendered reference inventory and matching-viewport visual comparison are required.
- Modernized document library, draft detail and secure upload verification journey using existing trusted BFF contracts.
- Pure typed lifecycle adapters and a capability compatibility matrix; unsupported capabilities remain unavailable.
- Mandatory TanStack React Query server state and the full project implementation protocol for all frontend streams.

## Impact
Tracking: #434 / #443; shell #435, document UX #436, adapters #437. Scope: portal-shell requirements, admin portal presentation and an isolated development preview runner/Compose override with fresh synthetic authority. No backend, OpenAPI, generated client, domain, permission or storage changes. Blueprint append/processing wording cannot override current BP-03 append201 semantics; BP-05 processing remains unavailable. Later collection slices depend on reviewed BP-04 integration rather than assuming its unintegrated worktree is dev.
