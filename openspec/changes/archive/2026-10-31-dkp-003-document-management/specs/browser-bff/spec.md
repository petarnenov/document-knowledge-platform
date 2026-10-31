# Browser BFF

## ADDED Requirements

### Requirement: Fixed authenticated DM draft delegation
BFF SHALL proxy only the reviewed DM methods/paths in the frozen contract, mapping /api/v1/dm/documents to DM /api/v1/documents. Existing authenticated server sessions, tenant selection, token expiry, exact Origin and session CSRF SHALL apply to every unsafe DM route. Browser authority/URL/identity headers SHALL be stripped; verified server-held bearer and checked tenant SHALL be reconstructed. No arbitrary target, redirect or unbounded body/response SHALL be accepted.

#### Scenario: Real authenticated draft journey
- **WHEN** a synthetic provisioned user creates, lists/inspects, updates and holds a draft through HTTPS BFF
- **THEN** real DM independently enforces catalog+ACL and returns contract-safe persisted results

#### Scenario: Proxy or mutation bypass
- **WHEN** an actor forges tenant/target headers or omits Origin/CSRF on a DM mutation
- **THEN** no mutation or credential forwarding to an unintended destination occurs
