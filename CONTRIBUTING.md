# Contributing

Use an OpenSpec change proposal for behavior or contract modifications. Add unit, integration, contract and security-negative tests as appropriate. CI checks must pass before review. Do not upload personal, customer, employer or confidential content. Keep all documentation in English.

Start from current `origin/dev` in an isolated feature worktree and open a PR targeting `dev`. Follow [the delivery protocol](docs/development/worktree-workflow.md). The owner's standing authorization permits automatic specification approval and merging after independent review and passing applicable checks until revoked; architecture/security changes still require explicit reconciliation and production deployment is separately gated.

Run the foundation checks documented in README. Java coverage is enforced across each service's implemented production layers; empty layers are not treated as covered. Frontend coverage includes its entry point. Do not disable tests, exclude implemented source or lower thresholds to pass. Generated Java/TypeScript SDKs are regenerated and compiled, not hand-edited; run `pnpm contracts:generate` after changing the operational OpenAPI contract and `pnpm contracts:check` before review.
