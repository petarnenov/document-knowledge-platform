## ADDED Requirements

### Requirement: Fixed bounded binary and original delegation
BFF SHALL delegate only frozen upload/status/complete/append/exact-version-content paths to fixed DM. Verified session/tenant/token rules SHALL apply; every unsafe JSON/binary request SHALL require exact Origin+CSRF before forwarding bytes. Metadata16384byte guards SHALL remain; binary streaming gets separate100000000byte/timeout bounds. Redirects/arbitrary URLs/header authority SHALL be rejected.

#### Scenario: Real guarded upload and original journey
- **WHEN** real OIDC browser creates a draft, uploads/verifies/appends and downloads an authorized exact version
- **THEN** DM independently rechecks policy and real storage evidence; browser sees no bearer/storage credential or S3 link

#### Scenario: Raw binary CSRF or tenant spoof
- **WHEN** binary mutation omits guard or forges selector/identity
- **THEN** no unauthorized forwarding/mutation occurs and selected tenant remains server-bound

### Requirement: Fixed consumed-version discovery delegation

BFF SHALL proxy only the frozen exact receipt/document version-lookup GET to configured DM, with verified session/tenant and server-held token. It SHALL expose only reviewed documentId/versionId after independent DM content.review+ACL/final checks. No arbitrary target, redirect, hidden list/count or storage locator SHALL be accepted or returned.

#### Scenario: Append to exact original journey
- **WHEN** a real browser appends a verified receipt then requests its actor-owned mapping and exact-version original
- **THEN** each GET independently enforces current policy and the authorized bytes match the immutable upload without enlarging command responses
