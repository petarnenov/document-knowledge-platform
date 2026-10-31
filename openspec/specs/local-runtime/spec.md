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
Docker Compose SHALL start the three domain APIs, browser BFF, HTTPS portal and pinned Keycloak provider with loopback-bound published ports and health checks. It SHALL NOT start unused databases, brokers, model runtimes or workers. The BFF SHALL run as a single replica with ephemeral sessions; this limitation SHALL be documented as a BP-16 gate.

#### Scenario: Start the foundation
- **WHEN** the documented Compose startup command completes
- **THEN** the HTTPS portal and each service probe are reachable on documented local ports, and a synthetic development user can complete the real OIDC/session/tenant/context/logout journey using generated local credentials

### Requirement: Environment isolation
The repository SHALL document local, test and production boundaries and fail closed on missing trust settings. A bootstrap script SHALL generate local CA/certificates, client secrets and development passwords under an ignored directory with restrictive permissions and SHALL NOT alter machine-global trust. Java and isolated browser tests SHALL explicitly trust the development CA. Development SHALL NOT weaken tenant or authorization controls.

#### Scenario: Inspect shipped configuration
- **WHEN** a contributor reviews tracked environment and infrastructure files
- **THEN** no real credentials, production endpoints, permissive security profiles or production deployment resources are present
- **AND** Kubernetes/GitOps SHALL contain structure and guidance only, with no speculative production manifests or deployment claims

#### Scenario: Generate local identity material
- **WHEN** a contributor runs the documented local security bootstrap
- **THEN** credentials, CA keys and certificates exist only under ignored runtime paths, no secret is printed or tracked, and no global trust store changes
