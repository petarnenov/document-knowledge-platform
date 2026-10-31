# local-runtime Specification

## Purpose
Allow contributors to run the implemented foundation locally while preserving independent deployments and safe environment boundaries.

## Requirements

### Requirement: Independent containers
Each service and the portal SHALL have an independent container build using pinned image digests and non-root runtime users. Building a component SHALL NOT require starting or packaging sibling services.

#### Scenario: Build and start a service
- **WHEN** a contributor builds one service image and starts it in isolation
- **THEN** its probes become healthy without external infrastructure and its process runs without root privileges

### Requirement: Minimal local composition
Docker Compose SHALL start the three scaffold services and portal with loopback-bound host ports and health checks. It SHALL NOT start unused databases, brokers, model runtimes or workers. This is a foundation subset of the eventual full development topology.

#### Scenario: Start the foundation
- **WHEN** the documented Compose startup command completes
- **THEN** the portal and each service probe are reachable on the documented local ports without any credentials or business data

### Requirement: Environment isolation
The repository SHALL document local, test and production configuration boundaries, external secret injection and fail-fast handling for future required settings. Development SHALL NOT weaken tenant or authorization controls. Kubernetes/GitOps SHALL contain structure and guidance only, with no speculative production manifests or deployment claims.

#### Scenario: Inspect shipped configuration
- **WHEN** a contributor reviews tracked environment and infrastructure files
- **THEN** no real credentials, production endpoints, permissive security profiles or production deployment resources are present
