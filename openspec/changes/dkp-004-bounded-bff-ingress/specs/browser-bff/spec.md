## ADDED Requirements

### Requirement: Bounded shared framework ingress
The BFF SHALL apply `contracts/streaming-ingress.md` only after development-specification approval. The listener-wide framework setting SHALL bound each request pending-body queue at131072bytes, separately from16384byte semantic metadata and1..100000000byte fixed binary bounds; it SHALL NOT be presented as an aggregate listener cap.

#### Scenario: Metadata and authority remain independent
- **WHEN** metadata exceeds16384bytes or current session/tenant/Origin/CSRF authority is invalid even though the global framework window is larger
- **THEN** the existing semantic/authority rejection occurs without upstream body forwarding and no artifact, receipt or version is issued

### Requirement: Constrained receive envelope
Supported plaintext HTTP/1.1 connections SHALL use bounded8192byte/one-message receive cycles and8192byte decoder pieces, with actual channel verification before protected dispatch. The receive-credit/queue/swap/current-piece proof SHALL be recorded; a passing large-file test alone SHALL NOT establish the bound.

#### Scenario: Legal burst and delayed consumer
- **WHEN** an authorized caller sends the unchanged32,400,444-byte public fixture in a legal fixed-length or chunked burst while the downstream consumer is temporarily blocked
- **THEN** the selected receive/window policy applies backpressure within its accounted bounds and resumes without whole-artifact buffering or changed300s/30s limits

### Requirement: Unsupported ingress profiles fail closed
DirectTLS, HTTP2/h2c/QUIC/multiplexing, unknown channels, decompression, allocators or pending-body policies outside the reviewed profile SHALL fail startup/readiness or close the affected channel before protected dispatch. No other protocol SHALL inherit the plaintextHTTP1 memory proof.

#### Scenario: Unsupported or changed channel policy
- **WHEN** active channel/configuration falls outside the reviewed profile
- **THEN** startup/readiness or the affected channel fails closed before protected dispatch and no other protocol inherits the plaintextHTTP1 memory proof

### Requirement: Explicit aggregate ingress accounting
Concurrent retained requests SHALL have finite accounted queue/bridge/transport overhead. A per-request allowance SHALL NOT be represented as a process-wide or connection-independent cap.

#### Scenario: Pipeline and aggregate memory remain explicit
- **WHEN** multiple requests or connections retain input concurrently
- **THEN** their finite request counts and separate queue/bridge/transport overhead are accounted and no per-request allowance is represented as a process-wide or connection-independent cap
