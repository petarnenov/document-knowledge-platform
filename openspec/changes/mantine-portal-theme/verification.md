# Verification plan and evidence ledger

This is an unimplemented specification. No runtime gate is claimed passed. Owner102 remains open. Author105 records spec validation only; independent review and recorded approval remain pending.

## Matrix required before implementation acceptance

Run meaningful component cases and browser journeys in **both light and dark**, using isolated browser contexts/storage for independent cases. Assertions target user-visible roles, names, checked state and rendered behavior; the root attribute alone is insufficient.

| Area | Positive evidence | Negative/regression evidence |
|---|---|---|
| Preference resolver/manager | Absent→dark; exact light/dark retained; each direction writes only allowed key; new-tab/reload/deep route retain preference | Empty, auto, JSON-quoted, object/markup, unknown and oversized strings→dark; get/set/remove/subscribe failure remains operable; unrelated keys intact |
| Lifecycle | Valid initial mode matches provider/root at first application render; tab event valid/deleted/invalid; StrictMode subscribe/unsubscribe | No duplicate listeners, unrelated key/storage event ignored; blocked storage does not break application, reload safely defaults dark |
| Switch | Visible Light theme switch checked iff light, current-mode text, mouse/Space, focus retained, both directions | Accessible name/state never contradict visible label; no reload/remount, no extra business requests/commands or protected refetch |
| Existing routes | Overview/foundation/identity/documents/not-found and active links, reload direct routes, desktop1280px/mobile390px,200% zoom | Skip-link Enter, keyboard order/focus, long text, missing/denied/disabled/loading/unavailable states; no keyboard trap/overflow |
| Existing forms | Theme switch preserves unsaved metadata/ACL/hold values, selection and focus; existing exact submit payload/guard tests | Revision/validation bounds, pending command/idempotency preserved, no double submit, no theme-derived Query keys |
| Accessibility | Actual rendered representative text4.5:1 (large3:1), controls/focus3:1; semantic labels/error associations/live status | Include primary/muted/error/success/nav/input/focus states in both palettes; compute composited colors against actual backgrounds, not token names or screenshot-only claims; reduced motion |
| Real identity | Actual HTTPS IdP login, verified session, authorized tenant,3service contexts, logout in each mode | Session expiry/401,403,503, reauth; theme never authorizes, no JWT/token/credentials; no mocks as substitute for real happy path |
| Real documents | Create/read/list/update draft, ACL/hold inspect/mutate as authorized in each mode, preserving exact receipt/revision behavior | Unauthorized/cross-tenant/revoked/expired/unavailable state and cache cleanup; theme persists but prior protected state never returns |
| Concurrent BP03 integration | When upload/original-review source is integrated, actual authorized upload→verify→append→discovery→exact-original download in each mode | Existing scan rejection, denied review, tenant/logout during upload and safe503 states still pass; no pretend preview/processing |
| Storage/bundle | Only `mantine-color-scheme` exact light/dark is newly allowed, stable after logout | Inspect all browser storage keys/values and bundle for authority/secret exclusion; replacing empty-storage assertions must not become wildcard permission |

No screenshot-only accessibility or mocked-only E2E acceptance. Existing API/BFF negatives remain independent; UI tests do not substitute for backend enforcement. With concurrent BP03 changes, use its approved current contracts and real runtime evidence rather than reasserting old always503 append behavior. Theme tests may mock deterministic failures at component boundaries, explicitly identified as such.

## Required checks

1. Before runtime changes: official OpenSpec1.16.0 `openspec validate mantine-portal-theme --strict`, independent review and recorded approval. All unchanged canonical and approved BP03 requirements remain intact.
2. Implement with tests and locked dependencies; `pnpm install --frozen-lockfile` must succeed in CI. Review exact core/hooks pins, lock integrity, peers and production CSS behavior; no hidden prerequisite upgrade.
3. `pnpm --filter admin-portal lint`, `typecheck`, `build`, `test:coverage`, `test:e2e` and `test:e2e:identity`. Keep existing coverage floors and require **at least75% changed automated source line and branch coverage**, including the preference manager and provider/control behavior; report aggregate and changed-area evidence. Do not exclude difficult new branches, dilute source with unrelated tests or disable existing assertions. Retain existing functions/statements75% global floors.
4. Execute the real HTTPS identity/document happy-path and applicable upload journey matrix in both themes against current integrated services. Record exact commands, commit, tests0skipped and runtime dependencies; unavailable evidence remains pending. Capture failures safely without tokens/content/secrets.
5. Root `pnpm openspec:check`, `contracts:check` (including SDK drift/compile), formatting, dependency/SBOM/OSV, secret and affected existing CI checks. Presentation scope does not waive existing supply-chain or contract gates. Adding accessibility tooling, if necessary, requires maintained exact pins and the same scans.
6. Independent implementation review and fixes, reviewed PR into dev, required PR checks and post-merge dev checks, integration SHA and delivery evidence. Close implementation/#102 only when their actual acceptance is complete; archive this change through official OpenSpec after verified delivery. No production acceptance inference.

## Current evidence

- Official dependency compatibility research and exact published integrity are recorded in design.md; this is candidate selection, not successful runtime installation.
- Official OpenSpec1.16.0 `validate mantine-portal-theme --strict`: PASS on2026-10-31; authored source only.
- Independent specification review: pending, review issue must predate delegation.
- Approval, implementation issues, dependency installation, source coverage, component/browser/security/runtime/CI checks, PR/integration and owner102 completion: pending.
