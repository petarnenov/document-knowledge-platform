## ADDED Requirements

### Requirement: Explicit collection ownership and lifecycle
KB SHALL own tenant-scoped collections, administrative ACLs, audience policies and many-to-many document memberships. IDs, owner tenant, creator, timestamps and revisions SHALL be server-derived. Collection status SHALL be ACTIVE or ARCHIVED; archive SHALL hide effective visibility without deleting history. KB SHALL NOT write DM ACLs, document versions, classification or publication pointers.

#### Scenario: Draft membership does not publish
- **WHEN** an authorized administrator associates an existing DM draft with an active collection
- **THEN** only KB membership/revision/audit facts change and DM activeVersionId remains unchanged

#### Scenario: Cross-tenant resource supplied
- **WHEN** a caller selects a collection or document outside its verified selected owner tenant
- **THEN** the response is indistinguishable404 without cross-tenant association or facts

### Requirement: Current conjunctive administrative authority
KB SHALL independently validate BP-01 identity and tenant membership, current exact catalog action and collection ACL ALLOW without DENY. Missing/malformed/unsafe catalog SHALL return503 with no stale positive decision. Ownership, creation, collection membership and ordinary document.read SHALL NOT supply an administrative action. Create SHALL seed only current catalog-authorized creator actions; unknown final authority SHALL abort atomically.

#### Scenario: Revoked grant at final boundary
- **WHEN** a catalog or ACL grant is revoked after initial validation but before commit or protected response
- **THEN** the mutation rolls back or protected facts are withheld using fresh403/404, with no stored positive fallback

#### Scenario: Missing catalog is not known denial
- **WHEN** the configured current authority file or a trusted ancestor is unavailable, malformed or unsafe
- **THEN**503 occurs before resource lookup rather than using an old catalog or treating uncertainty as permission

### Requirement: Bounded explicit administrative ACL deltas
ACL PATCH SHALL preserve unrelated exact rules, reject duplicate principal/action changes and enforce a100-rule final cap atomically. RESTRICT SHALL allow caller REMOVE_ALLOW/ADD_DENY only. DELEGATE SHALL require caller ceiling AND target current same-tenant action grant for all four operations. DENY SHALL remain until explicit authorized removal. No-op commands SHALL still commit revision/audit and minimal receipt.

#### Scenario: Self-revocation and replay
- **WHEN** a permitted restriction removes its own operation authority
- **THEN** initial response contains only its committed receipt and later replay fails404 under current original-action authorization

#### Scenario: Delegated action exceeds ceiling
- **WHEN** any delta exceeds caller ceiling or target current grant
- **THEN**403 DELEGATION_CEILING or422 TARGET_NOT_GRANTABLE respectively occurs with no partial rule, revision or audit mutation

### Requirement: Executable audience constraints without access expansion
Audience rules SHALL support exact PRINCIPAL, TENANT, FIRM and RESOURCE_SCOPE selectors with ALLOW and DENY. Current verified actor/tenant and admin-provisioned relation facts SHALL determine matching; caller selector fields SHALL confer no relations. Empty ALLOW SHALL deny and any matching DENY SHALL win. Audience allowance SHALL remain ANDed with current DM ACL, tenant entitlements, classification and eventual publication/evidence checks.

#### Scenario: Firm claim is supplied by browser
- **WHEN** an actor supplies a firm, group or resource-scope claim outside the configured current authority
- **THEN** it confers no relation or access, including when a policy permits that selector

#### Scenario: Audience update races a member denial
- **WHEN** any affected active member loses independent DM sharing authority or its authority becomes unavailable before final policy commit
- **THEN** the entire audience edit returns404 or503 without changing the previous policy or member visibility

### Requirement: Explicit membership commands and finite limits
Create/reactivate/inactivate SHALL require knowledge.membership.manage catalog+collection ACL AND current document.share-to-collection catalog+DM ACL. Unique tenant+collection+document identity SHALL be preserved on reactivation. Commands SHALL require current aggregate revisions, idempotency and change reason. Caps SHALL be50 memberships per document and100 per collection including inactive; overflow SHALL fail422 without partial mutation.

#### Scenario: Concurrent duplicate membership
- **WHEN** two authorized commands attempt the same tenant/collection/document membership concurrently
- **THEN** one identity exists, command outcomes are deterministic and no revision/audit duplication or audience widening occurs

### Requirement: Confidential bounded inspection and replay
Collection inspection SHALL require knowledge.collection.inspect catalog+ACL; each returned membership SHALL additionally pass current DM sharing authority. ACL filtering SHALL precede LIMIT for owned rows. Hidden rows SHALL NOT determine exposed counts, cursors or page sizes; bounded authority timeout SHALL fail503 rather than expose partial scan state.

#### Scenario: Inaccessible member is inside a page
- **WHEN** local members include a DM-inaccessible or cross-tenant document
- **THEN** no ID, count, hidden hasMore or cursor derived from that member is emitted and unavailable required authority fails the whole response503

### Requirement: Scoped cursors and current replay authority
Cursors SHALL bind actor, tenant, operation, order and relevant revisions. Replays SHALL check current original authority before stored success or conflicts.

#### Scenario: Wrong-tenant cursor or revoked replay
- **WHEN** a cursor is replayed in another selected tenant or an original command grant is revoked
- **THEN** cursor reuse fails400 and command replay fails403/404 without old protected facts

### Requirement: Fully assessed collection activation
ARCHIVED→ACTIVE and new-key ACTIVE no-op SHALL assess EVERY current ACTIVE member's DM sharing authority/classification at initial/final boundaries without hidden filtering. Final local ACL, complete-set and revision checks SHALL pass. Denial404, uncertainty503 or drift409 SHALL leave all facts unchanged. Committed ACTIVE-command replay SHALL recheck the CURRENT set and return only its original receipt without reactivation or new facts.

#### Scenario: Archived member loses sharing grant
- **WHEN** reactivation would expose an active member whose DM sharing action is revoked or classification unresolved
- **THEN**404 or503 occurs without status/revision/audit/outbox changes

#### Scenario: Reactivation replay and no-op
- **WHEN** a committed ACTIVE command is replayed after current member denial or outage
- **THEN**404 or503 replaces stored success; a separately authorized new-key ACTIVE no-op advances revision/audit while exact authorized replay does not

#### Scenario: Concurrent active member addition
- **WHEN** an ACTIVE command races an additional member or audience/status change
- **THEN** final current authority precedes409 and no old assessed set is applied to the changed collection

### Requirement: Complete eligibility before pagination
Member lists SHALL assess all<=100 local candidates through distinct initial/final eligibility calls before filtering and ordering eligible memberships and applying50LIMIT. Opaque server-private cursors SHALL bind full candidate/eligible-revision digests, local fence and lastVISIBLE membership. Every page SHALL repeat full eligibility; changed view SHALL fail409 after current local authority. Strict all-or-nothing command/snapshot assessment SHALL remain unchanged.

#### Scenario: Hidden members surround page boundary
- **WHEN**100 candidates contain51 authorized members with hidden rows before and between them
- **THEN** the first page returns50 authorized members and continuation from the last visible member, and the second returns only the remaining authorized member without hidden counts

#### Scenario: Visibility changes between phases or pages
- **WHEN** current candidate or eligible revision/classification view differs from initial assessment or private cursor binding
- **THEN**409 STALE_POLICY replaces the page after current collection authority, with no stale cached subset or partial response
