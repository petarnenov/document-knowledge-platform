Tracking: parent [#6](https://github.com/petarnenov/document-knowledge-platform/issues/6). Existing execution was retrospectively linked when issue-first tracking was adopted; issue creation is not claimed to predate that work.

## 1. Requirements, contracts and approval

- [x] 1.1 [#8](https://github.com/petarnenov/document-knowledge-platform/issues/8) Root: map BP-01 acceptance and full-project queue, preserve security/production gates and latest automatic-merge authorization.
- [x] 1.2 [#9](https://github.com/petarnenov/document-knowledge-platform/issues/9) Root: inspect exact managed security/session sources and record unsafe defaults, selected provider version and public protocol guidance.
- [x] 1.3 [#10](https://github.com/petarnenov/document-knowledge-platform/issues/10) Root: author proposal, capability deltas, design, frozen route/DTO contract and positive/negative test matrix.
- [x] 1.4 [#11](https://github.com/petarnenov/document-knowledge-platform/issues/11) Independent reviewer: review specification against architecture and threat cases; root resolves findings and runs strict OpenSpec validation.
- [x] 1.5 [#12](https://github.com/petarnenov/document-knowledge-platform/issues/12) Root: record automatic specification approval with reviewed artifact digest and resolved review evidence before runtime changes.

## 2. Identity contracts and domain API enforcement

- [x] 2.1 [#13](https://github.com/petarnenov/document-knowledge-platform/issues/13) Security owner: add immutable technical identity/authority contracts and deny/unavailable resource/workload ports with tests.
- [x] 2.2 [#14](https://github.com/petarnenov/document-knowledge-platform/issues/14) Security owner: implement framework-backed strict JWT/JWK policies, bounded JWKS behavior and issuer-bound membership; test all claim, rotation and outage cases.
- [x] 2.3 [#15](https://github.com/petarnenov/document-knowledge-platform/issues/15) Security owner: integrate all three APIs with independent tenant selection and safe security-context DTO; HTTP-test direct bypass and tenant negatives.
- [x] 2.4 [#16](https://github.com/petarnenov/document-knowledge-platform/issues/16) Security owner: extend Gradle/architecture coverage for four services and narrow technical-module exceptions, including negative boundary fixtures and ≥75% changed-source coverage.

## 3. Browser BFF

- [x] 3.1 [#17](https://github.com/petarnenov/document-knowledge-platform/issues/17) BFF owner: create five independent layers, framework confidential OIDC S256 login and server token custody; test protocol tampering and no authenticated session on failure.
- [x] 3.2 [#18](https://github.com/petarnenov/document-knowledge-platform/issues/18) BFF owner: implement constrained cookie/session lifetime, rotation, safe session DTO and local-only logout with lifecycle tests.
- [x] 3.3 [#19](https://github.com/petarnenov/document-knowledge-platform/issues/19) BFF owner: enforce CSRF and exact Origin on every accepted unsafe request type, deny alternate mutation routes and test state preservation.
- [x] 3.4 [#20](https://github.com/petarnenov/document-knowledge-platform/issues/20) BFF owner: implement fixed context delegation, stripping/validation/timeout/response limits; test SSRF, redirect and forged authority cases with ≥75% coverage.

## 4. Portal and versioned contracts

- [x] 4.1 [#21](https://github.com/petarnenov/document-knowledge-platform/issues/21) Root: publish OpenAPI identity contracts and regenerate checked Java/TypeScript SDKs; validate compatibility and deterministic generation.
- [x] 4.2 [#22](https://github.com/petarnenov/document-knowledge-platform/issues/22) Portal owner: implement same-origin login/session/tenant/context/logout UI, accessible failures and real identity states; use contract types without browser token storage.
- [x] 4.3 [#23](https://github.com/petarnenov/document-knowledge-platform/issues/23) Portal owner: add component and browser tests for identity lifecycle, expiry/denial/unavailability and existing navigation/theme regression; meet ≥75% coverage.

## 5. Real local identity runtime

- [x] 5.1 [#24](https://github.com/petarnenov/document-knowledge-platform/issues/24) Root: pin Keycloak image digest and add secret-free realm template with exact issuer/client/audiences, admin-only tenant mapping, fixed token lifespan and callback/origin restrictions.
- [x] 5.2 [#25](https://github.com/petarnenov/document-knowledge-platform/issues/25) Root: implement/test ignored local security bootstrap, restrictive permissions, dedicated CA trust and idempotency without secret output or global trust changes.
- [x] 5.3 [#26](https://github.com/petarnenov/document-knowledge-platform/issues/26) Root: wire HTTPS portal/BFF/IdP and three APIs into hardened Compose; document ephemeral single-BFF session and production/workload gates.
- [x] 5.4 [#27](https://github.com/petarnenov/document-knowledge-platform/issues/27) Root: run real Keycloak HTTPS Playwright login → tenant select → all contexts → logout E2E plus runtime security/probe checks; preserve failure evidence and fix defects.

## 6. Verification and delivery

- [x] 6.1 [#28](https://github.com/petarnenov/document-knowledge-platform/issues/28) Root: update pinned CI for four services, contracts, architecture, portal, real identity E2E, coverage, supply-chain/secret scans and runtime checks.
- [x] 6.2 [#29](https://github.com/petarnenov/document-knowledge-platform/issues/29) Root: execute affected gates and record exact commands/results/coverage with no skipped acceptance evidence; audit every requirement/scenario against tests.
- [x] 6.3 [#30](https://github.com/petarnenov/document-knowledge-platform/issues/30) Independent implementation reviewer: review final diff, security boundaries, tests and acceptance evidence; root resolves and retests findings.
- [x] 6.4 [#31](https://github.com/petarnenov/document-knowledge-platform/issues/31) Root: open PR targeting dev with evidence and rollback plan, run required CI; automatically merge into dev only after independent review and all required checks pass.
- [x] 6.5 [#32](https://github.com/petarnenov/document-knowledge-platform/issues/32) Root: publish completion/gate status, archive after reviewed integration, and continue independent ready full-project work with 15-minute status reports.

Completion evidence and canonical archive passed independent review #51 and strict validation (6/6). Final evidence-PR integration and issue closure remain tracked in #32; a checked local delivery step does not claim that later PR has already merged.
