# DM Persistence

## ADDED Requirements

### Requirement: Owned PostgreSQL migrations and least privilege
DM SHALL own one logical PostgreSQL database/schema with separately qualified migration and runtime roles. Runtime SHALL have necessary DML only, no DDL/public/cross-database privileges and no immutable-version/audit update/delete. Flyway migration qualifier SHALL differ from runtime default datasource to avoid duplicate hooks. Pinned versions/digest SHALL match design; durability evidence SHALL use fsync=on.

#### Scenario: Migration and runtime privilege verification
- **WHEN** real PostgreSQL starts, migrations run/reapply and runtime attempts schema changes or sibling access
- **THEN** migrations are checksum-consistent and single-applied while runtime DDL and unauthorized ownership operations fail

### Requirement: Atomic CAS idempotency and audit
Prepared SQL SHALL scope tenant+document+expected revision. Aggregate/ACL/retention/hold/version mutation, revision, idempotency outcome and append-only audit facts SHALL commit atomically. Conflicting expected revision/body/key reuse SHALL return409 after authorized visibility check. Concurrent identical keys SHALL produce one committed outcome. Transaction failures SHALL leave no partial success.

#### Scenario: Concurrent compare and swap
- **WHEN** two authorized commands use the same expected revision with distinct keys
- **THEN** one succeeds, one409 and only the successful transaction's complete facts persist

#### Scenario: Crash or conflicting idempotency payload
- **WHEN** a transaction fails before commit or an existing key is reused with different command semantics
- **THEN** no partial mutation exists and conflicting replay returns409

### Requirement: Immutable relational ownership constraints
Migrations SHALL enforce document-version ordinal uniqueness and same-document active pointer, tenant-owned references and valid revisions. Real PostgreSQL tests SHALL verify persistence over restart and immutable version constraints, not substitute an in-memory database.

#### Scenario: Cross-document pointer or duplicate version
- **WHEN** a transaction attempts a pointer to another document's version or duplicate ordinal
- **THEN** the database rejects it without corrupting either aggregate
