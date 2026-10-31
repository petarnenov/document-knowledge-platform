# Service Foundation

## Purpose

Provide independently executable service foundations whose dependency boundaries and operational behavior can be verified before business features exist.

## ADDED Requirements

### Requirement: Independent service lifecycle
The foundation SHALL provide independently buildable, testable and containerizable Document Management, Knowledge Base and Knowledge Search services using Java 25 LTS and Micronaut, without requiring another domain's runtime or build outputs.

#### Scenario: Build a single domain
- **WHEN** a contributor invokes one service's documented clean build and test command
- **THEN** its runnable artifact and test results are produced without building sibling services or requiring external data stores

### Requirement: Enforced inward dependencies
Each service SHALL separate domain, application/ports, inbound adapters, outbound adapters and bootstrap. Domain SHALL be framework independent; application SHALL depend only on domain and abstract contracts; adapters SHALL depend inward. Cross-service implementation dependencies and shared mutable domain or persistence models SHALL be forbidden.

#### Scenario: Forbidden coupling is introduced
- **WHEN** a domain references a framework or an application references an adapter, or one service references another service's implementation
- **THEN** architecture verification fails with the violating dependency

#### Scenario: Valid layers remain buildable
- **WHEN** service modules follow the declared inward dependency graph
- **THEN** architecture verification passes and bootstrap assembles the service

### Requirement: Minimal operational probes
Each service SHALL expose GET `/health/liveness` and GET `/health/readiness` returning only `{"status":"UP"}` with HTTP 200 when healthy. Readiness SHALL return only `{"status":"DOWN"}` with HTTP 503 when not accepting work. Probes SHALL be the only anonymously accessible operational endpoints and SHALL reveal no configuration, dependency details or tenant data.

#### Scenario: Healthy standalone scaffold
- **WHEN** a successfully started service receives either probe request
- **THEN** it returns HTTP 200 and exactly the minimal UP payload without infrastructure dependencies

#### Scenario: Service cannot accept work
- **WHEN** readiness is explicitly marked unavailable through a test-controlled lifecycle seam
- **THEN** readiness returns HTTP 503 with exactly the minimal DOWN payload while liveness remains HTTP 200

### Requirement: Fail-closed scaffold surface
The foundation SHALL contain no document, knowledge, search or administrative business endpoints, no authorization bypass and no data access adapters. Non-probe management endpoints SHALL be unavailable or protected. Caller-supplied tenant headers SHALL confer no authority.

#### Scenario: Unauthenticated request outside the probe surface
- **WHEN** an anonymous caller requests a business path or sensitive management path, including with a fabricated tenant header
- **THEN** no business behavior, sensitive management response or tenant data is returned, and the response is non-success
