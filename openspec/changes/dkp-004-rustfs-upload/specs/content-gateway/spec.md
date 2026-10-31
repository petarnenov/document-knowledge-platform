## ADDED Requirements

### Requirement: Distinct privileged exact-version review
Original review SHALL require explicit document.content.review current catalog AND DM ACL, verified issuer/actor/membership and exact same-document version. Neither document.read, inspect-draft, ownership nor generic admin SHALL imply this permission. New action bootstrap/delegation SHALL remain explicit and ceiling/target constrained. Ordinary published/citation/preview access SHALL remain unavailable until its reviewed package.

#### Scenario: Metadata permission without content action
- **WHEN** an actor has inspect-draft or document.read but lacks content.review
- **THEN** content GET returns resource-hidden404 before lookup

#### Scenario: Exact version and revocation
- **WHEN** an actor guesses another document version or loses grant/ACL before transmission
- **THEN** no bytes or storage identity are disclosed; current denial or unavailable authority fails closed

### Requirement: Safe bounded original stream and ranges
Gateway SHALL resolve immutable verified originals server-side, verify full source hash before delivery, and return canonical MIME attachment with server UUID filename/no-store/nosniff. It SHALL expose no S3/signed/permanent URLs, redirects or active inline rendering.

Full200 or one validated bytes range206 SHALL be independently authorized immediately before first byte; unsupported/multiple/unsatisfiable ranges416 only after authorization.

#### Scenario: Range byte parity
- **WHEN** an authorized actor requests a valid full, bounded, open-ended or suffix range
- **THEN** response has exact bytes/length and206 Content-Range where applicable, without exceeding100000000bytes

#### Scenario: Source substitution or unsafe range
- **WHEN** object version/hash mismatches or a malformed/multiple range is requested
- **THEN** integrity uncertainty503 or authorized invalid range416 occurs before any content body

### Requirement: Bounded content resources and current policy
At most2 global streams and1 per actor SHALL reserve at most200000000 private temporary bytes. Delivery SHALL use<=65536byte chunks,300s total/30s idle deadlines and cleanup after disconnect. Every new full/range request SHALL recheck current catalog/ACL/membership; no stale positive cache. Narrow post-final-check network race SHALL be explicit; downloaded bytes cannot be revoked.

#### Scenario: Revoked range request
- **WHEN** a previously permitted actor makes a new range request after revocation
- **THEN** prior success or cached headers cannot authorize bytes

#### Scenario: Gateway capacity unavailable
- **WHEN** stream/disk bound is exhausted
- **THEN** 429 occurs without unbounded buffering or leaked temporary content

### Requirement: Exact consumed-receipt version discovery

Actor-owned receipt version GET SHALL return only{documentId,versionId} for one immutable consumed association, max1024bytes/no-store. Current content.review catalog AND ACL SHALL be required, with missing action404 before lookup and unavailablecatalog503. Unknown/unconsumed/wrong actor/issuer/tenant/document404; final policy/binding SHALL be rechecked. No list/count/metadata/storage facts SHALL leak. Consumed mapping expiry SHALL not permit receipt reuse.

#### Scenario: Browser discovers its appended version
- **WHEN** an authorized review actor appends its verified receipt and resolves the known receipt through BFF
- **THEN** it receives only the bound document/version IDs and may independently request authorized exact-version content; mutation/status bodies remain unchanged

#### Scenario: Revoked or wrong scoped discovery
- **WHEN** the receipt is unconsumed/wrong-scope or review authority/ACL is missing or revoked before response
- **THEN** no version ID is disclosed; hidden404 or unavailable503 applies without protected counts or cached success
