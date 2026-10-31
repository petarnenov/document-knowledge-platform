# DKP-002 design and verification plan

## Context and approval boundaries

BP-01 implements real browser authentication and independently enforced API tenant identity before any document capability. System Design §§5/7/14 and selected architecture remain authoritative. Automatic specification approval follows independent review and strict validation; it cannot approve new production trust, resource privileges or release readiness. Latest owner instruction authorizes automatic PR merging into dev for all tasks after independent review and passing checks. Full project scope and reporting cadence are recorded in `docs/implementation/queue.md`.

No runtime implementation starts before the specification review is resolved and an approval record identifies the reviewed content. Provider/library source inspection and specification preparation are analysis, not implementation. Changes after approval require re-review proportional to their security/contract effect.

## Selected architecture

- Add independently executable `services/browser-bff` with domain, application, adapters-in, adapters-out and bootstrap modules. No BFF database. Existing three APIs independently validate tokens and tenant selectors, including direct calls bypassing the BFF.
- Share only immutable technical identity contracts and framework security adapters. Domain/application stay framework independent. Root dependency allowlists and ArchUnit explicitly cover four services and technical modules; negative architecture fixtures prove forbidden cross-service and adapter coupling still fails.
- Use Micronaut's published OAuth2, session, JWT and CSRF implementations, with narrow policy extension beans for strict validation and content-type-independent guards. No custom cryptography or OAuth protocol implementation.
- CID-01/CID-06 selections: Java 25 LTS, Micronaut platform 5.2.4, managed Security 5.4.1 and Session 5.2.0; Keycloak 26.8.1 pinned by image digest. These are **selection for standalone/local, not promotion to accepted production trust config**. Record all digests, source provenance, scans and dependency locks before acceptance.

## Identity trust

Each API accepts bearer access tokens only. Require exact configured issuer string, nonblank subject, mandatory numeric future expiration, valid optional not-before, own audience (`dkp-dm`, `dkp-kb`, `dkp-search`), allowlisted authorized party, payload `typ=Bearer`, JOSE `alg=RS256` and matching nonblank `kid`. Require appropriate public signing key and delegate signature checking to the framework. ID tokens and token-supplied key URLs never establish API trust.

Fixed configured JWKS endpoint, bounded fetch timeout, maximum 30-second cache TTL. Unknown key is denied until a successful refresh. A failed first fetch or expired cache fetch fails closed; existing unexpired known keys remain usable only within the documented TTL. Test overlapping key rotation and retired-key rejection after expiry. No raw JWT DEBUG logging.

Standalone membership comes from admin-managed signed `dkp_tenants` string-list snapshots only when exact issuer matches the standalone profile. Local users cannot edit that claim. It establishes tenant membership only, never resource ACLs. External profile ignores that claim and calls an entitlement port; absent configuration/outage returns unavailable (503), known denial returns 403. Actor key is issuer plus subject, never subject alone. Selector `X-DKP-Tenant` is an untrusted request choice checked against verified membership at each API.

The BFF membership source is the framework-validated ID token, with an additional exact issuer/audience/subject policy at the authentication mapper boundary. Its audience must contain the configured confidential BFF client ID; require nonblank subject and bind an immutable membership snapshot to that verified issuer/subject. Never extract authorization from an unvalidated JWT or browser-supplied claims. Configure the admin-only tenant mapper into both ID and access tokens: BFF uses only its verified ID-token snapshot, while each API independently uses its own validated access-token snapshot. External-profile BFF membership comes only from the entitlement port and ignores local-style claims. Proxy response actor/tenant parity must match the BFF session, preventing a token for another actor from producing a context. Test wrong issuer/audience/subject binding, malformed snapshots, unvalidated claim extraction and external local-style claims at the BFF.

Resource-action authorization ports default to deny/unavailable even for an admin role. Workload identity port returns unavailable until actual certificate verification exists; SPIFFE headers and `azp` are not workload proof. Actual SPIFFE/SPIRE mTLS is BP-16 work, visibly unfinished here.

## Browser protocol and session policy

Confidential authorization-code flow with S256 PKCE, framework state and nonce persistence, exact registered callback `/oauth/callback/keycloak`; login entry `/oauth/login/keycloak`. Code exchange and ID-token verification occur at the BFF. Retain access token only in server session; discard any refresh token and never refresh. Configure standalone access-token lifetime explicitly to 300 seconds in the committed secret-free realm template. Session absolute and idle lifetime cannot exceed verified ID-token expiry or the access-token lifetime reported by the authenticated token endpoint (use the earlier deadline); expired sessions require login again.

