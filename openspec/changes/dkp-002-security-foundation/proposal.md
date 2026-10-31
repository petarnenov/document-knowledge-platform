# DKP-002 — Identity and Browser Security Foundation

Status: Approved for implementation under standing owner authorization after independent architecture/security review and strict validation; see approval.md. This does not establish production security acceptance.

## Why

The platform currently exposes process scaffolds and an anonymous shell, with no identity provider, trusted browser entry or tenant authority. Real OIDC authentication, token custody and independent default-deny service enforcement must precede document and collection features.

## What Changes

- Introduce a separately deployable Micronaut browser BFF using confidential OIDC Authorization Code with PKCE, opaque server sessions, CSRF protection and a fixed upstream allowlist.
- Add strict JWT trust and tenant-context validation to every domain API, with explicit standalone and external issuer profiles and nonpermissive entitlement/workload ports.
- Add a real portal login/session/tenant-selection/security-context/logout journey with tokens confined to the BFF.
- Add generated local HTTPS/identity secrets and a pinned standalone Keycloak runtime, plus positive/negative browser/API/IdP tests and coverage/architecture/contract gates.
- Persist the full-project delivery queue and 15-minute SDLC status cadence; do not stop at an individual package boundary.

## Capabilities

### New Capabilities

- `identity-trust`: Independently authenticated actors, explicitly trusted tenant membership and deny/unavailable authorization boundaries.
- `browser-bff`: Browser OIDC, secure server-side sessions, CSRF/origin enforcement and constrained delegation to independently protected APIs.

### Modified Capabilities

- `service-foundation`: Permit the reviewed identity/security-context surface while preserving process probes and denial of unimplemented resources.
- `portal-shell`: Add real identity/session actions and safe error states without inventing document capabilities.
- `local-runtime`: Include the BFF and used identity provider, generated local secrets/HTTPS and explicit ephemeral-session limits.

## Impact

New `services/browser-bff` layers, shared immutable technical identity contracts and framework security adapters, three existing API adapters/bootstrap modules, the portal, versioned OpenAPI/SDKs, Gradle locks/architecture checks, Compose and CI. No document entities, business ACL grants, database migrations or processing workers are introduced.

Preserves selected DM-003/016/021/032/063, P02/P03/P06/P07/P08/P10/P19/P20 and System Design §§5/7/14. CID-06 standalone selections are explicit implementation choices, not accepted production trust or external corporate integration. Actual SPIFFE/SPIRE mTLS and durable multi-instance sessions remain visible BP-16 gates. Resource authorization remains authoritative in future owning domains and cannot be granted by a generic admin role or the local identity snapshot.
