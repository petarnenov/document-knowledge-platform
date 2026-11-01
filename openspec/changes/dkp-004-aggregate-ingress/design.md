## Selected candidate and authority

Issue222, independently checked under224, is the existing acceptance owner. This candidate selects technical development admission capacities C=32 connections and R=64 retained request bodies. Both exceed the ten domain upload slots; neither replaces domain admission or assigns roles. These are finite technical limits, not production throughput/sizing approval. Exhaustion is a connection-local transport refusal before unadmitted bytes enter the framework body owner. Other admitted connections remain usable; normal overload must not poison policy-attestation readiness.

## Ownership and lifecycle

1. A single application-owned admission budget grants a connection lease during accepted-channel specialization, before auto-read or the first receive. Failed channel initialization and normal teardown have explicit ownership paths.
2. The decoded guard acquires a request lease before forwarding each native HTTP/1.1 request into the existing adjacent PipeliningServerHandler. Coalesced and pipelined requests each consume a lease. Do not impose a one-unfinished-request restriction.
3. The pinned public HttpRequestReceivedEvent identifies the NettyHttpRequest/native request. Its LifecycleHttpRequest.addDisposalResource callback schedules release of only the request-owned queue allowance behind an ordered channel-event-loop cleanup barrier: body.close can enqueue discard/subscribe work, so callback invocation alone is insufficient. Late registration is supported by the pinned implementation. Executor rejection retains the charge. Registration does not read a body or perform I/O.
4. Counts and native-identity ownership are thread safe. A lease releases at most once; duplicates, disposal/close races and delayed callbacks cannot underflow counts or free another request. A lease must not be recreated by a late callback.
5. A request that never reaches the public lifecycle hook remains charged. It may release after the exact framework handler has been removed and its owned inbound queues discarded on confirmed channel teardown. For pinned Netty4.2.19, arm a teardown witness only after channelUnregistered forwarding returns with the exact framework handler and first raw guard intact on their channel event loop. The subsequent tail-to-head pipeline destruction must complete framework removal before raw-guard removal, with confirmed close and no head-routed ChannelPipelineException. Such an exception is latched before forwarding/reentrant close; isRemoved alone is insufficient because Netty marks it even when removal throws. Early/manual/reentrant guard removal never arms the witness. Never release it merely because close was requested or a response filter ran. If this proof cannot be established for a path, retain its charge and fail that connection closed rather than assume settlement.
6. Registered bodies may have independently owned bridge pieces or workers after request disposal. They require separate accounting under222. Connection admission release is not an acknowledgment of remote/SQL rollback or JDK/write completion.

## Accounting

For this pool, R *147456 =9437184 bytes is the existing conservative retained-request body allowance. C *8192 =262144 bytes is a separately reported receive-piece allowance; overlaps may be conservatively double-counted, never silently subtracted. These figures exclude header/object allocation, socket/JDK/TLS/edge buffers, output queues and bridge ownership after request disposal. Record those separate pools and actual headroom before full aggregate acceptance. The independent local disk payload ceilings remain separate from heap/direct memory and remote upload quotas.

## Compatibility and fail-closed behavior

Preserve the selected HTTP/1.1/nondecompression/readback profile, exact byte limits, first/final authority, session/tenant/Origin/CSRF, existing300s/30s stream deadlines and240s verification deadline. Admission does not make an authorization decision or manufacture HTTP success. Refusing an unadmitted or unprovably bound connection must not clear or replay any accepted domain operation. No new business response schema or role is selected.

## Verification

Add direct count/identity/race tests and actual Linux socket tests for pre-auth C exhaustion, R exhaustion across connections, legal coalesced/pipelined requests, disposal/reuse, incomplete and malformed requests, disconnect and initialization failures. Observe before-framework admission and actual teardown/disposal; an idle request count or rendered UI is insufficient. Retain original26 ingress regressions, affected BFF suite and75% line/branch floors using the shared222-only pinned Linux command. Add independent final source/evidence review before publication. New admission proof does not resolve the other222 pools or failed post-dev provider acceptance.

## Settlement readback amendment — independently reviewed development selection

