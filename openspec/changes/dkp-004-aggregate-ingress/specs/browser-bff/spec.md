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

### Requirement: Finite pending encoded-write admission
The development BFF SHALL separately admit at most64MiB encoded pending payload and4096 messages per application, with8MiB and128 per connection. Empty writes SHALL consume a message slot. Selected public write watermarks SHALL be explicitly installed and freshly attested; they SHALL NOT replace byte/message admission. Ordinary exhaustion SHALL refuse only the affected connection without fabricating operation success.

#### Scenario: Slow-reader exhausts pending-write capacity
- **WHEN** admitted encoded writes cannot complete because a client is slow
- **THEN** pending byte/message ownership cannot exceed either selected capacity, another connection remains independently usable below shared capacity, and refused ownership is separately accounted

### Requirement: Witnessed owned write settlement
On the selected pinned NIO/head-adjacent public path, the BFF SHALL forward only an observed private-direct-buffer wrapper with a fresh uncancellable internal promise. A charge SHALL release at most once after both successful private-buffer final release and terminal internal completion. Release exceptions SHALL remain unknown and latch failed-closed admission. Original cancellation/void promises, root disposal, close-request and handler removal SHALL NOT imply settlement.

#### Scenario: Promise completes after failed release
- **WHEN** the transport completes a promise despite a failed or unproved release
- **THEN** the charge remains retained and no callback or retry converts unknown ownership into available capacity

#### Scenario: Cancelled caller and empty message
- **WHEN** a caller promise is void or already cancelled, or the encoded message is empty
- **THEN** the separate internal owner still requires actual private release and completion, with an empty message retaining a finite real owner and message slot

#### Scenario: Unsupported write profile or handoff failure
- **WHEN** the exact channel/handler/direct-owner profile or watermark cannot be attested, or copy/release/handoff fails
- **THEN** new admission fails closed, only known ownership is cleaned, and unresolved admitted and refused owners remain separately recorded without claiming process settlement

### Requirement: Prompt failure with retained ownership
A terminal transport failure SHALL promptly fail the original promise and close the affected channel. Unknown ownership SHALL additionally latch admission closed and retain its unproved charge. Proven transport failure SHALL NOT poison policy readiness. Retained accounting SHALL NOT leave the caller pending or manufacture success.

#### Scenario: Successful internal promise hides a release exception
- **WHEN** the transport internally reports success after swallowing a private-buffer release exception
- **THEN** the caller receives failure promptly, admission remains latched closed and the unresolved charge remains retained

### Requirement: Finite upload source-read admission
The development BFF SHALL admit at most64 application-owned upload request sources before publisher forwarding. This technical budget SHALL remain separate from domain upload slots and JDK/download/bridge ownership. Capacity refusal SHALL use the existing unavailable contract before source reads without poisoning policy readiness.

#### Scenario: Blocked source owners exhaust capacity
- **WHEN**64 admitted upload sources remain active or unproved after cancellation or close requests
- **THEN** another upload source cannot be forwarded, existing owners remain charged, and only proven local source settlement permits reuse

### Requirement: Actual source close and read-exit settlement
A source lease SHALL release exactly once only after successful underlying close completion and actual exit of all admitted read invocations. Close SHALL prevent new read admission. Failed close SHALL remain unknown. Release callbacks SHALL run outside lifecycle locks; requested cancellation, flags, root disposal or future completion SHALL NOT substitute for either witness.

#### Scenario: Close finishes before blocked read exits
- **WHEN** a source close returns but an already admitted read is still blocked
- **THEN** its lease remains charged until that read actually exits, and another read cannot enter the closing source

#### Scenario: Close fails or remains pending
- **WHEN** underlying close throws or has not returned
- **THEN** no source settlement is reported, late read completion cannot repair a failed close, and the strongly identified owner remains charged

### Requirement: Finite bridge allocation ownership
The development bridge allocation component SHALL reserve at most8MiB/1024 allocations globally and64KiB/eight per producer before allocation. Buffers SHALL have fixed capacity at most8192 and reserve twice max(1, capacity). Copies SHALL obtain independent charges; resizing SHALL reject before changing storage. Exhaustion SHALL refuse before allocation without poisoning readiness. This component SHALL NOT imply producer, worker, JDK or whole-process bounds.

#### Scenario: Copies and empty buffers share finite admission
- **WHEN** a producer allocates or copies a bridge buffer, including an empty buffer
- **THEN** every independent allocation is admitted before construction, views retain their shared owner, and refusal cannot silently exceed either budget

### Requirement: Witness actual bridge allocation release
Private bridge buffers SHALL use owned shared Java arenas through supported public APIs. Charges SHALL release exactly once only after successful ByteBuf deallocation and successful arena close with invalidated scope. Unknown close/allocation ownership SHALL remain strongly retained and latch this pool closed; later cleanup SHALL NOT repair it. Release callbacks SHALL run outside pool locks.

#### Scenario: Framework retains a derived view or strict copy
- **WHEN** the original buffer is released while a retained view or independently charged copy remains
- **THEN** each live allocation remains charged until its own final deallocation and successful arena close, with stale arena-backed access invalidated

#### Scenario: Arena close fails on the current thread
- **WHEN** actual arena close fails, even if ByteBuf deallocation returned normally
- **THEN** the arena and charge remain strongly identified, capacity is not reused, and a later cleanup cannot convert uncertainty into success


### Requirement: Read-only download source-settlement witness
The internal download result SHALL expose a read-only source-settlement stage. The actual gateway SHALL complete it only after successful underlying close and exit of all admitted source reads. Missing legacy witnesses SHALL remain pending. The stage SHALL NOT attest worker, bridge allocation or JDK transport termination, or change HTTP/security/deadline contracts.

#### Scenario: Duplicate close returns before actual source close
- **WHEN** one underlying close remains pending and another wrapper close returns
- **THEN** the stage remains pending until the real close succeeds and all admitted reads exit

#### Scenario: Failed close or consumer completion attempt
- **WHEN** underlying close fails or a consumer completes or cancels a derived future
- **THEN** failed ownership remains unproved and the consumer cannot complete the authoritative stage

#### Scenario: Genuine HTTP download reaches EOF
- **WHEN** an actual trusted upstream download is consumed to normal EOF with successful underlying close
- **THEN** its source stage completes without changing bytes, metadata, range or authorization behavior
