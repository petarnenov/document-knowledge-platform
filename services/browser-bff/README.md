# Browser BFF

Independently deployable Micronaut confidential OIDC client and browser entry. Domain and application contain framework-independent identity/session policy; inbound adapters handle framework OAuth, session/CSRF and HTTP contracts; outbound adapters delegate fixed context routes and the approved metadata-only DM methods/paths. Bootstrap owns configuration and default-unavailable external entitlements. The BFF owns no database.

## Runtime contract

- Browser gets only opaque `__Host-DKP-SESSION` (Secure, HttpOnly, SameSite=Lax, Path=/, no Domain). Access token stays in a redacted server identity; ID and refresh tokens are not retained.
- Login uses framework code/S256, server-side state/nonce/verifier and a fixed callback URL. Session identifiers rotate on login and tenant selection; safe JSON always includes `selectedTenant`, initially null.
- Exact Origin and session CSRF guard every supported mutation; only JSON is accepted. Logout is local POST only; it does not terminate the provider's SSO session.
- Four-service identity contracts are published in `contracts/openapi/v1/identity.yaml`. Context requests use exact GET paths; DM delegation maps only the reviewed `/api/v1/dm/documents` routes to `/api/v1/documents` (including UUID detail, PATCH ACL, holds and the unavailable version boundary). Requests use fixed configured hosts, never caller targets/identity headers. Redirects, oversize/malformed responses and actor/tenant/service mismatch fail closed.
- OIDC requires HTTPS and exact issuer, plus HTTPS provider endpoints under that issuer origin. Configuration is checked before discovery; final discovered metadata is checked before token exchange. Only the explicitly named isolated `test-oidc-loopback` test environment permits HTTP loopback provider fixtures; remote HTTP is rejected there too. Real Compose/browser acceptance uses certificate verification over HTTPS.

DM command success bodies contain exactly `documentId` and `revision`; inspection responses require independent DM authorization. Response schemas, document IDs, tenant scope, status codes and byte limits are checked before forwarding. Every mutation requires Origin, session CSRF and Idempotency-Key; only JSON bodies up to16KiB are accepted. No upload/content/publication readiness is fabricated.

## Required configuration

Set `OIDC_ENABLED=true`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, exact `OIDC_CALLBACK_URI`, `DKP_PORTAL_ORIGIN`, and fixed `DKP_DM_URL`, `DKP_KB_URL`, `DKP_SEARCH_URL`. Secret injection and dedicated CA trust are provided by the Compose bootstrap, never tracked credentials. With login disabled, probes are available and protected identity routes remain closed.

Standalone membership uses only the framework-validated ID-token snapshot with exact BFF issuer/audience/subject binding. APIs independently verify access tokens and their own tenant snapshots. External profile uses only its entitlement port; the shipped adapter returns unavailable. Generic roles grant no resource actions.

## Verification and limits

Run `./gradlew :services:browser-bff:bootstrap:build --no-daemon` with the repository's pinned Java LTS. Tests exercise real framework HTTP OAuth positive/negative exchanges, session/CSRF/Origin and raw JSON contracts, upstream faults and deadlines, trust-configuration rejection and external authority decisions. Source line and branch coverage gates are at least 75%. These controlled provider fixtures complement the real HTTPS Keycloak Playwright E2E; they do not replace it.

Session storage is single-instance and ephemeral. Restart requires login again; idle/absolute access cannot outlive the earlier verified ID-token expiry or token-endpoint access-token lifetime, capped at 300 seconds. Durable multi-instance sessions and actual workload mTLS remain BP-16 gates. No production readiness is claimed.
