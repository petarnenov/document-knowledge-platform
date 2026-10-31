## MODIFIED Requirements

### Requirement: Privileged draft inspection
Development candidate actions SHALL be document.create, document.inspect-draft, document.read, document.update-metadata, document.acl.restrict, document.acl.delegate, document.version.append, document.legal-hold.place, document.legal-hold.inspect and document.content.review. Draft metadata/ACL SHALL require inspect-draft; hold inspection legal-hold.inspect; original content content.review. Ordinary read SHALL reveal no draft facts; CID07 stays open.

#### Scenario: Separate draft and original privilege
- **WHEN** an actor is granted only metadata inspection or ordinary read
- **THEN** no original bytes/content.review permission is inferred

#### Scenario: Explicit content review delegation
- **WHEN** a delegate adds content.review for a target
- **THEN** current caller ceiling and exact target same-tenant action grant are both required; no implicit grant

#### Scenario: Published read is not draft review
- **WHEN** an actor has document.read but no privileged draft-inspection permission
- **THEN** draft detail is indistinguishable404 and list/ACL responses reveal no draft facts or original bytes

## ADDED Requirements

### Requirement: Additive content review action contract

Public ACL request/response Action and private catalog actions/delegationCeiling SHALL add exactly document.content.review to existing9 strings. Other BP02 schema shapes, bounds, errors, statuses and minimal receipts SHALL remain unchanged. DM enum, generated Java/TS clients, Zod and catalog SHALL reproduce this exact vocabulary; unknown/wildcard/admin values SHALL reject. Bootstrap/delegation SHALL remain explicit current-grant and target/ceiling constrained.

#### Scenario: New action and old clients remain coherent
- **WHEN** old9-action ACL/catalog payloads or explicitly granted content.review ACL changes round-trip
- **THEN** old payloads remain accepted and the new action appears consistently in request/response/SDK/Zod/catalog without implicit authorization
