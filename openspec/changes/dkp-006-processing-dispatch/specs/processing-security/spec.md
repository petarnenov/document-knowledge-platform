## ADDED Requirements

### Requirement: Independent current human and workload authority
Human commands SHALL require verified BP01 identity/tenant and current operation catalog AND document ACL. Technical workload mTLS identity SHALL be independently verified and scoped to fixed stage/run/artifact/namespace/topic; accepted actor references SHALL not become grants. Default/unconfigured authority SHALL remain UNAVAILABLE.

#### Scenario: Wrong workload or revoked actor
- **WHEN** certificate/SAN/CA/expiry/scope or current accepted actor rights fail
- **THEN** no artifact/checkpoint/Temporal/Kafka protected operation succeeds; unknown503 and denial preserve frozen non-disclosure order

#### Scenario: Actor body forgery
- **WHEN** caller submits tenant, actor, objectkey, trustedhash or arbitrary profile
- **THEN** closed body rejects400 and server derives all authority/lineage

### Requirement: Current immutable original and retention boundary
Every original resolution/reuse SHALL bind exact immutable RustFS version/hash/size and verified scan/inspection provenance with current authority. Known holds SHALL prevent destruction; unknown hold/policy SHALL not permit cleanup. Unresolved retention metadata is not artifact reuse or publication approval.

#### Scenario: Held or unknown cleanup
- **WHEN** run cancellation/restart encounters active hold, unresolved delete or untrusted evidence
- **THEN** originals, history and exact charges remain; no unsafe deletion is inferred from workflow status

#### Scenario: Metadata-only skeleton evidence
- **WHEN** real ResolveOriginalV1 records its checkpoint
- **THEN** it reports unresolved retention honestly and cannot claim byte parsing, derived artifact reuse, review or publication

### Requirement: Isolated safe execution and private contracts
Processing workers SHALL use fixed versioned contracts, no arbitrary executable download, network allowlists, nonroot/read-only/capability/resource/temp controls and trusted private secrets. Temporal/Kafka histories/events SHALL exclude content/tokens; current policy SHALL gate each stage before effect.

#### Scenario: Untrusted file or endpoint
- **WHEN** malicious upload/URL/plugin/profile reaches a stage boundary
- **THEN** no executable instructions or outbound exfiltration occurs; real egress/cap/resource negatives pass
