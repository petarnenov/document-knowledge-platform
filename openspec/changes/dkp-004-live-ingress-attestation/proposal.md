## Why

Issue [#366](https://github.com/petarnenov/document-knowledge-platform/issues/366) independently reproduces a missing capability in the approved #341 development profile. Pinned Micronaut HTTP5.2.20 exposes body-limit/decompression setters on its final `@Internal` PipeliningServerHandler without public effective-state getters or notifications. Same-instance mutation leaves external configuration and pipeline identity unchanged. The existing no-private-reflection mechanism cannot establish the selected pre-body detect/close/readiness-latch invariant.

## What Changes

Propose a DEVELOPMENT-only technical amendment: allow read-only introspection of exactly two pinned fields, with exact class/artifact/field/type validation, before body-capable pipeline callbacks. Detect/close/latch remains mandatory; no silent reset, writes, framework fork, config-only or post-body fallback is allowed. No implementation is included and no approval is recorded. Independent exact-source review and checks precede any development approval or production change.

All #341 limits, receive/queue/bridge accounting, protocol restrictions, authority, deadlines, pipeline compatibility and aggregate/current-runtime gates remain unchanged. This amendment is not a claim that #338 attestation or BP03 delivery is complete.

## Capabilities

### Modified Capabilities
- `browser-bff`: specify the narrowly pinned effective-state attestation mechanism and its fail-closed lifecycle.

## Impact

Future implementation affects only the BFF outer Netty/bootstrap boundary. No business/application dependency, database, published HTTP contract, SDK, runtime deployment, credential or provider is changed here. Existing #338 work remains blocked on this mechanism until the amendment is reviewed/approved and independently verified in implementation. See design.md, verification.md and the closed descriptor for evidence and residual risks.
