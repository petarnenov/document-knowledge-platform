## MODIFIED Requirements

### Requirement: Immutable verified-artifact versions
A version append SHALL require a real tenant/document/actor-scoped immutable RustFS verification receipt and atomic receipt consumption. Browser hash/MIME/object pointers SHALL NOT establish verification. Versions SHALL be immutable, uniquely numbered and preserve the active pointer, submitted classification and initial UNREVIEWED state. Test-only receipts SHALL NOT be runtime adapters. Missing dependency/evidence SHALL fail closed.

#### Scenario: Verified real receipt append
- **WHEN** an authorized actor appends an unexpired real receipt for the same document
- **THEN** version+consumption+revision+command+audit commit atomically with201 CommandReceipt and unchanged active pointer

#### Scenario: Unsafe artifact authority
- **WHEN** a caller supplies fake/wrong-scope/reused receipt or storage authority
- **THEN** no version is fabricated; unknown/wrong scope404, consumed409 or dependency503 follows current authorization

#### Scenario: Public append before BP-03
- **WHEN** verified-artifact runtime authority is not configured or its real evidence dependencies are unavailable
- **THEN** otherwise authorized append returns503 and never fabricates upload or version success

#### Scenario: Verified test fixture append
- **WHEN** repository tests append an explicitly verified synthetic artifact receipt through test-only authority
- **THEN** version facts remain immutable, numbering unique, ownership enforced and activeVersionId unchanged; no test binding enters runtime

### Requirement: Future append receipt boundary
BP03 successful explicit version append201 SHALL return exactly CommandReceipt{documentId,revision}. Receipt verification SHALL be real and single-consumption in the same DM transaction. Missing RustFS/scanner/inspection/evidence SHALL remain unavailable. BP05 SHALL separately review legacy append compatibility while preserving final atomic processing202; BP03 SHALL not fabricate processing readiness.

#### Scenario: Storage verification unavailable
- **WHEN** authorized append cannot establish immutable verified original evidence
- **THEN** 503 is returned and no version or success outcome commits

#### Scenario: Command without inspection privilege
- **WHEN** an actor retains append action+ACL but lacks draft inspection
- **THEN** successful append/replay exposes only its minimal CommandReceipt

#### Scenario: Future schema without fake readiness
- **WHEN** an authorized append cannot establish the real receipt authority required by BP03
- **THEN**503 remains the fail-closed result despite the documented201 schema and no version persists

## ADDED Requirements

### Requirement: Hold placement respects unresolved deletion fence

Existing placeHold wire DTO/operationId/201 CommandReceipt SHALL remain unchanged. After existing current authorization and committed-command replay, new hold SHALL share document UoW with fence check. Unresolved deletion SHALL yield503 UNAVAILABLE and no hold/revision/success outcome. Authorized prior201 replay SHALL remain intact. Indefinite503 under unprovable remote settlement SHALL be explicit development availability cost, not legal-policy acceptance.

#### Scenario: Authorized hold replay during unknown cleanup
- **WHEN** a previously successful hold command is replayed with current original authority while cleanup has unresolved deletion
- **THEN** its original201 minimal receipt is returned without duplicate hold; a new hold command503 cannot imply hold placement
