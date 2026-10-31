# portal-shell Specification

## Purpose
Provide an accessible administration entry point and verifiable navigation without introducing unimplemented document or knowledge workflows.

## Requirements

### Requirement: Functional navigation
The portal SHALL provide a responsive application shell with a home route and a foundation-information route, semantic landmarks, a visible active navigation state, keyboard navigation and a not-found view. It SHALL use React, Vite and strict TypeScript in the pnpm workspace.

#### Scenario: Navigate and reload
- **WHEN** a user opens home, navigates to foundation information, and reloads that route
- **THEN** the correct view and active link remain visible without an error

#### Scenario: Keyboard and unknown route
- **WHEN** a keyboard user follows navigation and then opens an unknown route
- **THEN** navigation has a visible focus state and the not-found view provides a working home link

### Requirement: Honest foundation state
The portal SHALL identify its implemented metadata-only draft capability and SHALL offer no upload, search, publication, plugin, purge or hold-release actions. It SHALL neither fabricate business results nor access persistence directly.

#### Scenario: Inspect available actions
- **WHEN** a user opens a shell view
- **THEN** navigation, presentation, real identity and authorized draft metadata/ACL/hold controls are available without simulated processing or persistence bypass

#### Scenario: Tenant changes during draft use
- **WHEN** a user switches tenant or logs out while draft data is cached
- **THEN** previous scoped server-state caches and visible draft context are removed before new scoped results render

### Requirement: Authorized scoped portal data
It SHALL use same-origin BFF actions, render authenticated draft/ACL/hold data only after server authorization and show anonymous/authenticated/expired/unavailable states. All server state SHALL use pinned TanStack Query with Zod boundary validation; tenant/session change SHALL remove previous server-state caches.

#### Scenario: Render scoped draft state
- **WHEN** an authenticated user requests draft data or changes the selected tenant or session
- **THEN** the portal uses same-origin BFF authorization and validated Query state, clearing prior scoped caches before rendering the new state

### Requirement: Browser secret exclusion
No bearer token, credential or environment secret SHALL be embedded in the bundle or browser storage.

#### Scenario: Inspect browser authority material
- **WHEN** a contributor inspects the portal bundle and browser storage after a session journey
- **THEN** no bearer token, credential or environment secret is present

### Requirement: Theme preference
The shell SHALL default to a dark theme and provide a light theme option with accessible control labeling and readable content in both modes.

#### Scenario: Switch theme
- **WHEN** the user selects the light theme using the keyboard
- **THEN** the theme changes and navigation and content remain operable

#### Scenario: Complete identity journey
- **WHEN** a user logs in through the real identity provider, selects an authorized tenant, requests each service context and logs out
- **THEN** the portal displays verified identity and tenant context, sends CSRF guards on mutations and returns to the anonymous state after logout

#### Scenario: Security dependency failure
- **WHEN** a session expires or an identity/context request is denied or unavailable
- **THEN** the portal shows an accessible error or reauthentication state without fabricating permissions or context
