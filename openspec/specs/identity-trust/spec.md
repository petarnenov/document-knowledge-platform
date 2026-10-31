# identity-trust Specification

## Purpose
Establish independently validated actor identity and explicit tenant membership boundaries before any domain resource operation can be authorized.

## Requirements

### Requirement: Strict API access-token trust
Every domain API SHALL independently require a signed access token with exact configured issuer, nonblank subject, mandatory future numeric expiry, valid not-before when present, its own audience, allowlisted authorized party, `typ=Bearer`, `alg=RS256` and nonblank matching key ID. ID tokens, browser identity headers and unsupported algorithms SHALL NOT authenticate API requests.

#### Scenario: Valid delegated identity
- **WHEN** an API receives an unexpired correctly signed access token for its own audience and configured client/issuer profile
- **THEN** its actor identity is the immutable pair of exact issuer and subject

#### Scenario: Invalid access-token properties
- **WHEN** a request has no token, a malformed token, wrong signature/issuer/audience/client, missing or expired expiry, future not-before, blank subject, unsupported algorithm or missing/unknown key ID
- **THEN** the protected API returns HTTP 401 without protected data

#### Scenario: ID token presented to an API
- **WHEN** a valid login ID token is sent as an API bearer token
- **THEN** the API returns HTTP 401 and does not treat login authentication as access-token delegation

### Requirement: Bounded signing-key trust
Signing keys SHALL come only from the configured issuer's fixed JWKS URL with bounded network timeouts and a maximum 30-second cache lease. Unknown keys SHALL be denied until a successful refresh validates them. First-fetch or expired-cache refresh failure SHALL never authenticate a request. Token-controlled key URLs SHALL be ignored.

#### Scenario: Signing-key rotation
- **WHEN** the configured JWKS rotates to a new signing key with a documented overlap
- **THEN** unknown keys are initially denied and correctly signed new-key tokens work after a successful refresh within the cache lease

#### Scenario: Unavailable or malicious key source
- **WHEN** required JWKS retrieval times out, fails, contains no matching usable key, or the token supplies its own key URL
- **THEN** authentication fails closed without fetching a token-selected destination

### Requirement: Issuer-bound standalone tenant membership
Only the exact standalone Keycloak issuer profile SHALL map its admin-provisioned `dkp_tenants` snapshot into allowed tenant selectors. Users SHALL NOT be able to self-edit this claim. This identity snapshot SHALL NOT grant document/collection permissions or replace current owning-domain authorization checks.

#### Scenario: Two legitimate tenants
- **WHEN** a standalone actor's verified token lists two provisioned tenant memberships
- **THEN** the actor can explicitly select either tenant and obtain only their own identity context for that selection

#### Scenario: Forged or unprovisioned membership
- **WHEN** an actor supplies tenant/group/user headers, a tenant absent from verified membership, or malformed/empty membership claims
- **THEN** those inputs grant no authority and a denied or ambiguous tenant selection returns HTTP 403

### Requirement: External identity never inherits local grants
An external OIDC profile SHALL ignore `dkp_tenants` and obtain membership solely through its configured entitlement port. Until that port is implemented/configured it SHALL return unavailable, never an allow decision. Known denial SHALL produce HTTP 403; required authority unavailability SHALL produce HTTP 503 without protected data.

#### Scenario: External token contains a local-style grant
- **WHEN** a correctly authenticated external issuer token contains `dkp_tenants` but its entitlement adapter is unavailable
- **THEN** the identity-context request returns HTTP 503 and grants no tenant access

### Requirement: Independent tenant-context endpoint
Each API SHALL expose authenticated GET `/api/v1/security/context` for its own actor and an explicitly selected tenant. The selector SHALL be checked independently against verified membership. The response SHALL contain only the contract's service, issuer, subject and tenant fields and SHALL be noncacheable. Missing, unknown or unauthorized selectors SHALL NOT choose an arbitrary tenant.

#### Scenario: Direct API bypass attempt
- **WHEN** a client calls a domain API directly with tenant B selected while its token grants only tenant A
- **THEN** the API returns HTTP 403 even if the request claims to originate from the BFF

### Requirement: Resource and workload authority defaults
Resource-action authorization ports SHALL default to denial or unavailable, including for generic administrator roles. Workload verification SHALL default to unavailable until backed by actual certificate verification. Browser-supplied SPIFFE or workload headers SHALL confer no verified identity. An interface or access-token client ID SHALL NOT count as mTLS proof.

#### Scenario: Missing policy or workload implementation
- **WHEN** a resource action or workload check is requested without its configured authoritative implementation
- **THEN** the result cannot authorize the action and forged identity headers cannot change it

### Requirement: Explicit safe trust configuration
Missing or unsafe issuer, audience, authorized-party, JWKS, timeout or trust-profile settings SHALL fail startup or close the affected capability. Local and test modes SHALL enforce the same identity/tenant rules as deployed code. Authentication logs SHALL NOT contain raw tokens, session IDs or credentials.

#### Scenario: Unconfigured or unsafe profile
- **WHEN** a service starts with missing trust settings, an unsupported profile or an unsafe remotely selected key endpoint
- **THEN** no protected capability becomes permissively accessible
