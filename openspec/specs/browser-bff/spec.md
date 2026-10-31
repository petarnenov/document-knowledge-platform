# browser-bff Specification

## Purpose
Keep browser credentials within a trusted server entry and delegate only authenticated, tenant-scoped requests to independently protected APIs.

## Requirements

### Requirement: Confidential browser login
The BFF SHALL use framework OIDC Authorization Code with S256 PKCE, server-side state/nonce and an exact registered callback. It SHALL validate ID-token issuer, audience, signature and expiry. Invalid state, nonce, verifier, redirect URI, issuer or audience SHALL fail login without an authenticated session.

#### Scenario: Successful provider login
- **WHEN** a browser completes a valid standalone OIDC authorization-code exchange
- **THEN** the BFF authenticates the verified issuer/subject and stores the access token only on the server

#### Scenario: Tampered authentication exchange
- **WHEN** state, nonce, PKCE verifier, redirect URI, ID-token issuer or audience is missing or invalid
- **THEN** the exchange fails and no authenticated session or token reaches the browser

### Requirement: Restricted server sessions
The BFF SHALL issue only an opaque `__Host-DKP-SESSION` cookie with Secure, HttpOnly, SameSite=Lax, Path=/ and no Domain. It SHALL rotate the identifier after login and tenant selection. Idle and absolute expiry SHALL be bounded by the access-token expiry; tokens SHALL NOT refresh. Logout SHALL invalidate locally before responding.

#### Scenario: Session lifecycle
- **WHEN** a user logs in, selects a tenant and then logs out
- **THEN** both transitions rotate the identifier, prior identifiers cease authorizing requests and logout removes the authenticated session

#### Scenario: Expiry or restart
- **WHEN** the token or session expires, or the single-instance BFF restarts
- **THEN** protected requests fail closed and the browser must authenticate again

### Requirement: Safe session representation
GET `/api/v1/session` SHALL return only issuer, subject, displayName, allowed tenant identifiers, nullable selectedTenant and csrfToken for an authenticated browser. Session and identity responses SHALL use Cache-Control: no-store. Access, ID and refresh tokens, credentials and session identifiers SHALL NOT appear in browser JSON, storage or application logs.

#### Scenario: Read identity state
- **WHEN** an authenticated browser requests its session
- **THEN** it receives the safe representation and a CSRF token with no credential or bearer token

#### Scenario: Anonymous session request
- **WHEN** no valid authenticated session exists
- **THEN** the endpoint returns HTTP 401 without protected identity data

### Requirement: Origin and CSRF guarded mutations
POST `/api/v1/session/tenant` and POST `/api/v1/session/logout` SHALL require an authenticated session, matching X-CSRF-TOKEN and exact configured Origin on every accepted content type. No GET route SHALL mutate tenant selection or log out. A rejected request SHALL preserve session state.

#### Scenario: Valid tenant selection
- **WHEN** a user submits an allowed tenantId with both guards
- **THEN** selection succeeds and the rotated session representation contains that tenant and a fresh CSRF token

#### Scenario: Cross-site or forged mutation
- **WHEN** either guard is absent or wrong, the tenant is unauthorized, or a mutation is attempted through GET
- **THEN** no session mutation occurs and the request receives a non-success response, including for JSON bodies

### Requirement: Constrained authenticated delegation
GET `/api/v1/security/context/{service}` SHALL accept only dm, kb or search and forward only to that service's fixed configured hostname and exact GET `/api/v1/security/context` path. The BFF SHALL require a selected tenant, attach its server-held access token and checked selector, strip caller identity headers, disable redirects and bound timeouts and response size.

#### Scenario: Selected tenant context
- **WHEN** an authenticated browser with a selected tenant requests a known service context
- **THEN** that API independently validates the actor and selector and returns only service, issuer, subject and tenant

#### Scenario: Forged target or authority
- **WHEN** a request supplies an unknown service, escaped path, arbitrary URL, forged identity headers or an upstream redirect
- **THEN** it cannot change the destination or authority, no credential is forwarded to an unapproved target and no unauthorized context is returned

#### Scenario: Missing selection or unavailable dependency
- **WHEN** no tenant is selected or the allowed upstream times out or cannot establish membership
- **THEN** the BFF returns a safe non-success response without fabricating context or granting access

### Requirement: Explicit deployment limits
The BP-01 BFF SHALL own no database and use a single-instance ephemeral session store. Durable multi-instance sessions and verified workload mTLS SHALL remain explicit BP-16 gates. If provider logout is introduced, it SHALL require id_token_hint and an exactly registered post_logout_redirect_uri; BP-01 SHALL perform local logout only.

#### Scenario: Inspect readiness claims
- **WHEN** a contributor reviews the runtime and deployment evidence
- **THEN** single-instance restart behavior and unimplemented BP-16 gates are explicit and no production trust or availability acceptance is claimed

### Requirement: Verified BFF membership source
The BFF SHALL derive standalone tenants only from an admin-provisioned snapshot in the framework-validated ID token, with exact configured issuer, BFF client audience and nonblank subject. It SHALL bind the snapshot to that verified actor. External profiles SHALL ignore this claim and use only the entitlement port, failing unavailable with 503.

#### Scenario: Independently validated snapshots
- **WHEN** the BFF selects a tenant from its verified ID-token snapshot and calls an API
- **THEN** the API validates its access-token snapshot independently and the BFF accepts only a response matching its session actor and selected tenant

#### Scenario: Untrusted or mismatched membership
- **WHEN** claims are unvalidated, malformed, bound to a wrong issuer/audience/subject or supplied as local-style claims by an external issuer
- **THEN** they grant no BFF tenant membership or successful selection
