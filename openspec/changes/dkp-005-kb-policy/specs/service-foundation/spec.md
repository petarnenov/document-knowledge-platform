## MODIFIED Requirements

### Requirement: Fail-closed scaffold surface
The foundation SHALL permit reviewed identity/session/security-context and BP-02 metadata-only DM draft/ACL/hold/version-boundary endpoints with DM-owned data adapters, and independently reviewed BP-04 KB collection/audience/membership/policy routes plus explicit DM share assessment with owning-domain adapters. It SHALL contain no unreviewed search/upload/content/publication/purge/knowledge behavior or authorization bypass. Non-probe management endpoints SHALL remain unavailable or protected. Caller-supplied tenant headers SHALL confer no authority.

#### Scenario: Request to unsupported capability
- **WHEN** a caller requests an unimplemented content, publication, purge, search or knowledge operation outside the reviewed BP-04 surface
- **THEN** no business success, protected data or permissive authority is fabricated

#### Scenario: Unauthenticated request outside the probe surface
- **WHEN** an anonymous caller requests a business path or sensitive management path, including with a fabricated tenant header
- **THEN** no business behavior, sensitive management response or tenant data is returned, and the response is non-success
