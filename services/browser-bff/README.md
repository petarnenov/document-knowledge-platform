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

The reviewed development ingress profile (`dkp-004-bounded-bff-ingress`, #341/#338)
sets the framework request queue allowance to 131072 bytes. This is global
configuration applied separately to each request, including metadata requests;
semantic JSON validation still allows only 16384 bytes. Binary input remains
1..100000000 bytes with the existing 300-second total/30-second idle transport
limits and current session, tenant, Origin and CSRF checks.

The internal listener supports plaintext HTTP/1.1 behind the existing TLS edge.
Each accepted connection receives its own fixed 8192-byte receive allocator with
one message per read and an 8192-byte decoder chunk size. Actual receive-handle
identity, channel configuration and pipeline checks run before decoding and
protected dispatch; a mismatch closes the affected channel and latches readiness
unavailable. HTTP/2 negotiation, h2c prior knowledge, direct TLS, QUIC, request
inflation and whole-body aggregation do not inherit this profile.

The conditional 147456-byte allowance is for one retained framework request body
(queue plus separately bounded current/swap bridge pieces). Legal pipelining can
retain multiple request bodies. This is not a connection, JVM or aggregate memory
cap. Aggregate capacity (#222), genuine provider/full-HTTPS acceptance and current
CI remain separate mandatory gates; local component tests do not approve them.

The separately approved development amendment #366 permits a narrow read-only
adapter for exactly two fields in Micronaut 5.2.20's selected final handler. Startup
checks the exact unshaded classpath JAR SHA-256, class loader/module/layout and
private field owner/type/modifiers/access. It neither writes private state nor
repairs setters, opens modules, forks the framework or falls back to public
configuration. Live limits and decompression values are reread on the channel
EventLoop before every read, readComplete and error handoff. The final guard must
immediately precede the same actual Micronaut handler; executor hops or relocation
close the channel before handoff and latch readiness until runtime restart.
Readiness is initially unavailable until actual accepted-channel evidence exists.

This checks serialized callback boundaries; it is not a sandbox for malicious
in-process code or a claim about unsynchronized arbitrary Java setter races.
Component/source evidence, current native/provider/full HTTPS acceptance, aggregate
capacity and genuine elapsed-hour acceptance remain distinct mandatory gates.
The development technique approval grants no formal architecture, security-control,
CID, production readiness or release approval.
