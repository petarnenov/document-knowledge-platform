# OpenSpec

Feature work starts with an approved, reviewed OpenSpec change. Add the official OpenSpec structure when initializing the tool; do not invent tool-generated files or versions.

OpenSpec 1.16.0 initialized this repository with the official `spec-driven` schema and no tool-specific integration. Use the pinned workspace CLI:

```bash
pnpm exec openspec new change <change-id>
pnpm exec openspec validate <change-id> --strict
```

The owner authorized automatic approval of specifications and merging for this and future work on 2026-10-31 until revoked. Record an independent review, strict validation, the reviewed revision and the approval decision before writing implementation code. Keep implementation checkboxes unchecked until their outcomes are verified. Artifact completeness alone is not an approval or a production-readiness assertion.

DKP-001 is complete and archived under `changes/archive/2026-10-31-dkp-001-monorepo-foundation`; its four canonical capabilities are in `specs/`. `pnpm openspec:check` validates all current changes and canonical specifications, so archived change IDs are not hardcoded into the gate.