The public request-disposal callback plus one queued event-loop barrier proves ordering, not cleanup success. Pinned Netty executor tasks can fail without a head-routed pipeline exception. An already claimed primary also transfers bytes to subscribers; it must not be described as root-queue cleanup.

Propose a separate narrow read-only settlement adapter at this outer boundary, extending the approved pinned readback technique without changing its original two policy fields. Validate exact loaded class/loader/artifact/type/modifier metadata. Pin Micronaut5.2.20 `micronaut-http` SHA256 `e4ec29828c0c6201e7c2d878fd20991e01017dfa3fce47418c4c589b53c48637` and `micronaut-http-netty` SHA256 `f03cad89ec16484a6b9021c8d5a376e39b8048857a5242df46aa435a91028920`, in addition to the existing server policy artifact. The additional closed field selection is:

- `io.micronaut.http.body.stream.BaseStreamingByteBody.sharedBuffer`: protected final, erased type `BaseSharedBuffer`.
- `io.micronaut.http.body.stream.BaseSharedBuffer.reserved`: private int.
- `BaseSharedBuffer.buffer`: private `java.util.List`.
- `BaseSharedBuffer.fullSubscribers`: private `java.util.List`.
- `BaseSharedBuffer.working`: private boolean.

After exact shared-buffer class validation, require its public `eventLoop()` getter to return the request channel event loop. No sixth reflective field is selected; `StreamingNettyByteBody.isCompatible` is package-private and is not used.

Capture the exact root shared buffer from an exact `StreamingNettyByteBody` at the public received event before routing/claim. After public disposal and an ordered same-loop barrier, release that root-queue lease only when `reserved == 0`, both `buffer` and `fullSubscribers` are null, and `working == false`. Pinned `add0` buffers future input only with reserved subscribers or full subscribers; `reserve` cannot create another legal reservation after all primary slots have been claimed. Retain the charge and fail this connection closed if observation is unavailable or settlement is not proved. Never repair state, poll indefinitely, or infer success from callback alone. No reflection writes, caller-selected names/classes, JVM opens, generic lookup or fallback.

An exact `io.micronaut.http.body.stream.AvailableByteArrayBody` uses its pinned synchronous close/claim lifecycle without a streaming root queue. Unknown body classes remain unproved. Subscriber/AsFlux/bridge pieces remain separate aggregate owners and are not freed by this root-queue observation. Original policy readback and all authority checks remain mandatory.

Verify exact pinned artifact hashes, wrong loader/class/field/type/modifier/read permission, unsettled/working/reserved/buffering states, failed cleanup tasks, off-loop and forced-delay ordering, and no early release for primary transfers. Independent224 development-technique review and strict validation passed before implementation, with source-hash approval receipts recorded in tasks.md. No architecture/security/CID/production signoff is implied.

## Owned JDK client shutdown amendment — development candidate under222/224

Each fixed outbound gateway owns exactly one JDK HttpClient for its application instance. Register a public Jakarta PreDestroy hook calling that client's public shutdownNow method on bean destruction. This is application shutdown, never request completion, cancellation, denied authority or overload. Do not use blocking close on the destruction thread: outstanding streaming exchanges may otherwise delay shutdown indefinitely. shutdownNow is a best-effort shutdown request, not proof that all exchanges, subscribers or workers have settled. Only actual termination observations may support that narrower claim. Concurrent application contexts retain independent clients.

Move FixedUploadGateway client construction after its existing deadline validation, preserving the exact redirect, connect, total and idle settings. Invalid constructors must not allocate an owned client. Do not introduce a shared static client or change application/domain ports, HTTP schemas, security, retries or stream timing. Direct Java construction outside the DI container requires the same public shutdown hook in test cleanup.

Verify actual local HTTP exchanges through all three clients, retain their exact identities across ApplicationContext shutdown and require bounded actual termination without GC. A second live application context must remain able to exchange after the first closes. Preserve a failing pre-fix lifecycle test and the successful corrected test, then run the entire affected shared Linux222 profile and coverage. This closes only the application-client lifecycle defect; live JDK transport capacity, outbound bytes and full aggregate headroom remain open.
