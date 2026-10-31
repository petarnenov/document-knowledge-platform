# Document Authority

## ADDED Requirements

### Requirement: Explicit local operation authority
Standalone DM SHALL compose verified BP-01 tenant membership with a read-only admin-owned git-ignored catalog behind ResourceAuthorizationPort. Exact issuer/subject/tenant and enumerated actions SHALL be required; wildcard, generic admin and implicit owner permissions SHALL NOT authorize. External profile SHALL remain UNAVAILABLE until its independent authoritative adapter exists. Current default resource DENY SHALL NOT become permissive.

#### Scenario: Membership without an operation grant
- **WHEN** a valid tenant member lacks an exact catalog entry or action in a valid catalog
- **THEN** list/create/mutations are denied403 before lookup, while inspection GET detail/ACL/holds are resource-hidden404 before lookup, without disclosing existence

#### Scenario: Authority file unavailable or corrupt
- **WHEN** a catalog is missing, unreadable, malformed, oversized, schema-mismatched or unsafe
- **THEN** requests fail503 and no previous positive decision is reused

### Requirement: Privileged draft inspection
Development candidate actions SHALL be document.create, document.inspect-draft, document.read, document.update-metadata, document.acl.restrict, document.acl.delegate, document.version.append, document.legal-hold.place and document.legal-hold.inspect. Draft list/detail/ACL SHALL require inspect-draft catalog+ACL; hold inspection SHALL require legal-hold.inspect catalog+ACL. Ordinary read SHALL NOT reveal drafts; CID-07 remains open.

#### Scenario: Published read is not draft review
- **WHEN** an actor has document.read but no privileged draft-inspection permission
- **THEN** draft detail is indistinguishable404 and list/ACL responses reveal no draft facts

### Requirement: Explicit ACL bootstrap and deny precedence
Create SHALL require document.create ALLOW and atomically seed only creator catalog-authorized applicable ACL actions. Existing-resource commands SHALL require current catalog ALLOW AND DM ACL ALLOW, with denies winning and no implicit owner grant or stale fallback. Authorized self-restriction SHALL return only a committed receipt; future revoked-action replay SHALL fail404.

#### Scenario: Creation without automatic owner privilege
- **WHEN** an authorized actor creates a draft without metadata-update entitlement
- **THEN** the draft is created but creator ownership confers no metadata-update permission

#### Scenario: ACL deny overrides grants
- **WHEN** catalog and ACL contain an allow but a matching explicit deny also exists
- **THEN** the action is denied without protected resource data

### Requirement: Bounded ACL delta restriction and delegation
PATCH .../acl SHALL accept expectedRevision, mode and changes[1..100] with ADD_ALLOW, REMOVE_ALLOW, ADD_DENY or REMOVE_DENY. Duplicate principal/action keys SHALL fail422 DUPLICATE_ACL_CHANGE. Rules SHALL be exact principal/action/effect triples; unrelated rules SHALL remain. Existing ADD/absent REMOVE SHALL still increment revision/audit. Final rule count>100 SHALL fail422 ACL_RULE_LIMIT atomically.

#### Scenario: Duplicate change or oversized resulting ACL
- **WHEN** changes repeat a principal/action key or produce more than100 unique rules
- **THEN** response is422 DUPLICATE_ACL_CHANGE or ACL_RULE_LIMIT respectively and no partial mutation commits

#### Scenario: No-op delta still advances revision
- **WHEN** an authorized command removes an absent exact rule or adds an existing exact rule
- **THEN** only its receipt is returned and revision/audit/idempotency commit despite unchanged rule membership

#### Scenario: Delegation exceeds authority
- **WHEN** target lacks its exact same-tenant action grant or action exceeds caller delegation ceiling
- **THEN** response is422 TARGET_NOT_GRANTABLE or403 DELEGATION_CEILING respectively without mutation

#### Scenario: Deny remains until explicitly authorized removal
- **WHEN** a delegate adds ALLOW for a principal/action with existing DENY
- **THEN** DENY remains effective until a separately authorized REMOVE_DENY through delegation; unrelated rules remain unchanged

#### Scenario: Self-restriction followed by replay
- **WHEN** caller removes its own acl.restrict ALLOW and retries its committed key/body
- **THEN** initial command commits with minimal receipt and replay fails404 on current ACL rather than returning stored success

#### Scenario: Restriction attempts another principal or deny removal
- **WHEN** RESTRICT targets another actor or uses ADD_ALLOW or REMOVE_DENY
- **THEN** response is422 RESTRICT_SCOPE with no partial changes

#### Scenario: Grant is revoked during request
- **WHEN** catalog or ACL permission is revoked before the final authoritative check
- **THEN** protected inspection facts are excluded and no prior allow is reused; an authorized self-restriction returns only its non-inspection receipt

#### Scenario: Inspection and command denial order
- **WHEN** an actor lacks the route operation for existing or nonexistent document IDs
- **THEN** GET detail/ACL/holds returns identical404 before lookup, while list/create/mutations return403 before lookup; missing/malformed catalog returns503 first

### Requirement: Fixed route authorization order
BP-01 session/membership SHALL precede catalog load; unavailable catalog SHALL return503. Missing route action SHALL return403 for list/create/mutations or404 for inspection GET detail/ACL/holds, before lookup. Missing document, absent ACL ALLOW or explicit DENY SHALL return identical404. Stale revision409 SHALL occur only after these checks. Missing catalog entry SHALL be DENY, never a fallback allow.

#### Scenario: Resource-hidden inspection route
- **WHEN** inspection permission is absent for either an existing or nonexistent ID
- **THEN**404 precedes lookup and mutation missing-action denial remains403

### Requirement: Self-only restriction delta
RESTRICT SHALL require document.acl.restrict catalog+ACL ALLOW without DENY. Each entry SHALL target caller and use REMOVE_ALLOW or ADD_DENY only; otherwise422 RESTRICT_SCOPE. It SHALL NOT touch another principal or remove a DENY.

#### Scenario: Restriction authority scope
- **WHEN** restriction targets another principal or attempts deny removal
- **THEN**422 RESTRICT_SCOPE occurs without mutation

### Requirement: Ceiling-constrained delegation delta
DELEGATE SHALL require document.acl.delegate catalog+ACL ALLOW without DENY. All four operations SHALL require target same-tenant exact action grant, else422 TARGET_NOT_GRANTABLE, and caller explicit ceiling, else403 DELEGATION_CEILING. Caller may be target. ADD_ALLOW SHALL NOT clear DENY; REMOVE_DENY and third-party REMOVE_ALLOW/ADD_DENY SHALL be permitted only through this constrained path. Wildcard/group/other-tenant principals SHALL NOT be accepted.

#### Scenario: Bounded deny restoration
- **WHEN** an authorized delegate removes a DENY within its ceiling for a granted same-tenant target
- **THEN** only that exact rule is removed and unrelated rules remain
