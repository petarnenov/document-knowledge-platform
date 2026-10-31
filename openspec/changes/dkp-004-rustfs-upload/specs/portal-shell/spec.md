## MODIFIED Requirements

### Requirement: Honest foundation state
Portal SHALL identify implemented draft and BP03 verified-upload/original-review capabilities. It SHALL offer no simulated processing/search/publication/plugin/purge/hold-release or rendered preview. Upload→verify→explicit append SHALL be distinct observable outcomes; active pointer stays unchanged. It SHALL never fabricate business results or access persistence directly.

#### Scenario: Inspect available actions
- **WHEN** a user opens document/upload/version controls
- **THEN** real authorized upload/verification/append/original download is distinct from unavailable processing/publication

#### Scenario: Tenant changes during upload
- **WHEN** user switches tenant/logs out with an in-flight file or cached server state
- **THEN** scoped visible state is removed and old session cannot authorize further transfer/complete/content

#### Scenario: Tenant changes during draft use
- **WHEN** a user switches tenant or logs out while draft data is cached
- **THEN** previous scoped server-state caches and visible draft context are removed before new scoped results render

## ADDED Requirements

### Requirement: Authorized version discovery before original review

Portal SHALL resolve its known consumed receipt using the fixed BFF mapping before exact-version original download. It SHALL preserve minimal command/status schemas and use Query/Zod scoped server state. Append-only actors SHALL not gain discovery/content privilege; denied/unavailable/revoked mapping SHALL clear protected result state and show a safe error.

#### Scenario: Privileged original review happy path
- **WHEN** an explicitly permitted actor completes scan, appends, resolves the receipt and downloads its exact version
- **THEN** real backend responses supply each outcome and byte parity holds without invented version IDs or browser storage authority
