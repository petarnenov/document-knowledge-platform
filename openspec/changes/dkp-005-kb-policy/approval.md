# DKP-005 development specification approval

Approved on2026-10-31 under the owner's standing automatic specification authorization, after independent architecture171 and security172 final PASS and root strict/contract checks. Parent36; specification167; correction174. This approves development implementation of the reviewed BP04 contract only. It grants no production deployment, CID07/CID15, workload-trust, legal-policy or formal architecture/security-control sign-off. Specification PR/CI/integration remain required. The latest2026-11-01 owner instruction permits automatic dev merging after green tests and CI; independent review and all applicable acceptance gates remain mandatory (185).

## Accepted reviewed scope

KB-owned collections, audiences, membership, policy revisions and opaque complete-set policy snapshots preserve current collection ACL/noDENY checks at initial/final boundaries. Hidden complete scopes return404 before stale409; they cannot be filtered into a compatible policy result. ACTIVE transitions, no-op and replay recheck every current active member's DM sharing authority/classification, with rollback/outage/race behavior. Selected-tenant and explicit development classification staging does not invent production taxonomy or cross-tenant sharing rights.

Existing18 approved DM paths and18 nonAction schemas stay exact. The additive document.share-to-collection action preserves all9 original actions and approved document.content.review, with exact catalog/ACL/delegation compatibility. Strict mutation/snapshot assessment uses one bound all-or-nothing batch of up to100 documents per phase; unavailable defaults and a new request-scoped coherent catalog port prevent scalar-loop authority substitution. Sorted owning DM row/ACL locks and a final fresh catalog reread are explicit.

Member inspection uses a DISTINCT eligibility endpoint, use-case, typed port and authorized-subset result, bound to actor/tenant/fixed action and the canonical complete requested-set digest. Known missing/foreign/ACL-denied rows are omitted indistinguishably; uncertainty fails503 for the whole request. KB evaluates all up-to100 candidates before the50-item limit, validates current collection authority first, and binds opaque private cursors to the complete candidate/eligible revision views and last visible membership. Every page re-evaluates; drift409 never substitutes for current-authority denial. Eligibility cannot satisfy strict mutation/snapshot authority.

The reviewed DEVELOPMENT baseline is KB8s/BFF9s/edge10s/browser12s, initial DM phase2s, FINAL TOTAL2s including DM<=1.5s plus KB local catalog/connection/locks/SQL/acknowledgedCOMMIT<=500ms. Actor1/global16 admission is single-process and has no queue; HTTP stays outside KB SQL connections/locks. An acknowledged timely success has the measured residual bound, not globally current-at-transmission authority. UNKNOWN already-dispatched COMMIT may complete late and requires original-key/fresh-authority reconciliation. No timeout, cancellation or HTTP return proves absence or on-time completion. Rejected55s/10-way/24s candidates are not implementation permissions.

## Actual specification verification and mandatory runtime gates

Independent171/172 accepted the amended22-file source freeze ada15bb6818a31890197297a1afca347a90d192032fe8c681d45313574866d66. Root strict OpenSpec12/12, both pinned Redocly descriptions zero warnings,37KB/25DM strict schemas,32 operations/15KB-BFF parity, compatibility and positive/negative/declarative examples pass; see verification.md. Administrative approval-state wording changed after review without changing accepted behavior, limits or wires. Later185 records renewed owner merge permission; governance-only metadata does not waive any runtime or production gate.

No runtime was implemented or executed by this approval. Separate prioritized issues, actual100-member timing/concurrency/realPG/catalog/identity/E2E, current ACL/revision/cursor races, compatible published contracts/SDKs and separate75% changed-source line/branch floors remain required. Processing, publication, search readiness and later BP13 acceptance are not claimed. BP03 real expiry and PR146 integration remain separately tracked. The full-project goal remains active.

## Reviewed artifact fingerprint

SHA256 of the sorted manifest below: `e9feda73d5806470f865e04230b2efd85e3c8a51646ae224e7252b0f61c097eb`.

