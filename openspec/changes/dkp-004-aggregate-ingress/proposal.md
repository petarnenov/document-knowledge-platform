Development state: bounded C32/R64 admission and the exact root-settlement technique independently reviewed under224 and approved through standing development-specification authorization (see tasks.md). Original candidate wording below records the proposal rationale. Full222 acceptance, architecture/security/CID and production gates are not approved here.

## Why

Existing final acceptance issue222 requires aggregate ingress accounting. The approved131072-byte queue and147456-byte conservative body allowance apply per retained request. Domain upload admission does not bound pre-auth connections or pipelined bodies. The current BFF has no explicit shared connection/request admission owner.

## What Changes

Propose a development profile with32 live connection leases and64 retained ingress-request leases per BFF application instance. Acquire before first application read and before a decoded request reaches the framework body owner, respectively. Preserve legal multi-request HTTP/1.1 pipelining below these limits. Capacity exhaustion closes only the unadmitted connection; it does not latch policy-invalid readiness or invent a successful operation.

Use the pinned public request-disposal hook for the request-owned queue only. Bind every admitted request to its exact native request identity; unprovable bindings fail closed on their own connection. Release unbound requests only after actual framework removal/discard during confirmed channel teardown. Connection closure does not establish bridge, JDK exchange or outbound-write settlement.

This is one part of222. Timer cleanup, bridge/worker ownership, pending outbound bytes, JVM/edge/transport headroom and the final aggregate ledger remain separately required. Do not claim that32/64 bounds whole-process memory or closes BP03. Numeric selection is PROPOSED until independent exact-source review and recorded development approval.

## Capabilities

### Modified Capabilities
- `browser-bff`: add finite aggregate ingress admission without changing published business contracts or authority checks.

## Impact

Only the outer BFF Netty/bootstrap boundary and targeted component tests. No domain/application dependency, database, credential, public schema, generated SDK, TTL, processing behavior or production readiness selection changes. Follow the owner's222-only affected verification instruction; do not rerun unrelated Foundation/provider/hour suites. Existing unrelated post-dev failure166/237 remains open.
