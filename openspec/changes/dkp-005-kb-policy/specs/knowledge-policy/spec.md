## ADDED Requirements

### Requirement: Independent DM sharing assessment
DM SHALL assess1..100 distinct same-tenant document UUIDs in one actor-authenticated all-or-nothing batch under explicit document.share-to-collection catalog+ACL permission. It SHALL independently bind dkp-dm issuer/actor/tenant/action, hide any denied/missing item404 before conflicts, and return only the exact bound complete metadata set. Scalar inspection/read/owner permissions SHALL NOT substitute; partial success SHALL NOT exist.

#### Scenario: Collection administrator lacks DM authority
- **WHEN** a valid KB administrator without document.share-to-collection attempts a membership mutation or policy snapshot
- **THEN** current DM denial causes404 with no metadata, membership or snapshot success

### Requirement: Fixed fail-closed inter-service adapter
KB SHALL call the fixed HTTPS DM batch route with original actor token and selected tenant, no redirects or arbitrary identity headers. Closed response<=32KiB SHALL match exact actor/tenant/action and complete requested set. Initial deadline SHALL be2s; final DM<=1.5s within the SAME2s final phase. Timeout/malformed/partial/mismatched authority SHALL fail503 without positive caching or scalar fallback.

#### Scenario: Upstream authority changes or redirects
- **WHEN** the fixed DM endpoint redirects, times out, returns mismatched data or becomes unavailable
- **THEN** no alternate host is contacted and no cached success, membership mutation or protected payload is returned

### Requirement: Complete actor-bound visibility snapshots
KB SHALL maintain monotonic membership/policy revisions per affected scope and a document membership-set revision covering additions/removals, including an empty set. Snapshot construction SHALL include the complete current set, with tenant revision fence and current DM revision/classification.

#### Scenario: Membership added after empty-set snapshot
- **WHEN** a snapshot of no memberships is followed by an authorized membership addition
- **THEN** validating the earlier token returns409 STALE_POLICY instead of treating an incomplete set as current

#### Scenario: Another issuer or tenant replays token
- **WHEN** a caller presents another actor/issuer/tenant/document snapshot reference
- **THEN**404 is indistinguishable from unknown token and reveals no collection IDs, counts or prior decision

### Requirement: Final current policy validation
Validation SHALL recheck current BP-01 membership, policy.evaluate grant, original actor/document binding, fresh DM sharing authority and every current scope/fence revision. Known drift SHALL return409 only after current authorization. Current DENY SHALL return resource-hidden404 and dependency uncertainty503, never an old COMPATIBLE response. Future consumers SHALL additionally enforce their own publication, ACL, classification and evidence requirements at final response boundary.

#### Scenario: Denial or outage after snapshot creation
- **WHEN** authority is revoked or unavailable before validation
- **THEN**404 or503 respectively replaces the old success and no stale decision is returned

#### Scenario: Concurrent independent databases
- **WHEN** KB policy changes after DM's final read or DM ACL changes after KB's final read
- **THEN** each owning service enforces its current authority, known drift invalidates snapshots, and no cross-database atomicity or eliminated transmission race is claimed

### Requirement: Explicit development and workload boundaries
Development classification SHALL remain development-restricted/revision1. Unknown classification/clearance or unavailable relation authority SHALL fail503. Formal CID-07 taxonomy, cross-tenant sharing and production acceptance SHALL remain explicit review gates. Future workload validation SHALL require actual independently verified workload authentication; no forged SPIFFE headers, browser internal flag or default ALLOW SHALL enable it.

#### Scenario: Unimplemented publication consumer
- **WHEN** a caller requests workload publication authorization before a reviewed verified adapter exists
- **THEN** the capability remains unavailable without claiming BP-13 readiness

### Requirement: Non-authorizing private snapshot references
An opaque256-bit server reference SHALL bind exact actor issuer/subject, tenant and document without revealing hidden collections. Empty memberships SHALL be INCOMPATIBLE. Snapshot compatibility SHALL NOT authorize content, search or publication.

