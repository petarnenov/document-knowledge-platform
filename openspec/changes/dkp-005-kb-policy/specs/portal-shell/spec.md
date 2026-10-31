## ADDED Requirements

### Requirement: Real accessible KB administration
Portal SHALL provide Mantine collection list/detail/create/update/status, explicit ACL deltas, audience rules and membership create/activate/inactivate controls using generated DTO declarations, closed Zod validation and TanStack Query server state. Controls SHALL respect separate action affordances while APIs remain authoritative. Snapshot compatibility SHALL be labeled administrative policy evidence, never processing/publication readiness.

#### Scenario: Genuine collection and membership journey
- **WHEN** an authorized actor creates a collection, assigns a bounded audience and associates an authorized DM draft
- **THEN** real persisted server state and revision changes are displayed in both themes without fake documents, uploads or publication status

### Requirement: Tenant and revocation cache isolation
All KB queries SHALL bind session and selected tenant. Tenant switch/logout/session loss SHALL abort requests and clear all protected KB/DM facts; delayed prior responses SHALL NOT repopulate them. Revoked authority SHALL remove affected detail/member/policy state. Mutations SHALL invalidate only authorized GETs, not trigger forbidden inspection.

#### Scenario: Revocation while request is in flight
- **WHEN** a previously authorized response arrives after tenant switch or current404 revocation
- **THEN** no previous collection/member/audience data is rendered or retained as current authority
