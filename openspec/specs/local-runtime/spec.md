# local-runtime Specification

## Purpose
Allow contributors to run the implemented foundation locally while preserving independent deployments and safe environment boundaries.

## Requirements

### Requirement: Independent containers
Each service and the portal SHALL have an independent container build using pinned image digests and non-root runtime users. Building a component SHALL NOT require starting or packaging sibling services.

#### Scenario: Build and start a service
- **WHEN** a contributor builds one service image and starts it in isolation
- **THEN** its process runs without root privileges, probes report process lifecycle accurately and protected routes fail closed unless the required trust configuration is supplied

### Requirement: Minimal local composition
Docker Compose SHALL start the three domain APIs, browser BFF, HTTPS portal, pinned Keycloak and the used DM PostgreSQL database with loopback-bound published ports and health checks. It SHALL NOT start unused brokers, object stores, model runtimes or workers.

#### Scenario: Start the foundation
- **WHEN** documented bootstrap and Compose startup complete
- **THEN** the synthetic user completes a real authenticated draft journey, migrations and scoped runtime DML work and restart preserves DM state without permissive tenant rules

### Requirement: Local persistence and session limits
The BFF SHALL remain a single ephemeral-session replica with its BP-16 limit documented. PostgreSQL SHALL persist to an owned volume with separate generated migration/runtime credentials. Local grant material SHALL be synthetic, ignored and mounted read-only. RustFS SHALL first arrive in BP-03, not this composition.

#### Scenario: Inspect local trust and data boundaries
- **WHEN** a contributor starts the implemented local composition
- **THEN** the BFF has one ephemeral-session replica, DM data persists with separate generated roles and synthetic grant material is ignored and read-only
- **AND** object storage remains absent until BP-03 introduces RustFS

### Requirement: Environment isolation
The repository SHALL document local, test and production boundaries and fail closed on missing trust settings. A bootstrap script SHALL generate local CA/certificates, client secrets and development passwords under an ignored directory with restrictive permissions and SHALL NOT alter machine-global trust. Java and isolated browser tests SHALL explicitly trust the development CA. Development SHALL NOT weaken tenant or authorization controls.

#### Scenario: Inspect shipped configuration
- **WHEN** a contributor reviews tracked environment and infrastructure files
- **THEN** no real credentials, production endpoints, permissive security profiles or production deployment resources are present
- **AND** Kubernetes/GitOps SHALL contain structure and guidance only, with no speculative production manifests or deployment claims

#### Scenario: Generate local identity material
- **WHEN** a contributor runs the documented local security bootstrap
- **THEN** credentials, CA keys and certificates exist only under ignored runtime paths, no secret is printed or tracked, and no global trust store changes