#### Scenario: Compatible token used as a content grant
- **WHEN** a caller presents a compatible snapshot without independent current DM content/publication authority
- **THEN** it grants no document content or publication success

### Requirement: Complete-set current collection ACL
Snapshot creation and validation SHALL require current knowledge.policy.evaluate catalog AND same-action collection ACL ALLOW without DENY on EVERY current scope, including inactive/archived memberships, at initial and final boundaries. Hidden scopes SHALL NOT be filtered. Any denied scope SHALL return uniform404 before stale409 or a decision; authority uncertainty SHALL return503.

#### Scenario: Global policy grant with collection denial
- **WHEN** the actor has global policy.evaluate and DM sharing authority but one current collection lacks ALLOW or has explicit DENY
- **THEN** the whole operation returns404 without a snapshot, prior decision or hidden-scope metadata

#### Scenario: Empty-to-hidden membership addition
- **WHEN** an initially empty set gains a collection the caller cannot evaluate before the final boundary
- **THEN** the final complete-set ACL check returns404 before stale409 and no compatible subset is substituted

#### Scenario: Late collection ACL revocation
- **WHEN** any referenced collection revokes policy.evaluate after the initial complete-set check
- **THEN** the final operation returns404 and no earlier COMPATIBLE result or new snapshot success is returned

### Requirement: Exact additive DM sharing contract
The frozen dm-sharing.openapi.json and dm-grant-catalog.schema.json SHALL add only document.share-to-collection to the original9 DM actions plus approved document.content.review. Existing ACL PATCH, four deltas, ceilings, target grants, DENY precedence, DTOs, operationIds and success statuses SHALL remain unchanged. The new action SHALL NOT derive from inspect/read/ownership.

#### Scenario: Sharing delegation cannot bypass old guards
- **WHEN** sharing ALLOW is added beside DENY, target lacks the sharing grant, or caller lacks its explicit delegation ceiling
- **THEN** DENY remains or the existing422/403 guard applies with no unrelated schema/permission change

### Requirement: Request-scoped coherent batch authority
A NEW batch authority port SHALL read one immutable validated current catalog for the complete bounded resource set and fresh-reread/reevaluate it at the final DM boundary. Scalar ResourceAuthorizationPort calls SHALL NOT pretend a coherent generation or lease. DENY404/UNAVAILABLE503 SHALL win; changed allowed fingerprint SHALL conflict409. External or unimplemented batch adapter SHALL remain503 even if a scalar adapter permits.

#### Scenario: Scalar port allows without batch capability
- **WHEN** scalar authorization permits but no reviewed authoritative batch adapter exists
- **THEN** batch assessment returns503 rather than100 scalar calls or implicit coherence

#### Scenario: Catalog changes during batch
- **WHEN** the final fresh catalog denies, is unavailable or has changed allowed fingerprint
- **THEN** the entire batch fails404,503 or409 respectively without partial rows or an authority lease

#### Scenario: Malformed or partial bound response
- **WHEN** DM returns duplicate, omitted or extra document IDs or another actor/tenant/action
- **THEN** KB returns503 without admitting its final transaction

### Requirement: Distinct current member-list eligibility
Member listing SHALL use a separately typed DM eligibility endpoint/port for the COMPLETE locally scoped candidate set<=100, with actor/tenant/fixed-action/requested-set-digest binding. Missing/foreign/ACL-denied candidates SHALL be omitted indistinguishably; unknown authority SHALL fail the WHOLE response503. Fresh whole-catalog initial/final reads and sorted DM-owned locks SHALL apply. Eligibility SHALL NOT authorize strict commands or snapshots.

#### Scenario: Mixed or all-hidden candidates
- **WHEN** the complete member set includes known denied/missing/foreign documents
- **THEN** only authorized metadata is returned, all-hidden yields an empty200 and no denied IDs/counts/reasons or partial-authority success exists

#### Scenario: Uncertain authority is not omission
- **WHEN** any required eligibility decision is unavailable or the response binding/subset is malformed
- **THEN**503 replaces the entire page rather than treating uncertainty as a hidden member
