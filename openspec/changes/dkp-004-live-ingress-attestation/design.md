## Status and scope

PROPOSED, NOT APPROVED. Issue366 requests a technical amendment to the approved development profile341, not architecture/security/CID/production sign-off. The root selected option B for drafting only. No private production reflection, setter reset, framework fork, deployment or runtime acceptance is authorized by this draft.

This amendment changes only the sentence in341 design selecting enforcement “not through reflection on private Micronaut handlers”: a narrowly pinned read-only adapter for the two policy fields is proposed instead. The ban on private writes/patching and framework forks remains. All normative341 numeric and behavioral requirements remain in force, including detect-close-latch before protected dispatch. Current profile approval does not approve this different mechanism.

## Independent feasibility evidence

The exact5.2.20 source and compiled API were inspected independently. `PipeliningServerHandler` is final and `@Internal`; its private instance fields `bodySizeLimits` and `requestDecompressionEnabled` are assigned by public void setters without readback getters or change events. `HttpPipelineBuilder` initializes them before adding that same instance to the pipeline. NettyServerCustomizer exposes pipeline-construction callbacks, not a live policy-state API. See contracts/evidence.md and the research-only public API probe.

The probe executes both setters before any request/body is sent. External NettyHttpServerConfiguration, channel autoRead and pipeline handler identity remain unchanged. Public method inventory has no state getter. This is an observed capability gap, not a reproduced uploaded-content exploit or proof that arbitrary in-process Java code is sandboxed.

Reapplying expected setters is enforcement/repair, not detection, and violates the selected changed-policy rejection/latch behavior. Public request-header comparison can detect decompression effects after use; the embedded decoder may already allocate. It cannot replace a pre-body check. Replacing/subclassing the final internal handler is not a supported extension and introduces unselected framework ownership/lifecycle changes.

## Proposed pinned read-only boundary

The closed contracts/readback-profile.json and its schema fix the artifact, class identity, source/binary fingerprints, two declared field names/types/modifiers, expected body/queue limits and false decompression value. Startup verifies the loaded class is exactly the selected final class, with the selected artifact bytes on the normal JVM classpath; native-image/shaded/repackaged/module-path or unknown loaders are not implicitly supported. No additional JVM opens/agents/Unsafe/JNI are selected.

An outer adapter may use the descriptor's declared-field lookup/type/modifier/declaring-class metadata operations, `trySetAccessible` and `Field.get`/`getBoolean` ONLY for the two declared instance fields. Public class/module/code-source fingerprint metadata is allowed only to establish that exact selected class/artifact. BodySizeLimits is the exact selected record type; its public maxBodySize/maxBufferSize accessors provide values. Handles may be cached after metadata validation; live values MUST be read anew, without a success cache, at every required handoff. Null, missing, wrong declaring class, wrong type/modifiers, denied/inaccessible reads, unexpected artifact or values fail closed. No dynamic field-name search, version range, inferred defaults, public-config-only fallback or private-state write is permitted. Referencing a public getter on a future framework release requires a new reviewed profile; the unsupported API is not treated as stable.

The read-only adapter belongs to BFF bootstrap/Netty infrastructure; pure application/domain code cannot import Micronaut/Netty/reflection. Existing DI and request authority remain unchanged. The adapter is not a runtime policy configuration surface and exposes no field names, arbitrary classpath or reflective method invocation through environment variables, HTTP or user data.

## Handoff ordering and lifecycle

Checks run on the actual channel EventLoop. The actual handler identity and guard order are pinned; an effective-state guard must immediately precede the selected Micronaut handler, with no unverified handler or executor hop between check and dispatch. The raw receive guard remains before the decoder. Both channelRead and channelReadComplete require live readback: the latter can construct the streaming shared buffer. exceptionCaught also requires a guard before forwarding because the optimistic error path can devolve to streaming. Pinned-source auditing must cover every body-capable callback; discovering another path returns the implementation to review, not a partial acceptance claim.

After all pipeline customizers have run, initialization checks the actual instance and values before any incoming request data. Before every body-capable handoff the guard rechecks actual identity, callback ownership/adjacency and both values along with existing public channel/config/codec/allocator policy. A mismatch releases only the intercepted owned message, closes that channel before embedded body/decompression allocation or protected dispatch and permanently latches unavailable readiness for that runtime lifecycle. It never repairs setters or continues with a default. Framework shutdown/discard must still run to settle owned resources; close is not proof of remote/SQL absence.

Readiness requires actual accepted-channel policy evidence; constructing a reflector/configuration object is insufficient. Denied access fails startup/readiness, and later read failure closes/latches. Healthy restart requires the reviewed lifecycle and current compatible profile; a new channel cannot clear the latch. Reflection/metadata errors remain finite internal categories, with no classpath, header, token, body or exception details in public responses.

## Concurrency and proof limits

The guarantee is checked at the serialized pre-dispatch boundary, not continuous monitoring between callbacks. Tests must exercise ordered same-instance changes after initialization, between raw and decoded guards, before readComplete/error handoffs and after a prior request. Guard relocation/removal or an inserted mutator between it and Micronaut is rejected by pipeline integrity, not assumed harmless. The pinned Micronaut implementation initializes the fields before publication; legal competing customizers and their asynchronous EventLoop tasks are included in mutation testing.

Read-only reflection does not add stronger Java memory semantics to the framework's plain fields. Arbitrary malicious in-process code or unsynchronized setter races cannot be represented as an attested sandbox boundary: such code can also bypass/disable a public getter or pipeline guard. This is a stated proof limitation, not permission to omit a legal competing-customizer test or weaken any HTTP/control invariant. If an ordinarily supported customization can mutate state during the guarded handoff or evade visibility, this candidate remains unaccepted and returns to review. No new trust waiver is silently selected.

## Alternatives and tradeoffs

A — Preferred long-term supported API: keep338 unavailable until a maintained upstream release exposes effective readback or an immutable policy/hook preserving the same invariant. No such API exists in the selected5.2.20 artifact; no future version/date is invented. New release/pins/source proofs and all applicable acceptance would be required. This avoids private coupling but cannot currently implement the missing capability.

B — Drafted candidate: narrow read-only pinned access preserves rejection semantics and existing limits but adds private-layout/classpath/JDK coupling and can fail availability on any mismatch. Strict descriptor validation, missing/denied-access negatives, source/bytecode audit and upgrade re-review are mandatory. A development approval would select this implementation technique only; all security/CID/production and actual resource/runtime gates remain separate.

C — Independent external envelope/trusted-extension redesign: enforce public byte/framing/encoding rules and prove bounds without relying on these hidden fields. This may be worth separate work, but removing same-instance hidden-state detection or treating all competing customizers as trusted changes the selected invariant/threat model. It is not an equivalent fallback and is NOT selected by this amendment. Post-body effect checks, silent setter reset, private writes, a framework fork, smaller fixtures, higher deadlines or larger queues are rejected shortcuts.

## Unchanged acceptance

Queue131072, receive8192/one-message, decoder8192, initial credit65535, two bridge pieces, per-retained-request147456 allowance, semantic16384/binary100000000 and transfer300000/idle30000 remain exact. Plaintext internal HTTP1 only, public TLS/CA/hostname protection, current auth/tenant/Origin/CSRF, no preauth forwarding, fixed destinations/no implicit retry, legal pipelining, cancellation/settlement/fence/accounting remain unchanged. Full actual Linux/channel/native305/elapsed storage/provider/HTTPS/currentCI and aggregate222 gates remain mandatory; no actual proof is supplied by this spec.
