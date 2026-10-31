# engineering-gates Specification

## Purpose
Make foundation changes reproducible and reviewable through automated source, architecture, contract and specification checks.

## Requirements

### Requirement: Reproducible version policy
The repository SHALL pin build tools, runtime dependencies and container references, commit dependency lockfiles and verify Gradle wrapper integrity. Floating versions SHALL be forbidden. Java SHALL use the maintained Java 25 LTS patch; other selections SHALL be maintained compatible stable releases, with sources and compatibility evidence recorded.

#### Scenario: Clean installation
- **WHEN** a contributor follows the documented setup from a clean checkout
- **THEN** frozen dependency resolution succeeds without changing lockfiles and the declared runtime/tool versions are reported

### Requirement: Source quality gates
CI SHALL run independent Java build/tests, architecture verification, frontend lint, formatting, strict type checking, build, unit/component tests and browser happy paths. Automated source coverage SHALL be at least 75% separately for each service's implemented source and the frontend. Empty modules SHALL NOT be reported as covered.

#### Scenario: Introduce a regression
- **WHEN** a test, dependency boundary, lint/type check or coverage threshold fails
- **THEN** the corresponding required CI job fails and preserves a useful report

#### Scenario: Browser happy path
- **WHEN** Playwright executes against the built shell
- **THEN** navigation, deep-link reload, theme selection and the not-found route pass in a browser

### Requirement: Versioned contracts
The repository SHALL provide versioned OpenAPI 3.1, AsyncAPI, workflow and plugin contract locations. CI SHALL validate every published contract, reject malformed contracts and check generated SDK drift for implemented HTTP contracts. Empty event/workflow/plugin directories SHALL be explicitly reported as having no implemented contracts, without invented business messages.

#### Scenario: Probe contract and generated clients
- **WHEN** the implemented probe contract is validated and Java/TypeScript clients are regenerated
- **THEN** schema validation passes, client compilation passes and no tracked generated output changes

#### Scenario: Invalid contract
- **WHEN** a malformed OpenAPI or AsyncAPI fixture is passed to the contract validator
- **THEN** validation fails with a diagnostic instead of silently skipping the file

### Requirement: Specification controlled delivery
Every behavior change SHALL have a validated OpenSpec proposal, requirements, design and tasks before implementation. Specification and implementation reviews SHALL record their decisions and revision. PRs SHALL target dev from isolated worktrees based on origin/dev.

#### Scenario: Review the foundation
- **WHEN** DKP-001 is considered for implementation or integration
- **THEN** its specification review, explicit session authorization, validation and applicable CI evidence are available, and no production approval is inferred
