# Specification approval record

Approved for BP-01 implementation on 2026-10-31 under the owner's standing automatic specification authorization, after independent review and strict validation. This records development specification approval only; it does not invent production trust, architecture/security-control sign-off or release readiness.

## Review evidence

- Independent architecture review: initial blocker identified ambiguous BFF membership source. Resolved with verified ID-token snapshot, exact issuer/BFF audience/subject binding, separate API access-token snapshots, external entitlement-only membership and negative tests. Delta review: PASS, no remaining blockers.
- Independent security specification review: PASS, no blocking findings. Implementation must prove protocol HTTP negatives, genuine certificate trust, admin-only mapper control, session lifecycle, bounded JWKS and constrained proxy behavior.
- Strict change validation passed. All-project `openspec validate --all --strict` passed: 5 elements, 0 failures.
- Latest owner instruction expressly authorizes automatic PR merging into dev for specifications, implementation and all developed tasks after independent review and required passing checks.

## Reviewed artifact fingerprint

SHA-256 of the following manifest: `24275747c9ee3b9b0cccd7428a4c859bad5ed7f7cf3ba38c667e2dff49b52f6d`. The task checklist will evolve during execution; security or contract changes require proportionate re-review.

```text
787d9b17787eec3fb1df4ad47d6ecfb6e9ac99a8c39f783ca9068e4687381cf3  design.md
1c665ff7c009b33ece53384539322551f4c7c3e205109fd725400f62cd657bed  proposal.md
ac69cb8e1cde13855b7604532e17c26e0a097f5a061a9c7dbb7bd64a1b770ed3  specs/browser-bff/spec.md
ac0f8e3763038b3eb66169b373bee0f997be633f19c7efd2a4d11f0a52ee98dd  specs/identity-trust/spec.md
dde150fe8c61466bb6a29d7d1cca3260cd9d4ae4d3e3cccf277cb545b6b7f65a  specs/local-runtime/spec.md
aa2f8abdd555b471f485eb1681537e73f7e8521578b9139cd64e8734c7a54c73  specs/portal-shell/spec.md
69c80050c63d9069721a393d4203628b5434cd292bf552efe259834bb4d77c46  specs/service-foundation/spec.md
869b2c875c43033e84347f8b4c9f4f8373adc030a734a750ff1721c40f3e4769  tasks.md
```

## Delegated decision

Away Claude run `20261031T151413Z-claude-66713` selected Option A with mandatory amendments, preserved verbatim in design.md. Exact managed library source facts were independently researched before approval. No production action was delegated or authorized.
