## ADDED Requirements

### Requirement: Pinned live effective-policy attestation
Only after this amendment's development approval, the BFF SHALL read exactly the two pinned fields in `contracts/readback-profile.json`, read-only, before each body-capable handoff to the actual handler. Missing, inaccessible, changed metadata or policy SHALL close the channel and latch unavailable before body/decompression allocation or protected dispatch; public configuration alone SHALL NOT substitute.

#### Scenario: Same-instance dormant decompression or limit mutation
- **WHEN** the existing handler's setter changes effective decompression or body/queue limits while external configuration and handler identity remain unchanged
- **THEN** the pre-body readback detects the mismatch, closes before embedded decompression/body allocation or protected dispatch, and latches unavailable without repair, fallback or a successful artifact/receipt/version

#### Scenario: Reflection or artifact incompatibility
- **WHEN** the exact class, artifact, declared field type/modifiers or read permission cannot be established, including inaccessible fields or an unsupported runtime layout
- **THEN** startup/readiness fails closed or the accepted channel closes/latches before protected dispatch, without exposing internal details or substituting configuration/default values

### Requirement: Serialized complete attestation handoff
The BFF SHALL validate actual handler identity, EventLoop ownership and adjacent guard ordering for channelRead, channelReadComplete and exceptionCaught before forwarding. It SHALL cover every pinned-source body-capable path; a missing path or legal mutator interleaving invalidates acceptance. Private writes, setter repair, generic reflection, framework forks and unchanged-configuration inference SHALL NOT be fallbacks.

#### Scenario: Mutation after a raw check or before streaming allocation
- **WHEN** an ordered setter change occurs between raw/decoded stages or before readComplete/error processing can allocate a body, or a mutator is inserted after the adjacent guard
- **THEN** effective-state or pipeline-integrity validation rejects the handoff and latches unavailable before the affected Micronaut body path runs

#### Scenario: Unchanged legal streaming profile
- **WHEN** effective policy, actual channel and handoff remain exactly selected while fixed/chunked/coalesced/pipelined legal traffic and cancellation are exercised
- **THEN** all prior131072/8192/one-message/147456 accounting,16384/100000000 byte bounds,300000/30000ms deadlines and current authority/fence/settlement obligations remain independently enforced without a global-cap claim
