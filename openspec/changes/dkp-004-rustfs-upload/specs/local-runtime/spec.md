## MODIFIED Requirements

### Requirement: Minimal local composition
Compose SHALL start only used domain APIs/BFF/HTTPS portal/Keycloak/DM PostgreSQL plus pinned RustFS, standard ClamAV/updater and isolated structural inspector. Private networks, scoped generated credentials, CA-verified object/inspection TLS, non-root/resource/read-only/cap limits and persistent owned volumes SHALL match reviewed design. No unused broker/model/processing worker SHALL start.

#### Scenario: Real used dependencies
- **WHEN** bootstrap and Compose start BP03
- **THEN** all four formats use actual private RustFS/scanner/inspector; unavailable/stale dependencies prevent verification and readiness never fabricates capability

#### Scenario: Start the foundation
- **WHEN** documented bootstrap and Compose startup complete
- **THEN** the synthetic user completes a real authenticated draft/upload journey, scoped migrations/runtimeDML work and restart preserves state without permissive tenant rules

### Requirement: Local persistence and session limits
BFF SHALL remain one ephemeral-session replica with BP16 limit explicit. PostgreSQL SHALL persist scoped migrations/runtime state; RustFS first introduced in BP03 SHALL persist private exact-version objects. Generated synthetic grant, scoped keys and secrets SHALL be ignored/read-only/restrictive. Quotas/cleanup/evidence SHALL survive restart; no root application credential or anonymous storage policy.

#### Scenario: Restart and scoped credentials
- **WHEN** local stack restarts or a scoped app attempts public/admin/other-prefix access
- **THEN** owned state survives and unauthorized storage operations fail; no permissive fallback

#### Scenario: Inspect local trust and data boundaries
- **WHEN** a contributor starts the implemented local composition
- **THEN** BFF has one ephemeral-session replica, DM/object data persists, separate scoped credentials exist and synthetic grant material remains ignored/read-only
- **AND** RustFS is the first object store introduced, only with reviewed BP03 verification and content controls
