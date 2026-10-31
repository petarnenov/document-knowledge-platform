# Environment configuration

BP-01 adds standalone OIDC, a trusted browser BFF and independently protected identity-context APIs. Only minimal process probes are anonymous operational endpoints. Uploaded document/resource capabilities remain denied until their owning domain packages exist. A tenant selector is an untrusted choice, never authority by itself.

## Local identity runtime

Use JDK 25, Docker Compose and OpenSSL. Run from the repository root:

```bash
python3 infra/docker-compose/bootstrap-security.py
docker compose --env-file local-data/security/runtime.env -f infra/docker-compose/compose.yaml up --build --wait --wait-timeout 240
python3 tools/runtime/smoke.py
node tools/runtime/identity-e2e.mjs
docker compose --env-file local-data/security/runtime.env -f infra/docker-compose/compose.yaml down
```

The portal is `https://localhost:18443`; issuer is exactly `https://idp.localhost:18444/realms/dkp`. Domain probe ports remain loopback 18081/18082/18083. BFF has no published direct host port. The generated realm contains only synthetic identities. Read development login values locally from ignored `local-data/security/runtime.env`; never copy them into tickets, logs, commits or screenshots. No fixed development password is committed.

The bootstrap is idempotent: it verifies complete existing material and preserves credentials. It rejects incomplete or overpermissive paths rather than overwriting them. Files are owner-only (0600), directory 0700. Runtime UID/GID match that local non-root owner so private bind mounts stay private; Compose cannot run with a root UID. Certificates expire in 30 days. Regeneration requires stopping this task's local composition and intentionally removing its generated material; it invalidates sessions and local IdP state.

No script changes host-global trust. Java uses the generated CA trust store through a private JVM argument file (not a password-bearing JAVA_TOOL_OPTIONS log). The isolated browser runner trusts the public CA in its disposable container only. A regular browser needs explicit local CA trust to visit these URLs; the test runner supplies reproducible isolated trust without disabling verification. The local IdP uses an ephemeral synthetic development store; restart can require login again. BFF is one instance with ephemeral sessions. Durable multi-instance sessions remain BP-16.

## Trust and session settings

Every API consumes OIDC_PROFILE (`standalone` or `external`), OIDC_ISSUER, OIDC_STANDALONE_ISSUER, OIDC_JWKS_URL and OIDC_AUTHORIZED_PARTIES. Audiences are independently fixed to dkp-dm/dkp-kb/dkp-search. Missing trust settings close protected capabilities; probes remain available. Key fetch uses a fixed HTTPS endpoint, bounded timeout/size and a maximum 30-second lease. Caller-supplied key URLs or identity headers cannot alter trust.

BFF additionally uses OIDC_ENABLED, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, DKP_PORTAL_ORIGIN and fixed DKP_DM_URL/DKP_KB_URL/DKP_SEARCH_URL upstreams. The confidential browser client uses code+S256 PKCE and an exact registered callback. Tokens stay server-side; no refresh. Sessions expire no later than the token lifetime, rotate on login and tenant selection, and use an opaque Secure/HttpOnly/host-only SameSite=Lax cookie. Tenant selection and local logout require exact Origin plus CSRF for accepted JSON bodies. Unsupported content types and alternate mutation routes do not mutate state.

Standalone membership is the administrator-controlled signed dkp_tenants snapshot bound to the exact configured standalone issuer: BFF uses the validated ID token and each API independently validates the access token. It grants no document/collection action. External issuer mode ignores this local claim and calls the entitlement port; an unconfigured authority returns unavailable, never allow. Resource and workload ports remain deny/unavailable; forged SPIFFE headers are not certificate identity.

## Environment boundaries

Environment-specific settings belong in runtime configuration, never frontend bundles or packaged credentials. Use environment-owned external secrets for deployed environments, independent credentials and migrations for each domain database, and fail startup or close affected capabilities when required trust is absent. Development must exercise the same tenant and authorization checks.

No production deployment is provided by this change. Actual workload SPIFFE/SPIRE mTLS, external corporate entitlement integration, durable sessions, residency, backups, operational ownership and release acceptance remain explicit gates in the full-project queue. Automatic specification approval and reviewed merging apply to development integration, not production release.
