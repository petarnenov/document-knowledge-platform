# Local Runtime

## MODIFIED Requirements

### Requirement: Minimal local composition
Docker Compose SHALL start the three domain APIs, browser BFF, HTTPS portal, pinned Keycloak and the used DM PostgreSQL database with loopback-bound published ports and health checks. It SHALL NOT start unused brokers, object stores, model runtimes or workers. The BFF SHALL remain a single ephemeral-session replica with its BP-16 limit documented. PostgreSQL SHALL persist to an owned volume with separate generated migration/runtime credentials. Local grant material SHALL be synthetic, ignored and mounted read-only. RustFS SHALL first arrive in BP-03, not this composition.

#### Scenario: Start the foundation
- **WHEN** documented bootstrap and Compose startup complete
- **THEN** the synthetic user completes a real authenticated draft journey, migrations and scoped runtime DML work and restart preserves DM state without permissive tenant rules