Opaque `__Host-DKP-SESSION`: Secure, HttpOnly, SameSite=Lax, Path=/, no Domain. Rotate and invalidate old session ID after successful callback and tenant selection, and rotate CSRF on selection. Anonymous login-state sessions never grant authenticated access. Logout invalidates locally first and clears the cookie. BP-01 does not initiate provider logout. Built-in GET logout and alternative mutation routes must be disabled or denied.

Every unsafe BFF session route requires authenticated session, constant-time matching session CSRF value in `X-CSRF-TOKEN`, and exact configured `Origin`. Enforce on JSON and every other accepted content type; unsupported types reject before mutation. No blanket CORS or permissive development profile. CSRF rejection cannot rotate or change session state.

## Versioned wire contract

Publish OpenAPI 3.1 under `contracts/openapi/v1/`; generated Java/TypeScript SDKs remain reproducible and checked. Identity fields are strings, tenant IDs nonempty strings; IDs are opaque, with documented bounded length. Requests reject unknown fields and oversized bodies. Responses use `Cache-Control: no-store`.

| Method/path | Input | Success | Failure |
|---|---|---|---|
| GET `/api/v1/session` (BFF) | opaque cookie | 200 `{issuer, subject, displayName, tenants: string[], selectedTenant: string|null, csrfToken}` | 401 no authenticated session; 503 membership unavailable |
| POST `/api/v1/session/tenant` (BFF) | `{tenantId}` plus Origin and X-CSRF-TOKEN | 200 new safe session DTO, rotated session/CSRF | 400 malformed; 401 unauthenticated; 403 guards/membership; 415 unsupported type; 503 authority unavailable |
| POST `/api/v1/session/logout` (BFF) | empty JSON object plus Origin and X-CSRF-TOKEN | 204 local session invalidated | 400 malformed; 401 unauthenticated; 403 guards; 415 unsupported type |
| GET `/api/v1/security/context/{service}` (BFF) | service enum dm/kb/search; selected session tenant | 200 `{service, issuer, subject, tenant}` | 401 unauthenticated/expired; 403 no selection/denial; 404 unknown target; 503 upstream unavailable |
| GET `/api/v1/security/context` (each domain API) | bearer token, X-DKP-Tenant selector | 200 `{service, issuer, subject, tenant}` | 401 invalid token; 403 missing/unauthorized tenant; 503 unavailable authority |

Error body is `{code: string}` with bounded stable public codes; no provider diagnostics, token material or sensitive configuration. Runtime probes preserve the minimal existing payloads. Anonymous unsupported routes remain non-success without sensitive management data.

Fixed upstream service enum maps to configuration-controlled hostnames and exact context paths only. Caller headers never supply authority or arbitrary URL. Rebuild forwarded headers from an allowlist with only server-held bearer, verified selector and safe correlation ID; strip browser Authorization, tenant and identity headers. Disable redirects, cap response bytes and timeout, validate returned DTO schema and actor/tenant/service parity. Unexpected upstream payload/status is a safe failure, never relayed HTML or credentials.

## Local runtime

Portal nginx terminates HTTPS and proxies BFF routes on the same origin; domain APIs remain independently protected. Proposed fixed endpoints: portal `https://localhost:18443`, issuer `https://idp.localhost:18444/realms/dkp`. The issuer hostname resolves in the browser and Compose network; certificate SANs cover the actual names. Tests verify exact issuer/redirect consistency rather than bypassing validation.

An `infra/docker-compose/` bootstrap script generates local CA, TLS keys/certificates, client secret and synthetic development passwords into ignored `local-data/security/`, restrictive directory/file permissions, without printing secrets or changing global trust. Secret-free committed realm template fixes audiences, mapper ownership, user profile restrictions, exact callback/origins and 300-second token lifetime. Generated import material is ignored. Java uses a dedicated generated trust store; isolated browser E2E explicitly trusts the local CA. No secrets in images, tracked env files, tests, snapshots or CI logs.

Compose includes four app services, portal and Keycloak, no unused infrastructure. Pin images/digests, non-root users, capabilities/read-only protections and loopback exposure where applicable. BFF single-instance ephemeral sessions mean restart forces reauthentication; durable multi-instance session storage remains BP-16 gate. IdP local store holds synthetic development identity only, not domain persistence.

## Acceptance and test matrix

