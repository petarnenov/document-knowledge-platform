# Draft verification evidence — task #52

2026-10-31; specification author only. No approval, runtime tests or implementation completion claimed.

- Official installed OpenSpec CLI from completed DKP-002 worktree: `openspec validate dkp-003-document-management --strict` PASS after canonical BP-01 archive integrated into this worktree.
- Repository Redocly config: draft OpenAPI valid. Initial lint found a missing-server warning (corrected to relative server) and two expected no-2xx warnings on intentionally unavailable BP-02 version-append operations. Do not invent a success response merely to silence lint: BP-03 verified RustFS receipt authority is absent.
- JSON grant catalog schema accompanies the frozen draft. File/ownership/size bounds, duplicate actor keys, delegation cross-entry consistency and reauthorization require runtime policy tests; JSON schema alone does not prove these.
- Security review #56 and architecture review #57 pending. Reviewed artifact digest and approval record belong to root after resolved reviews; runtime child issues remain uncreated/unstarted.
- Baseline branch now contains archived BP-01 canonical specs. Only this change directory is authored by this task; no runtime source, SDK output, credentials or database artifacts created. No commit.

## Amendment verification — pending independent re-review #56/#57

Decision run 20261031T163854Z-claude-72144 replaces mode+rules/PUT ACL and all inspection-bearing command success responses. CommandReceipt is EXACT {documentId,revision}. AclChange changes[] is an explicit delta with mode retained. Future append201 is documented as receipt but runtime remains503 until BP-03/RustFS; therefore previous intentional no-2xx lint warnings are superseded by the explicit future response documentation.

Required runtime regression evidence (not run in this specification task):

| Scenario | Required outcome |
|---|---|
| All five mutation families / both DM+BFF | Only documentId/revision success keys; no lifecycle/creator/timestamps/metadata/ACL/retention/holdId |
| Mutation without inspection / replay after inspect revocation | Only original receipt; inspect GET404 |
| Self-restrict removes own operation ALLOW | Initial receipt commits; later current-ACL replay404 |
| Duplicate principal/action delta key |422 DUPLICATE_ACL_CHANGE before transaction |
| Resulting rule count101 |422 ACL_RULE_LIMIT, complete rollback |
| Existing ADD / absent REMOVE | No-op rule state but revision/audit/idempotency committed |
| RESTRICT another principal / forbidden operation |422 RESTRICT_SCOPE |
| DELEGATE target not granted / outside ceiling |422 TARGET_NOT_GRANTABLE /403 DELEGATION_CEILING |
| ADD_ALLOW over DENY / REMOVE_DENY | Deny preserved; removal only bounded delegate path |
| Unrelated ACL rules | Preserved through delta |
| GET detail/ACL/hold lacking route inspection grant | Identical404 for existing/nonexistent, before lookup |
| List/create/mutation lacking action |403 before lookup |
| Missing/malformed catalog |503 before action decision; no stale allow |
| Missing document / ACL missing ALLOW or DENY | Identical404 |
| Stale revision / conflicting replay |409 only after current authority |

Security review #56 and architecture review #57 must independently re-review the amended draft before root approval. No runtime code or commit created.

Amendment static checks executed: exact decision Python assertion prints OK; design grep hits all four required error codes; strict OpenSpec PASS after splitting lengthy requirements into focused normative requirements/scenarios; Redocly valid with no warnings once future version201 was explicitly documented as unavailable at runtime. No runtime tests performed. Re-review #56/#57 pending.

## Root approval checkpoint

Independent security re-review #56 and architecture re-review #57: PASS after all original findings resolved; review issues closed after accepted deliverables. Root repeated all-project strict OpenSpec (7/7), configured Redocly (valid, zero warnings), exact CommandReceipt/ACL PATCH/ten DM+BFF mutation-success assertions and git diff --check: PASS. Frozen pnpm install passed without lock/package changes. Earlier draft-author/pending-review statements above describe their historical checkpoints and are superseded by this root checkpoint and approval.md.

Runtime issues #58–#63 were created before execution and remain unstarted at approval. Metadata status/checklist updates after re-review do not alter the reviewed contract or normative requirements. Production, runtime coverage/PG/E2E/independent implementation review remain outstanding.