```text
63e7f9133bb3603d935294964dfdbe6d7595cfbfd92652c5035d85adff961415  AGENTS.md
5fadb138adebef364adb32f1020fb4f7ed756070ac295c0734ebb5f1ada44b2d  docs/development/worktree-workflow.md
6c58d69104012e5c4c30929d036eeaa1867c68682928fa39fdb6bd0d596dcdd9  docs/implementation/dkp-005-status.md
e496f609eebed3b66c05553419cfa72ad4696e845f42324217811c5cc3972ed2  docs/implementation/queue.md
1caeb76c80565f701704ab18822e70ee61663cb62d6c5bef303601642b67d6e7  openspec/changes/dkp-005-kb-policy/acceptance.md
8800920c40d571b6cc178b4385b357795bd66f91f6968e81498f008d9052b0d4  openspec/changes/dkp-005-kb-policy/contracts/compatibility.md
dd45dcf25d93512d3541dd7d8a2ec96c973d630534ce37d30965efd4c1564bf1  openspec/changes/dkp-005-kb-policy/contracts/dm-grant-catalog.schema.json
b804291cc867c3319e22ee823388fe5181e6dbc77927821d282a77d91453a3eb  openspec/changes/dkp-005-kb-policy/contracts/dm-sharing-compatibility-fixtures.json
fab24cdedb27c3f78383af200c36bfee53fc4bf69e68c060b2933ee75a736794  openspec/changes/dkp-005-kb-policy/contracts/dm-sharing.openapi.json
622264a5b3f6d487034b248484ab90c6bf5b79670cb84c1faaadb0197d06a45a  openspec/changes/dkp-005-kb-policy/contracts/kb-grant-catalog.schema.json
5222e409ed37ef2beb6141783b4ccf6bb36892a0b9f55e0e056d9c932fcbe529  openspec/changes/dkp-005-kb-policy/contracts/kb-persistence.md
923b801fa035b594a5adcd51d0e99ba383a829e52a113ec02930b8051bfb3af1  openspec/changes/dkp-005-kb-policy/contracts/knowledge-base.openapi.json
78b95c083668a05691d8750ebfc8507d1fea94953591fb50fa3fc220c06d84f9  openspec/changes/dkp-005-kb-policy/contracts/member-eligibility-fixtures.json
5fb459b9f0fd6cc588ddc231e807b2b26a5be9e66fe636a2baaf960e6863441c  openspec/changes/dkp-005-kb-policy/contracts/member-eligibility.md
91d5d676be96eb531c8588e7ab34f92fa81ff3ec621a85af4473d978ae09c69b  openspec/changes/dkp-005-kb-policy/contracts/request-budgets.md
7094b6f6abcb6e4fe2f2c603dc3ea7669720e5e4fe448132d98afbf04809138f  openspec/changes/dkp-005-kb-policy/design.md
e6f1519d4a67baa3480faae0543ff21b718429d8aea5eca2143a24a862a358b8  openspec/changes/dkp-005-kb-policy/proposal.md
176b0fd13f06fadbbb8980dc0e00a779c27e00d0d066cd1a5b5d19b8c67c9819  openspec/changes/dkp-005-kb-policy/specs/browser-bff/spec.md
51049e845496e3c5e5ef0d8b83831a08daec20224e0b09700b68691609e329b7  openspec/changes/dkp-005-kb-policy/specs/kb-persistence/spec.md
bd5081558aa34ae1da73e3f637b3c3a9db7b0697f273aa1f3fa463acd66de11d  openspec/changes/dkp-005-kb-policy/specs/knowledge-base/spec.md
f75a572f71858643276c02490bf5e1314528f0b7577a1768b3b06b2991447fed  openspec/changes/dkp-005-kb-policy/specs/knowledge-policy/spec.md
dc58b5feea1cd73104d8d8c04dce81832cb0e3fb80385a2f18acd21acb8ff7eb  openspec/changes/dkp-005-kb-policy/specs/portal-shell/spec.md
d1aa18643c02813540bc70d40611691c58b64b592f9af069323459c00491d6f7  openspec/changes/dkp-005-kb-policy/specs/service-foundation/spec.md
1848c4382e9f52bbb3ba42273e35cce3e280d6bc4749751bb5e7bd9cb59a451f  openspec/changes/dkp-005-kb-policy/tasks.md
6c4b36a1d42d5d16403829819f2e7d27b329eb0763ff12cecc863995db3ee64e  openspec/changes/dkp-005-kb-policy/verification.md
```

This records the approval checkpoint; task/evidence/status updates may evolve. Normative behavior changes require proportionate independent re-review. Approval.md is excluded to avoid a self-referential digest.
