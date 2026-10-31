# Design

## Context and authority

Owner #102 and specification #105 select Mantine now for the common portal foundation. System Design §11 already requires React/strict TypeScript, dark default/light option, accessibility, TanStack Query, Zod and backend authority. Canonical `portal-shell`, `browser-bff`, `document-management` and approved `dkp-004-rustfs-upload` remain authoritative. This change modifies only theme behavior and shared presentation. BP-14 still delivers its remaining business screens and complete journeys; it does not postpone this migration.

Baseline inspected at dev `beae205`: `src/App.tsx` holds a dark/light `useState` and `.shell[data-theme]` supplies custom variables; no persisted theme key exists in current source. `main.tsx` owns StrictMode/BrowserRouter; `Identity.tsx` and `Documents.tsx` own existing session/query/validation flows. `e2e-real/identity.spec.ts` exercises actual HTTPS IdP/BFF/DM/PG and currently asserts empty browser storage. The specification adds one explicitly allowed non-secret key, not general storage permission.

## Dependency selection and compatibility contract

Research date: 2026-10-31. Candidate exact runtime pins are **@mantine/core 9.7.4** and **@mantine/hooks 9.7.4** together. Official release 9.7.4 was published October25 and is marked latest; core metadata requires `react ^19.2.0`, `react-dom ^19.2.0`, and hooks exactly9.7.4; hooks requires React^19.2.0. Existing React/react-dom19.3.1 satisfy these ranges. Neither package declares a Node engine constraint. This establishes peer compatibility, not a claim that this repository has already built with them.

Preserve React/react-dom19.3.1, TypeScript6.0.3, Vite8.3.5, plugin-react6.1.2, Vitest5.0.5, Playwright1.64.1, TanStack Query5.105.0, Zod4.6.7, Node24.22.1 and pnpm12.16.1 unless an independently reviewed compatibility fix proves necessary. Do not force peer resolutions, downgrade security fixes, float ranges or silently upgrade unrelated dependencies. Implementation must prove strict compilation, production CSS build, browser execution and the locked transitive supply chain. Failure blocks delivery rather than implying compatibility from semver alone.

Primary sources:

