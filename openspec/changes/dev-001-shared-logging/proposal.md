## Why

Owner requirement [#457](https://github.com/petarnenov/document-knowledge-platform/issues/457) makes comprehensive development diagnostics a BLOCKER. Existing stdout and narrow failure evidence cannot reconstruct an upload503 or native-thread failure, and do not join frontend, backend, provider and CI activity across compilations. Repeated debugging must not depend on a lucky retained terminal output.

## What Changes

- Persist the owner logging gate in the implementation protocol before other work.
- Introduce a development telemetry boundary owning one persistent log-only PostgreSQL database and common authenticated ingestion/query API for all builds and components; preserve independent domain database ownership and authoritative audit.
- Version the event/batch/query contract, mandatory `buildId`, distinct `runId`, trusted producer attribution and indexed debug filters.
- Instrument FE through nonblocking Beacon, BE/workers through bounded asynchronous structured emitters, and infra/build/CI through the same contract and explicit durable spool/import path where direct connectivity is absent.
- Preserve useful sanitized DEBUG/TRACE operation and exception context, with visible delivery/rejection/loss, retention and resource limits.
- Verify real multi-build persistence, failures, recovery, security and nonblocking UI before declaring the blocker complete.

## Capabilities

### New Capabilities

- `shared-dev-logging`: persistent cross-build logging, provenance, controlled ingestion/query, producer instrumentation and honest delivery evidence.

### Modified Capabilities

None. Existing content/security/audit and operation deadlines retain their contracts.

## Scope and Approval

#457 was created after open/closed duplicate checks and before this work. Related #47 covers broader BP15 governance/audit, #166 the upload failure, #222 native resource failure and #141 active delivery. None is closed by preparing these artifacts. This draft requires independent architecture/security analysis, strict validation and recorded development-specification selection before runtime edits. It grants no architecture/security-control/CID/production sign-off, no secret logging and no public deployment. Preserve pending frontend work and the Interactive Design v0.5; no new product UI is required for the operator query tool.
