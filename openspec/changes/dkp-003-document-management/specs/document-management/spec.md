# Document Management

## ADDED Requirements

### Requirement: Metadata-only draft aggregate
DM SHALL provide reviewed metadata-only DRAFT create/inspect/list/update and ACL/hold operations. Tenant, creator, IDs, timestamps and revision SHALL be server-derived; activeVersionId SHALL start null. Unknown/oversized fields SHALL be rejected and metadata treated as untrusted text. Fixed route authorization order SHALL apply before resource disclosure. No publication/upload/content success SHALL be fabricated.

#### Scenario: Authenticated draft lifecycle
- **WHEN** an explicitly authorized actor creates, inspects and updates a draft through the BFF
- **THEN** real DM persistence returns only authorized metadata, revision and unresolved retention state

#### Scenario: Tenant or identity spoof
- **WHEN** a caller submits a forged tenant/creator/storage authority or accesses another tenant's ID
- **THEN** input is rejected or protected facts remain unavailable without cross-tenant mutation

### Requirement: Restricted classification and unresolved retention
Classification SHALL be exactly development-restricted/revision1. Retention SHALL store development-unresolved/revision1 with null computed deadline and destructive eligibility false. Active legal holds SHALL independently block destruction. No production classification, clearance inference or legal retention duration SHALL be claimed; CID-07/CID-15 SHALL remain open.

#### Scenario: Unresolved retention with a hold
- **WHEN** an authorized actor places and inspects a legal hold
- **THEN** DM stores immutable hold facts and destruction remains forbidden without inventing an expiry

### Requirement: Immutable verified-artifact versions
A version append SHALL require tenant/document-scoped VerifiedArtifactAuthorityPort receipt verification. Runtime authority SHALL return UNAVAILABLE until BP-03, whose first object store SHALL be RustFS. Browser hash/MIME/object pointers SHALL NOT establish verification. Versions SHALL be immutable, uniquely numbered within a document and preserve the active pointer; test-only verified receipts SHALL NOT be runtime adapters.

#### Scenario: Public append before BP-03
- **WHEN** an otherwise authorized caller appends with an opaque receipt identifier
- **THEN** the response is503 and no version or upload success is fabricated

#### Scenario: Verified test fixture append
- **WHEN** repository tests append an explicitly verified synthetic artifact receipt
- **THEN** version facts are immutable, numbering unique, document ownership enforced and activeVersionId unchanged

### Requirement: Policy-safe list and replay
List SHALL be bounded, bind authenticated cursors to actor/tenant/order and reveal no unauthorized rows/counts/pages. Replay SHALL recheck current membership, original-operation catalog grant and current original-action ACL (create catalog only), never inspection authority, before returning stored immutable status/CommandReceipt. Current revocation SHALL yield fresh403/404; self-revocation replay SHALL yield404.

#### Scenario: Hidden rows or revoked replay
- **WHEN** list candidates include invisible documents or a prior successful actor loses permission
- **THEN** hidden facts are not counted/exposed and replay cannot reveal the revoked response

### Requirement: Non-disclosing command and replay receipts
All five mutation families SHALL return no success body field beyond documentId/revision, including replay. Create SHALL return201, metadata PATCH/ACL PATCH200 and hold place201. Commands SHALL NOT reveal metadata, creator, timestamps, lifecycle, ACL rules, retention or holdId to actors without inspection authority. Existing-resource command success SHALL not require inspect-draft solely to return a receipt.

#### Scenario: Mutate without inspection authority
- **WHEN** an actor with current action+ACL but no inspection permission updates metadata, changes ACL or places hold
- **THEN** success and permitted replay return only documentId/revision and never inspection facts

#### Scenario: Inspection revoked after first command
- **WHEN** an actor retains mutation authority but loses inspection permission before replay
- **THEN** replay may return only its original receipt, while inspection GET remains404

### Requirement: Future append receipt boundary
Successful future version append201 SHALL use exactly CommandReceipt {documentId,revision}. BP-02 runtime SHALL instead return503 UNAVAILABLE after authorization until BP-03 verified RustFS receipt authority exists. Public receipt schemas SHALL NOT imply that integration is implemented.

#### Scenario: Future schema without fake readiness
- **WHEN** BP-02 authorized version append reaches unavailable artifact authority
- **THEN**503 is returned despite the future201 contract shape and no version is persisted