- [Official release9.7.4](https://github.com/mantinedev/mantine/releases/tag/9.7.4).
- [Official core registry metadata](https://registry.npmjs.org/@mantine/core/9.7.4) and [hooks metadata](https://registry.npmjs.org/@mantine/hooks/9.7.4), retrieved using TLS-verifying curl. Published integrity: core `sha512-c7aTGELNn74CxG9i5/tq9BHGh0lKdKmGGz+9iDF+gR7Ipt4U1TMM286lAmLpRrKL2bR+UEaN4blZ8UJmOuGJ4Q==`; hooks `sha512-j6Xf9Sc1fhhGEKXg6BZA2geyP/Czqvo6Rsi0IduOCWgwl/MAAJbKRjRc7xe2+9vePLdfqLZqRIAOY5rta88uNA==`. The implementation lockfile must resolve these exact artifacts.
- [Official Vite setup](https://mantine.dev/guides/vite/) specifies core/hooks, core CSS and MantineProvider. Use the distributed precompiled `@mantine/core/styles.css` once plus plain CSS/CSS variables, avoiding additional PostCSS plugins unless their features are actually required. The guide's optional authoring setup is not permission to add unpinned tooling.
- [Official color-scheme API](https://mantine.dev/theming/color-schemes/) documents provider default, root attribute, persistent manager and custom manager interface. Mantine's default is light and accepts auto; this project deliberately sets dark and narrows persisted values to light/dark.

## One shared component and theme foundation

Mount one stable `MantineProvider` around the existing router/application tree. Import core CSS once before application overrides. Centralize `createTheme` tokens and component defaults: semantic surfaces, text, borders, accents, status colors, typography, spacing, radii, breakpoints and visible focus. Both modes must use these reviewed tokens; do not retain an independent `.shell[data-theme]` state/palette or add per-route providers. Plain layout CSS may remain, referencing shared Mantine tokens. Use actual styled Mantine components, not an unused provider around an otherwise unmigrated portal.

Migration inventory:

| Existing surface | Shared foundation and preserved behavior |
|---|---|
| Header/sidebar/footer/content, skip link, navigation, headings, cards and not-found | Mantine AppShell/layout/Typography/Paper/NavLink as appropriate; semantic header/nav/main/footer, router Links and aria-current remain real and keyboard accessible |
| Theme control | Mantine Switch with stable visible and accessible label **Light theme**, checked exactly when light; nearby current-mode text makes dark/light meaning explicit |
| Sign-in anchor, logout/retry/context controls and tenant selector | Mantine Button/Anchor/NativeSelect with existing links, labels, options, submit and disabled/busy semantics; no replacement of IdP screens |
| Document metadata/ACL/hold forms and results | Mantine TextInput/Textarea/NativeSelect/Button/Alert/loading/status equivalents; preserve existing form values, exact numeric/string validation, authorization affordances and command handlers |
| Approved upload/original review if integrated by implementation rebase | Same styled controls/progress/error foundation; preserve real transfer, scan, receipt discovery and exact-version authorization; no preview or processing invention |

Prefer native-select behavior through Mantine NativeSelect for current selects. Do not change payload parsing, revision precision/JS-safe bounds, idempotency lifetime, validation error association, or browser navigation semantics merely to fit a component API. Centralize reusable defaults rather than duplicating wrappers for every HTML element. Subsequent screens must use the same provider, tokens and controls; exceptional native/custom widgets require documented accessibility and foundation integration in their own reviewed changes.

## Preference model and lifecycle

The only persisted theme record is localStorage key **`mantine-color-scheme`**, raw exact string `light` or `dark`. Reuse a valid existing value at that key; there is no legacy persisted key in the inspected baseline to migrate. Absent, empty, malformed, JSON-quoted, unknown, oversized or `auto` values resolve to dark, independently of OS preference. No automatic mode is introduced. Do not parse objects or derive identity, tenant, roles, network endpoints or server scope from this value.

Implement one validated Mantine-compatible color-scheme manager/resolver used for initialization and provider state. Initialize the root `data-mantine-color-scheme` from the same resolver before rendering the application, so a stored light preference does not paint dark application content first. This is a client-rendered Vite SPA: no SSR or inline-script/CSP relaxation is needed. Avoid competing component state, document reload, keyed provider/router remount or duplicate StrictMode storage subscriptions. Valid external storage updates synchronize other same-origin tabs; deletion/invalid updates select dark. Unrelated keys/storage areas/events are ignored and subscriptions cleaned up.

Storage access, write, clear or subscription failure must not crash or block navigation, sign-in or mutation. Keep the user's current selection in memory for that tab when persistence is unavailable; a subsequent reload may default dark. Catch storage failures without logging storage contents or credentials. Theme survives route changes, full reload, tenant switch and logout when storage is available. It is intentionally browser-origin scoped, shared across accounts on that browser, carries no account/tenant identifiers and is not a server preference or authority record. Never clear unrelated storage. Tests that previously required completely empty localStorage must assert this exact allowlist with exact light/dark values; sessionStorage remains empty of application authority data and all browser token/credential exclusions remain enforced.

Theme changes must preserve mounted form values, selected document, in-flight requests, focus, current route and scroll as appropriate. They must not mutate Query keys, invalidate/refetch protected caches, submit commands, change CSRF/idempotency guards or resurrect hidden stale data. Existing issuer/subject/tenant/session keying, cancellation and protected-cache removal on context change/401/logout remain unchanged. Theme may persist while protected context is destroyed.

## Accessibility and security constraints

Test both modes, not only Mantine defaults. Text contrast must meet WCAG AA4.5:1, or3:1 for qualifying large text; meaningful component boundaries and focus indicators meet3:1 against adjacent colors. Status is conveyed by text/semantics as well as color. Preserve labels, error descriptions, live status/alert announcements, disabled/read-only states, native form behavior, focus visibility/order, skip-to-main, active navigation and reduced-motion behavior. No keyboard traps; Space operates the switch with coherent checked state and focus retained. At390px and desktop1280px, and200% zoom, controls/content must remain usable without page-level horizontal overflow; long untrusted titles/errors wrap safely. Do not assert that a library or screenshot alone proves accessibility.

All content remains escaped text; no unsafe HTML, unsafe link endpoints or CSS derived from untrusted business fields. Same-origin BFF remains the only portal business boundary; real OIDC, HttpOnly secure cookies, CSRF/Origin checks, tenant isolation, ACL/anti-enumeration, upload trust and dependency fail-closed states are untouched. No local authorization fallback, storage of tokens/content/server responses, new business route or API/schema/operation-ID change is allowed. Contract and generated-SDK drift gates remain applicable despite a presentation-only implementation.

## Rollout and approval boundaries

Spec-only first, independent review, strict validation and recorded approval before any runtime/dependency edit. Implementation/review issues must exist before execution. Rebase onto current dev before coding, inventory any concurrently integrated portal surfaces and migrate them without reverting approved work. Verify all gates in verification.md, review independently, PR into dev, require passing checks and integration evidence. #102 stays open until real implementation/integration evidence is recorded. A dependency scan failure, inaccessible mode or broken journey blocks completion. A rollback reverts the implementation as one reviewed unit; it may leave the harmless preference key for forward compatibility and never touches security/session data. Production release remains separately gated.
