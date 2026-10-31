# DKP-003 — Document Management aggregates and authority

Status: Approved for development implementation after independent security/architecture re-review and strict validation; see approval.md. No production approval is granted. Specification task [#52](https://github.com/petarnenov/document-knowledge-platform/issues/52), parents #34/#50. Independent review and strict validation must precede any automatic specification approval. Runtime tasks require their own previously created prioritized issues.

## Why

BP-02 must establish DM-owned persisted aggregates, immutable version/CAS invariants and explicit document authority before verified upload or collection work can proceed. Tenant membership alone cannot authorize a document operation.

## What Changes

- Metadata-only DRAFT create, privileged inspect/list, metadata update, ACL restrict/delegate and legal-hold place/inspect, through authenticated DM and fixed BFF routes.
- Option B: bounded, admin-owned, git-ignored read-only local grant catalog implementing the existing resource authorization port; exact actor/tenant/action plus DM-owned ACL, explicit deny wins.
- One development-restricted classification revision, unresolved retention with null deadlines and no destructive eligibility; separate legal holds.
- Owned PostgreSQL migrations, runtime DML-only role, CAS, atomic idempotency/audit facts and immutable verified-artifact version model. Public append remains unavailable until BP-03 provides verified receipts.
- Frozen draft OpenAPI under this change; no runtime or SDK changes in this specification task.

## Capabilities

### New Capabilities

- `document-management`: Draft aggregates, metadata, immutable versions, ACL and retention state.
- `document-authority`: Explicit local operation grants composed with DM-owned ACLs.
- `dm-persistence`: Owned relational transactions, migrations, CAS, idempotency and audit facts.

### Modified Capabilities

- `browser-bff`: Fixed DM delegation with existing session/Origin/CSRF protections.
- `portal-shell`: Reviewed draft controls with TanStack Query server state and Zod validation.
- `local-runtime`: Only used DM PostgreSQL dependency and isolated generated authority material.
- `service-foundation`: Permit reviewed DM draft APIs while retaining all other fail-closed boundaries.

## Impact and completion boundary

BP-02 done means the approved metadata-only draft, ACL/retention state, immutable version invariants, real owned migrations, catalog and ≥75% changed-source tests plus real authenticated BFF happy-path E2E are independently reviewed, checked and merged into dev. Full project remains active; BP-03 follows.

Not complete here: uploads/scanning/content delivery (BP-03), publication (BP-13), purge/hold release (BP-15), final CID-07 matrix or CID-15 legal durations, production/security sign-off. Owner selected **RustFS** as the first object store; BP-03/#35 implements it under tracking #53/#54. BP-02 merely defines a future RustFS-backed verified-artifact authority port and starts no object-store runtime.

Adjudicated addendum `/Users/petarnenov/.config/away/runs/20261031T163021Z-claude-62291` selects ninth candidate action `document.inspect-draft`; ordinary `document.read` never authorizes drafts. Independent specification re-reviews #56/#57 passed; development approval is recorded in approval.md.
