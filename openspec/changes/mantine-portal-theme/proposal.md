# Mantine portal foundation and persistent themes

## Why

Owner requirement [#102](https://github.com/petarnenov/document-knowledge-platform/issues/102) selects Mantine for existing and future administration screens, with light/dark preference persistence. Specification task [#105](https://github.com/petarnenov/document-knowledge-platform/issues/105) was created before this work. The current shell uses raw controls and component-local dark/light state; a reload loses the preference. This common UI foundation is needed now rather than deferred to BP-14.

## What Changes

- Adopt exact `@mantine/core@9.7.4` and `@mantine/hooks@9.7.4`, one provider and reviewed shared theme/component tokens, preserving the current compatible React/TypeScript/Vite baseline.
- Migrate the shell and applicable existing overview, foundation, identity, tenant, document metadata, ACL and hold controls. Integrate approved upload/original-review controls if present at implementation rebase; subsequent screens use the same foundation.
- Keep dark as the first-visit default. Offer a visible keyboard-operable light/dark switch and persist only a validated non-secret preference across route navigation, reload, tenant changes and logout.
- Require accessible contrast, loading/error/disabled/focus states, storage-failure handling and meaningful component/browser regression evidence in both themes.

## Capabilities

### Modified Capabilities

- `portal-shell`: shared Mantine foundation, persisted theme semantics and accessible behavior with preserved identity/document authority boundaries.

## Impact

Frontend presentation, frontend dependency lock/SBOM, tests and documentation only after approval. No API/schema/operation-ID, server authorization, tenant isolation, database ownership, content-trust, upload limits, RustFS, processing or production-gate changes. Future unimplemented business screens are not fabricated or implemented by this change. Existing BP-03 sequencing and its pending runtime evidence remain independent. This proposal neither approves itself nor authorizes production release.
