## ADDED Requirements

### Requirement: Finite aggregate ingress admission
The development BFF instance SHALL own at most32 admitted live connections and64 retained request-body leases, acquired before first application read and before a decoded request enters the framework body owner respectively. Pre-auth and pipelined requests SHALL count. Domain slots SHALL NOT substitute for these technical counts. Exhaustion SHALL refuse only the unadmitted connection without poisoning policy-attestation readiness or fabricating operation success.

#### Scenario: Pre-auth connection exhaustion
- **WHEN** the selected connection capacity is occupied before authentication
- **THEN** another connection cannot obtain application read admission, existing admitted connections remain usable, and only confirmed teardown frees the connection charge

#### Scenario: Multiple retained pipelined requests
- **WHEN** several legal HTTP/1.1 requests retain bodies across the same or different connections
- **THEN** each receives its own shared request lease before framework body ownership, legal pipelining below capacity remains supported, and exceeding64 cannot allocate an unadmitted framework body queue

### Requirement: Request-owned ingress settlement
The BFF SHALL bind each admitted native request to its pinned public request-disposal lifecycle. It SHALL release that request's queue allowance exactly once after actual body disposal. Unbound requests SHALL remain charged until proven framework removal/discard on confirmed channel teardown. Requested cancellation, response completion or unmatched callbacks SHALL NOT release another owner or imply bridge, worker, write, remote or SQL settlement.

#### Scenario: Disposal races teardown
- **WHEN** request disposal and channel teardown or a delayed callback overlap
- **THEN** the admitted request releases at most once after its proven settlement and no counter underflows or frees another native request

#### Scenario: Malformed or unbound request closes
- **WHEN** an admitted request cannot bind to a supported public lifecycle or terminates before binding
- **THEN** its connection fails closed, its queue charge remains until actual framework discard is proved, and no missing callback is treated as successful cleanup

### Requirement: Separate aggregate evidence
Admission evidence SHALL report request body and receive-piece arithmetic separately from headers, bridge/worker, pending writes, socket/JDK/TLS/edge and other process overhead. The admission limits SHALL NOT be represented as whole-process memory bounds or production sizing approval. Full aggregate acceptance SHALL remain incomplete until the separate222 pools and required headroom are proved.

#### Scenario: Review admission arithmetic
- **WHEN** the32-connection/64-request development profile passes its component checks
- **THEN** the9437184-byte conservative body and262144-byte receive allowances are reported with their exclusions, and remaining aggregate, integration and production gates remain explicit

### Requirement: Proven root-queue settlement
After independent development approval, the outer BFF SHALL use only the exact five-field read-only settlement profile in design.md. Streaming root-queue release SHALL require zero reservations, no root buffer or full subscribers, and no active mutation, observed on the channel event loop after disposal ordering. Unproved state SHALL retain its charge and fail that connection closed. Original policy attestation and separate subscriber accounting SHALL remain mandatory.

#### Scenario: Cleanup task fails before settlement
- **WHEN** disposal has been signalled but cleanup throws or leaves reserved, buffering, full-subscriber or active-mutation state
- **THEN** callback or FIFO ordering alone cannot release the request charge, the unproved owner remains charged, and the connection fails closed

#### Scenario: Off-loop disposal and primary transfer
- **WHEN** disposal enqueues cleanup or the primary has transferred bytes to a subscriber
- **THEN** observation runs after the cleanup barrier and proves only root-queue settlement, preserving separate subscriber and bridge obligations

#### Scenario: Unsupported settlement profile
- **WHEN** exact artifact, class, loader, field type/modifier or read-access validation fails
- **THEN** no settlement is inferred, no arbitrary lookup, private write, JVM opener or fallback is used, and the charge remains retained

### Requirement: Application-owned HTTP client shutdown
Each fixed outbound gateway SHALL request shutdown of its own JDK HTTP client when its application bean is destroyed, using the public non-blocking shutdownNow lifecycle API. Request completion or cancellation SHALL NOT shut down that shared application client. Invalid upload deadline construction SHALL reject before allocating a client. Shutdown invocation alone SHALL NOT be represented as transport or worker settlement.

#### Scenario: Application destruction releases owned idle clients
- **WHEN** real exchanges have completed and their application context is closed
- **THEN** all three exact owned clients reach actual termination within the bounded test observation without garbage collection, while clients owned by another live application context remain usable

#### Scenario: Invalid upload deadline construction
- **WHEN** a caller supplies an invalid Java-only test deadline
- **THEN** construction rejects before creating a JDK client and existing production deadlines remain unchanged