| Area | Required evidence |
|---|---|
| API trust | All three APIs: good token and two allowed tenants; missing bearer; wrong issuer/audience/azp/type/algorithm/key; absent sub/exp/kid; expired and future nbf; invalid signature; ID token rejection |
| Key lifecycle | Initial JWKS outage, expired cache outage, bounded known-key cache, overlap rotation, unknown kid denial and acceptance after refresh, retired-key rejection |
| Membership | Explicit selection; forged headers; cross-tenant/direct-API bypass; malformed/empty snapshots; same sub different issuer; external claim ignored; entitlement denial/unavailability; admin grants nothing |
| OIDC | Real Keycloak code flow; state/nonce mismatch, missing/invalid verifier, wrong redirect URI and ID-token issuer/audience; no authenticated session on failure |
| Session | Cookie attributes; fixation/rotation on callback and selection; old ID rejection; safe DTO; no browser token custody; idle/absolute/token expiry; restart reauthentication; local logout; GET logout denied |
| CSRF/Origin | Both guards required, exact origin, JSON and other accepted types; rejected requests preserve state; unauthorized selection denied |
| Proxy | Fixed destinations only, path escapes/unknown targets never forwarded, forged headers stripped, redirects disabled, timeout/oversize/malformed/mismatched DTO fail safely |
| Portal E2E | Real provider login → tenant selection → all three independent contexts → logout; accessible error/session-expiry states; no local/session storage tokens |
| Architecture/contracts | Four-service hexagonal and ownership checks with negative fixtures; Java/TS SDK regeneration and compatibility; all changed source ≥75% automated coverage plus real-runtime E2E |
| Supply chain/runtime | Maintained pinned versions/digests, dependency/secret scans, generated material untracked, HTTPS/probes/container checks, no global trust changes |

Use synthetic signing keys and deterministic clocks for negative/unit tests, framework HTTP integration for actual filters, and real Keycloak/HTTPS Compose for browser flow. Stub provider fixtures are permitted only for controlled negative protocol cases; they do not replace real-provider acceptance. Record test totals, coverage, commands, CI URLs, environment and limitations. No skipped test or fabricated success counts as evidence.

## Rollout, rollback and remaining gates

No production deployment. Review PR into dev with green required CI and independent implementation review. Rollback is reverting this isolated feature or stopping its local composition; no domain database migration exists. Full-project queue continues immediately after reviewed and verified integration. BP-16 durable sessions/workload mTLS and production trust configuration remain explicit. Do not describe BP-01 as complete until its real-runtime evidence passes.

## Research provenance

Inspected exact published Micronaut Security 5.4.1 source jars: default issuer comparison is weaker than exact equality; missing expiration and missing key ID need explicit policy; default CSRF content-type matching excludes JSON; default JWKS cache is too long. These facts justify narrow validation extensions while retaining framework crypto/protocol code.

