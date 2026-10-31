## MODIFIED Requirements

### Requirement: Theme preference
The shell SHALL default to dark and offer a visible keyboard-operable light/dark switch. It SHALL persist only exact light/dark values at `mantine-color-scheme`, reuse a valid preference across navigation/reload/tenant/logout, and default invalid/absent values to dark. Storage failure SHALL preserve operable in-memory selection. Themes SHALL use one Mantine color-scheme authority without resetting forms, focus, route, queries or commands.

#### Scenario: Switch theme
- **WHEN** the user selects the light theme using the keyboard
- **THEN** the theme changes and navigation and content remain operable

#### Scenario: Complete identity journey
- **WHEN** a user logs in through the real identity provider, selects an authorized tenant, requests each service context and logs out
- **THEN** the portal displays verified identity and tenant context, sends CSRF guards on mutations and returns to the anonymous state after logout

#### Scenario: Security dependency failure
- **WHEN** a session expires or an identity/context request is denied or unavailable
- **THEN** the portal shows an accessible error or reauthentication state without fabricating permissions or context

#### Scenario: Reuse a valid preference
- **WHEN** a valid light or dark preference exists and the user loads any portal route, navigates, reloads, changes tenant or logs out
- **THEN** the selected mode remains consistent from first application render while protected context follows its independent lifecycle

#### Scenario: Invalid or unavailable storage
- **WHEN** storage is absent, invalid, auto, malformed or inaccessible, or writing a selection fails
- **THEN** initialization defaults dark and switching remains usable in memory without crashes, credential logging or unrelated storage changes

#### Scenario: Synchronize tabs safely
- **WHEN** a same-origin theme storage event sets light/dark, deletes the key or supplies an invalid value
- **THEN** the tab applies the valid mode or dark fallback, ignoring unrelated events and removing subscriptions on unmount

#### Scenario: Change theme during a command
- **WHEN** a user switches mode with form edits, focused controls or pending authorized work
- **THEN** state and focus are preserved and no command, protected-data refetch or authorization-context change is caused by the switch

## ADDED Requirements

### Requirement: Shared Mantine portal foundation
The portal SHALL use exact compatible Mantine core/hooks9.7.4 with one stable provider, shared reviewed theme tokens and styled Mantine shell/controls. Existing overview, foundation, identity, tenant and document controls, including approved upload controls present at integration, SHALL migrate now. Future screens SHALL share this foundation. Strict React/TypeScript/Vite compatibility and existing TanStack Query/Zod boundaries SHALL be preserved.

#### Scenario: Inspect common controls
- **WHEN** a contributor renders each existing route in either theme
- **THEN** the shell, forms, actions, loading/error/status and disabled states consistently use the common Mantine foundation with real router navigation and preserved business behavior

#### Scenario: Add a later screen
- **WHEN** a subsequent delivery package adds portal controls
- **THEN** it reuses the provider, tokens and shared component conventions rather than introducing a separate theme or deferring adoption to BP-14

#### Scenario: Dependency incompatibility
- **WHEN** frozen peers, strict compilation, production build or dependency/security checks fail
- **THEN** delivery remains blocked until a reviewed compatible fix passes without forced peers or unrelated unreviewed upgrades

### Requirement: Accessible modes and preserved authority
Both themes SHALL preserve semantic landmarks, labels, keyboard/focus, status/error announcements, responsive layouts and WCAG AA contrast. UI migration SHALL preserve BFF-only business access, server identity/tenant/ACL/content authority, CSRF/idempotency guards and protected-cache cleanup. The non-secret theme key SHALL be the sole new browser-storage allowance; credentials, tokens and protected server data SHALL remain excluded.

#### Scenario: Keyboard and contrast in both modes
- **WHEN** desktop, mobile or zoomed keyboard users navigate and operate forms or read errors in light and dark
- **THEN** visible focus, coherent switch state, readable contrast, labels and status semantics remain usable without traps or page-level horizontal overflow

#### Scenario: Protected context ends while theme persists
- **WHEN** tenant/session changes, logout, revocation, denial or dependency failure removes access
- **THEN** protected visible/query state is removed or denied under existing rules while theme persistence grants no authority and browser storage contains only the permitted preference

#### Scenario: Untrusted values in styled controls
- **WHEN** titles, descriptions or errors contain markup-like or long content
- **THEN** controls render safe escaped text with usable layout and introduce no unsafe HTML, link or style execution
