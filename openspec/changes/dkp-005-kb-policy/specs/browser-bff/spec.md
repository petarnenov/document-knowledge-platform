## ADDED Requirements

### Requirement: Fixed KB administration delegation
BFF SHALL expose only frozen `/api/v1/kb` routes with exact methods, bounded paths/queries/bodies and schema-validated responses. It SHALL use server-held original access token and selected tenant with actor/tenant parity and independent KB validation. Every unsafe request including policy snapshot/validation SHALL require exact Origin and CSRF before upstream work. DM share assessment SHALL have no browser proxy route.

#### Scenario: Forged proxy authority
- **WHEN** browser supplies another tenant/actor, arbitrary URL/path escape, unsupported method or missing Origin/CSRF
- **THEN** the BFF rejects before upstream mutation without forwarding attacker identity or service selection

### Requirement: Noncacheable finite KB responses
BFF SHALL enforce16KiB request bounds with HTTP decompression disabled, closed DTOs, finite errors, response size limits and no redirects/ETags/304. Upstream identity, resource or response-shape mismatch SHALL fail503 without raw diagnostics or stale data. Minimal mutation receipts SHALL remain minimal through replay and delegation.

#### Scenario: Malformed or mismatched KB response
- **WHEN** the fixed upstream returns unknown fields, another collection identity or an oversized protected response
- **THEN** no protected payload reaches the browser and the finite failure is noncacheable

### Requirement: Separate bounded KB gateway
New fixed KB routes SHALL use the candidate9-second whole gateway deadline from request-budgets.md, with no retries, while existing DM metadata3-second behavior remains unchanged. It SHALL not extend the propagated remaining8-second KB budget. Timeout SHALL return finite503 without claiming an already-dispatched domain command did not commit.

#### Scenario: KB timeout after possible commit
- **WHEN** the new gateway times out after an upstream command may have committed
- **THEN** it returns no fabricated receipt and later original-key retry rechecks current authority and durable outcome
