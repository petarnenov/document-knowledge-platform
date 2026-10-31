## 1. Requirements, contracts and approval

- [x] 1.1 Root: map BP-01 acceptance and full-project queue, preserve security/production gates and latest automatic-merge authorization.
- [x] 1.2 Root: inspect exact managed security/session sources and record unsafe defaults, selected provider version and public protocol guidance.
- [x] 1.3 Root: author proposal, capability deltas, design, frozen route/DTO contract and positive/negative test matrix.
- [x] 1.4 Independent reviewer: review specification against architecture and threat cases; root resolves findings and runs strict OpenSpec validation.
- [x] 1.5 Root: record automatic specification approval with reviewed artifact digest and resolved review evidence before runtime changes.

## 2. Identity contracts and domain API enforcement

- [x] 2.1 Security owner: add immutable technical identity/authority contracts and deny/unavailable resource/workload ports with tests.
- [x] 2.2 Security owner: implement framework-backed strict JWT/JWK policies, bounded JWKS behavior and issuer-bound membership; test all claim, rotation and outage cases.
- [x] 2.3 Security owner: integrate all three APIs with independent tenant selection and safe security-context DTO; HTTP-test direct bypass and tenant negatives.
- [x] 2.4 Security owner: extend Gradle/architecture coverage for four services and narrow technical-module exceptions, including negative boundary fixtures and ≥75% changed-source coverage.

## 3. Browser BFF

- [x] 3.1 BFF owner: create five independent layers, framework confidential OIDC S256 login and server token custody; test protocol tampering and no authenticated session on failure.
- [x] 3.2 BFF owner: implement constrained cookie/session lifetime, rotation, safe session DTO and local-only logout with lifecycle tests.
- [x] 3.3 BFF owner: enforce CSRF and exact Origin on every accepted unsafe request type, deny alternate mutation routes and test state preservation.
- [x] 3.4 BFF owner: implement fixed context delegation, stripping/validation/timeout/response limits; test SSRF, redirect and forged authority cases with ≥75% coverage.

## 4. Portal and versioned contracts

- [x] 4.1 Root: publish OpenAPI identity contracts and regenerate checked Java/TypeScript SDKs; validate compatibility and deterministic generation.
- [x] 4.2 Portal owner: implement same-origin login/session/tenant/context/logout UI, accessible failures and real identity states; use contract types without browser token storage.
- [x] 4.3 Portal owner: add component and browser tests for identity lifecycle, expiry/denial/unavailability and existing navigation/theme regression; meet ≥75% coverage.

## 5. Real local identity runtime

- [x] 5.1 Root: pin Keycloak image digest and add secret-free realm template with exact issuer/client/audiences, admin-only tenant mapping, fixed token lifespan and callback/origin restrictions.
- [x] 5.2 Root: implement/test ignored local security bootstrap, restrictive permissions, dedicated CA trust and idempotency without secret output or global trust changes.
- [x] 5.3 Root: wire HTTPS portal/BFF/IdP and three APIs into hardened Compose; document ephemeral single-BFF session and production/workload gates.
- [x] 5.4 Root: run real Keycloak HTTPS Playwright login → tenant select → all contexts → logout E2E plus runtime security/probe checks; preserve failure evidence and fix defects.

## 6. Verification and delivery

- [x] 6.1 Root: update pinned CI for four services, contracts, architecture, portal, real identity E2E, coverage, supply-chain/secret scans and runtime checks.
- [x] 6.2 Root: execute affected gates and record exact commands/results/coverage with no skipped acceptance evidence; audit every requirement/scenario against tests.
- [x] 6.3 Independent implementation reviewer: review final diff, security boundaries, tests and acceptance evidence; root resolves and retests findings.
- [ ] 6.4 Root: open PR targeting dev with evidence and rollback plan, run required CI; automatically merge into dev only after independent review and all required checks pass.
- [ ] 6.5 Root: publish completion/gate status, archive after reviewed integration, and continue independent ready full-project work with 15-minute status reports.
