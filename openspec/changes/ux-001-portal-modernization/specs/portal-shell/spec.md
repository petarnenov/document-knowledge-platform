## ADDED Requirements

### Requirement: Balanced workspace navigation
The portal SHALL provide a responsive Mantine application shell with comfortable density, preserved existing routes, keyboard-operable mobile navigation and shared accessible loading, empty, error, conflict and unavailable states. Dark SHALL remain the default with operable light switching. Authentication and tenant selection SHALL retain existing authority.

#### Scenario: Mobile keyboard navigation
- **WHEN** a user opens navigation on a narrow viewport and chooses an existing route or presses Escape
- **THEN** navigation and focus behave coherently, collapsed controls are not focusable, and content remains operable in both themes

### Requirement: Mandatory TanStack server state
All frontend server state SHALL use TanStack React Query queries and mutations through feature hooks and typed clients/adapters to the trusted BFF. Protected keys SHALL be scoped by authenticated session and tenant. Context transitions, logout, expiry and revoked access SHALL cancel and remove protected state under existing security rules. Local React state SHALL serve transient UI/forms without a parallel component fetch/useEffect server-state cache.

#### Scenario: Protected context changes
- **WHEN** the selected tenant or authenticated session changes or access ends
- **THEN** stale protected state is removed and cannot reappear from an in-flight request or an unscoped cache

### Requirement: Truthful document lifecycle presentation
The modernized library, draft and upload journey SHALL reuse existing contracts and distinguish upload transfer, verification, recorded immutable version, processing, approval and publication. Unknown or missing capabilities SHALL be unavailable rather than fabricated. Collection membership SHALL never imply permission to read content.

#### Scenario: Version append succeeds without processing capability
- **WHEN** the existing BP-03 append returns201 with documentId and revision
- **THEN** the UI reports a recorded version without claiming processing, approval or publication and preserves uncertain-outcome reconciliation

#### Scenario: Untrusted document title
- **WHEN** a backend title contains markup-like text
- **THEN** the presentation renders escaped text without executable content


### Requirement: Isolated real manual preview
The development preview SHALL use a dedicated Compose project, fresh private synthetic authority and distinct loopback ports24443/24444. Its runner SHALL select only owned resources, preserve active stacks and retain strict TLS/BFF/tenant boundaries. Identity and draft journeys SHALL use real services; missing capabilities SHALL remain unavailable.

#### Scenario: Preview alongside active acceptance
- **WHEN** the owner opens the frontend manual preview while other package acceptance environments exist
- **THEN** the preview uses its own resources and browser profile, shows only demonstrated real capabilities, and neither restarts nor changes another stack


#### Scenario: Manual browser access
- **WHEN** the owner receives the preview URL
- **THEN** access instructions list demonstrated scenarios and remaining verification gaps and require a dedicated browser profile to isolate localhost cookies shared across ports

### Requirement: Verified prototype fidelity
The portal SHALL reproduce Interactive Design v0.5 visual composition and demonstrated interactions using reusable Mantine components, preserving accepted security and domain invariants. The prototype SHALL be inspected in a rendered browser. Reference and React captures SHALL use matching themes, viewports and comparable test fixtures. Material intentional visual deviations SHALL require explicit product approval. Synthetic prototype actions SHALL NOT become simulated production success.

#### Scenario: Responsive shell comparison
- **WHEN** the shell is implemented at desktop and mobile widths in both themes
- **THEN** reference and React screenshots, measurements, remaining deviations and executed comparison results are retained before visual completion is claimed

#### Scenario: Capability unavailable
- **WHEN** a demonstrated prototype operation has no implemented trusted contract
- **THEN** its visual structure remains identifiable with an explicit unavailable state and no fabricated content, permission or operation result
