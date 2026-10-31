# Portal Shell

## MODIFIED Requirements

### Requirement: Honest foundation state
The portal SHALL identify its implemented metadata-only draft capability and SHALL offer no upload, search, publication, plugin, purge or hold-release actions. It SHALL neither fabricate business results nor access persistence directly. It SHALL use same-origin BFF actions, render authenticated draft/ACL/hold data only after server authorization and show anonymous/authenticated/expired/unavailable states. All server state SHALL use pinned TanStack Query with Zod boundary validation; tenant/session change SHALL remove previous server-state caches. No bearer token, credential or environment secret SHALL be embedded in the bundle or browser storage.

#### Scenario: Inspect available actions
- **WHEN** a user opens a shell view
- **THEN** navigation, presentation, real identity and authorized draft metadata/ACL/hold controls are available without simulated processing or persistence bypass

#### Scenario: Tenant changes during draft use
- **WHEN** a user switches tenant or logs out while draft data is cached
- **THEN** previous scoped server-state caches and visible draft context are removed before new scoped results render