- [RFC 10017, browser BFF](https://www.rfc-editor.org/rfc/rfc10017.html#section-6.1)
- [RFC 9700, OAuth security](https://www.rfc-editor.org/rfc/rfc9700.html)
- [OIDC Core](https://openid.net/specs/openid-connect-core-1_0.html)
- [Micronaut JWT 5.4.1 sources](https://repo.maven.apache.org/maven2/io/micronaut/security/micronaut-security-jwt/5.4.1/micronaut-security-jwt-5.4.1-sources.jar)
- [Keycloak 26.8.1 release](https://www.keycloak.org/2026/10/keycloak-2681-released)

An away Claude decision selected this BP-01 design with mandatory amendments. The approval record must preserve its conditions and independent review outcome; it is not production security approval.

## Delegated design conditions (verbatim)

DECISION: Adopt Option A as the BP-01 design and write the OpenSpec change with the nine amendments below folded in verbatim; do not widen scope beyond them.

RATIONALE: Option A matches §7.1 (real issuer/audience/signature checks, browser-supplied tenant never trusted, non-permissive standalone authorization), §14 BP-01 acceptance (standalone default deny, nonpermissive integration stubs) and §15 (CID-06 SPIFFE/mTLS stays a staging gate). The amendments only close gaps the packet itself exposed (framework defaults for issuer/exp/CSRF/kid, claim-to-issuer binding, pinned versions, contract and architecture-test coverage) without adding features or production approval. Nothing in Option A breaks a CONSTRAINT. Assumption: the Keycloak 26.8.1 and Micronaut Security 5.4.1/platform 5.2.4 facts in the packet are correct; I did not re-verify them.

ACTIONS:
1. Create `openspec/changes/dkp-002-security-foundation/proposal.md`, `design.md`, `tasks.md` and `specs/<capability>/spec.md` deltas (new capabilities, e.g. `browser-bff`, `identity-trust`, plus MODIFIED deltas to `openspec/specs/local-runtime/spec.md` and `openspec/specs/service-foundation/spec.md`). Record Option A as written, then add amendments 2–9.
2. Amendment, framework defaults: the spec MUST require explicit narrow validation beans (Micronaut `JwtClaimsValidator`-style, no custom crypto): exact string issuer match per configured issuer profile, `exp` and `sub` mandatory, `aud` must contain the service's own audience, `azp` in allow-list, `typ=Bearer`, `alg=RS256`, `kid` mandatory and unknown `kid` denied until a successful bounded JWKS refresh (TTL ~30s, fail closed on timeout). CSRF MUST be enforced on every state-changing BFF route regardless of content type (override the JSON skip) AND require exact `Origin` equality with the portal origin.
3. Amendment, claim binding: the `dkp_tenants` snapshot claim is mapped ONLY when the token issuer equals the standalone Keycloak issuer profile. For the external OIDC profile the claim is ignored and tenant membership comes solely from the entitlement port, which returns unavailable (HTTP 503, never allow) until configured. State in the spec that the snapshot is a local issuer contract, not an ACL or entitlement authority, and that future DM/KB ports remain authoritative.
4. Amendment, session/token lifetime: pin the standalone realm access-token lifespan explicitly in the committed realm export (no IdP default), bound the BFF session to it, no refresh tokens, re-authentication after expiry. Rotate the session identifier on login callback and on tenant selection. Cookie: `__Host-` prefix, `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, no `Domain`. Logout is POST + CSRF + Origin, invalidates the local session first; if RP-initiated IdP logout is used it MUST pass `id_token_hint` and a registered exact `post_logout_redirect_uri`.
5. Amendment, login hardening tests: `state` and `nonce` mismatch, missing/invalid PKCE verifier, wrong `redirect_uri`, and ID-token `aud`/`iss` mismatch MUST each be negative-tested at the BFF.
6. Amendment, module/contract shape: add `services/browser-bff` with the same five layers (`domain`, `application`, `adapters-in`, `adapters-out`, `bootstrap`) and register it in `settings.gradle`; extend `architecture-tests` so hexagonal direction and logical DB ownership rules cover the BFF (BFF owns no database). Publish the BFF's fixed GET security-context routes and the per-service `GET` security-context endpoints as versioned OpenAPI under `contracts/` so SDK generation and CID-14 parity apply. Allow-listed upstreams are fixed hostnames and exact paths; any other host/path returns 404 without forwarding; caller identity headers are stripped before forwarding.
7. Amendment, pinned selections: record Keycloak 26.8.1 (by image digest in `infra/docker-compose/compose.yaml`) and Micronaut Security 5.4.1 / platform 5.2.4 as BP-01 selections in the change's `design.md` under CID-01/CID-06, marked "selection for standalone/local, not promotion to accepted production trust config".
8. Amendment, local runtime: generated local CA, TLS certs, client secrets and development passwords live under an ignored directory (add the path to `.gitignore`), are created by a script in `infra/docker-compose/`, and never touch machine-global trust; the BFF compose service runs as a single replica with a comment that the in-memory session store is the BP-16 gate. Portal nginx terminates TLS and proxies the BFF under the same origin.
9. Amendment, authorization defaults: all resource-action ports deny/unavailable, the generic admin role grants nothing, the workload identity port returns unavailable and ignores any SPIFFE-style header. Tests MUST cover each tenant/browser/API/IdP happy and negative case in the spec with the ≥75% coverage floor plus a Playwright E2E login → tenant select → security-context → logout happy path. Then run `openspec validate dkp-002-security-foundation --strict`, perform the independent spec review, record automatic approval, and proceed to implementation.

VERIFY: `ls openspec/changes/dkp-002-security-foundation/` lists proposal, design, tasks and spec deltas; `grep -l "dkp_tenants" openspec/changes/dkp-002-security-foundation/**/*.md` and `grep -n "__Host-" openspec/changes/dkp-002-security-foundation/**/*.md` both hit; `openspec validate dkp-002-security-foundation --strict` exits 0; `git status` shows no credential or certificate files staged.

CONFIDENCE: medium — it would become high once the caller confirms from the Micronaut Security 5.4.1 docs that the issuer/exp/audience claim validators and CSRF content-type setting are configurable as the packet states.

REVERSIBLE: yes — the change is a spec directory that can be deleted or amended before any runtime code exists.


Latest owner instruction explicitly authorizes automatic PR merging into dev for specifications, implementation and all other developed tasks after independent review and passing checks. Production release remains separately gated.
