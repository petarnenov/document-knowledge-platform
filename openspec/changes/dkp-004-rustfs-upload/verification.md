# BP-03 verification matrix — draft, task #88

No row is acceptance evidence until executed against approved implementation. Independent reviews #89/#90 and strict contract validation precede development approval. Source coverage measures every changed service/tool/frontend area, at least75% lines and branches.

| Gate | Required evidence |
|---|---|
| AUTH-01 | Verified real issuer/subject/tenant; missing/malformed catalog503; missing operation before lookup403; hidden resources404; explicit DENY wins; membership alone never permits upload/content. |
| AUTH-02 | Revoke membership, operation, ACL or delegated authority during init/body/complete/receipt append/content/replay; no stale positive response or cross-tenant access. |
| AUTH-03 | Same subject with another issuer; forged tenant/header/body; guessed upload/receipt/version IDs; actor-bound slot; external authority unavailable. |
| BFF-01 | Exact fixed paths/methods; unsafe routes reject wrong/missing Origin or CSRF for binary and JSON; session expiry/logout/tenant-switch; no redirect or credential forwarding to arbitrary host. |
| LIMIT-01 | Exactly100MB versus100MB+1 bytes, declared/actual mismatch, empty/truncated/chunked body, timeout/disconnect, sixth tenant admission, concurrent first-five race, quota release/recovery and tenant fairness. |
| FORMAT-01 | Real valid synthetic PDF, DOCX, PPTX and XLSX each pass actual signature/structural/malware checks and can become a same-document immutable version. |
| FORMAT-02 | Extension/MIME/magic mismatch, malformed/truncated PDF/ZIP, PDF1000/1001 pages, encrypted files, ZIP traversal/absolute paths/symlinks/duplicates, archive bombs/nesting and oversized XML fail closed. |
| FORMAT-03 | Macro-enabled/renamed Office, embedded executable/OLE/active content, external URLs/relationships, malicious XML entity and PDF JavaScript/launch/embedded files; no execution or egress. |
| SCAN-01 | Standard maintained ClamAV signature set, freshness boundary, real EICAR and benign fixtures, scanner outage/error/timeout/oversize/partial protocol response, stale definitions; none yields verified receipt. |
| SANDBOX-01 | Inspection non-root, read-only root, bounded CPU/memory/PIDs/tmp/time and denied network; malicious input cannot access credentials, sibling uploads, host paths or DM database. |
| S3-01 | Exact pinned RustFS SigV4/path-style operations, TLS verification, app IAM explicit deny, quarantine/private buckets, scoped credentials, key guessing, no anonymous or root application access. |
| S3-02 | Immutable object identity, failed/interrupted write, checksum/readback mismatch, stale object substitution, restart and storage outage; no storage pointer or receipt is trusted before full evidence. |
| DB-01 | Real PostgreSQL owned migrations/reentry/checksums, DML-only runtime, upload admission races, state CAS, idempotent concurrent bodies/commands, rollback failure injection and append-only evidence. |
| RECEIPT-01 | Actor/tenant/document/hash/object-version/evidence binding, missing/fabricated/wrong-scope/changed-source receipt rejection, replay/current-policy checks, concurrent consumption and atomic version+receipt+audit outcomes. |
| VERSION-01 | Unique immutable ordinal, submitted classification, unreviewed state, same-document original, non-null active pointer fixture unchanged; no publish/process/readiness transition fabricated. |
| CONTENT-01 | Exact version and privileged action, current actor/catalog/ACL at each request and final boundary; nonexistent/cross-tenant/wrong-document IDs indistinguishable; no S3 URL or active inline content. |
| CONTENT-02 | Full and single-range response correctness, invalid/multi-range handling, byte bounds, safe filename/content headers/no-store/nosniff, download revocation/new-request and interrupted transport. |
| E2E-01 | Real Keycloak→HTTPS BFF→tenant→draft→upload→scan→receipt→append→exact-version authorized download with byte/hash parity for all four formats. |
| E2E-02 | UI accessible upload progress/failure/expired session/quota/scan unavailable/retry, cache clearing on tenant/logout, no invented parser/publication success; browser has no storage/token secrets. |
| OPS-01 | Scanner/store/inspector/DB readiness truth, standard definitions freshness, real outage/recovery, persistent original/evidence digest, log/secret exclusion, SBOM/pins/image verification. |
| CONTRACT-01 | Strict OpenSpec+OpenAPI validation, closed schemas and bounded bodies, generated Java/TS parity/reproducibility, unchanged BP02 wire behavior and architecture negative fixtures. |

## Proposed frozen profile and additional gates

Away95531 OptionA and contracts/content-and-limits.md freeze the proposed byte/count/deadline/expiry/structural profile. #89/#90 must accept it before development approval. Root-owned System Design§8.1.1/queue proposal is part of the same review; no final atomic-processing obligation is removed.

- QUOTA-02: verified-unconsumed and cleanup-pending reservations survive crash/expiry; failed deletion/hold/unavailablehold authority never frees quota; no sixth slot or retained-capacity overcommit after restart.
- RECEIPT-02: receipt expiry, consumed-complete replay, body mismatch/replay, storage verified-copy crash and append commit ambiguity preserve immutable facts and one-consumption semantics.
- CLEANUP-01: rejected/expired exact object cleanup retries; active hold blocks deletion; consumed original/evidence never a cleanup target; reconciliation cannot trust lease expiry as physical deletion.
- CONTENT-03: bounded pre-stream fullSHA verification against exact S3version, disk/stream admission and disconnect cleanup; authority revoked during readback denies before first byte.
- STAGE-01: no202/PROCESSING/run/intent/outbox in BP03; completion creates no version; explicitappend201; activepointerunchanged; BP05 legacy compatibility obligation remains visible.


## Consolidated findings regression gates — re-review pending

- DISCOVERY-01 (#89): actual authenticated append→known-receipt mapping→exact-version download; no minimal mutation/status shape changed. Append-only actor lacking review can append but receives identical404 for mapping/content.
- DISCOVERY-02 (#89): wrong actor/issuer/tenant/document, unknown/unconsumed receipt, absent association and missing action are hidden404; missing/malformedcatalog503; revoked ACL/grant during lookup suppresses JSON; known consumed mapping works after slot expiry without enabling reuse. At most one row, no count/page/metadata/hash/storage facts.
- COPY-01 (#90): real PG+RustFS crash immediately after successful PUT but before DB verification leaves possible copy charged; restart cannot issue a new copy/key until settled writer and exact version cleanup/absence is confirmed. Full physical bytes never exceed2x slot reservation.
- COPY-02 (#90): timeout/unknown write, delayed completion, failed cleanup, legal hold and unavailable hold authority keep charges and block retry503; no absent HEAD/expired lease/SDK retry permits another version. Once safe cleanup completes, one bounded new copy may reuse the reserved half.
- COPY-03 (#90): init/append/cleanup concurrent PG transactions use fixed global→tenant→document→slot→receipt/outcome order without deadlocks/negative counters; held failed copy still denies sixth admission.
- ACTION-01 (#90): exact10-string public ACL Action, private catalog actions/ceiling, DM enum, Java/TS SDK and Zod parity; each old9 string remains valid; new content.review GET/PATCH round trip, explicit creator bootstrap and constrained delegation succeed only when granted. Unknown/wildcard/admin/read-only substitutions fail.
- ACTION-02 (#90): recursively compare every existing reachable ACL schema to BP02; only Action.enum adds one string. Existing16KiB/rule bounds, error/body/status and CommandReceipt unchanged. Generated SDK drift and old-client payload tests pass.

## Amendment author validation checkpoint — no independent PASS claimed

Strict OpenSpec change validation passes after all three consolidated corrections. Redocly recommended validation has0errors and the existing unselected-license warning; root repo-configured gate remains required. Author semantic checks confirm all8 shared BP02 schemas unchanged except additive Action, unchanged ACL operation IDs/body/responses, catalog sole additive enum and all DM/BFF body/response parity. Independent re-reviews #89/#90 remain pending.

## Decision #100 fence proof matrix — independent re-review pending

- FENCE-01: both deterministic orders: successful holdfirst blocks dispatch; committed fencefirst blocks NEW hold503 without hold/revision/outcome, while authorized prior same-command hold201 replay succeeds unchanged.
- FENCE-02: pause authorized deletion before/after dispatch; timeout then delayed remote deletion, lost response, cancellation and stale-worker dispatch; no new hold can succeed while prior deletion remains executable. No lease/HEAD/list/restart shortcut.
- FENCE-03: crash before dispatch, after dispatch and after remote terminal response; restart preserves unknown fence/charges and forbids dispatch-authority transfer absent proof. No inferred absence or negative counters.
- FENCE-04: known successful exact-version delete plus validated bound response/one-shot exhausted/no-retry proof/absence settles fence+accounting atomically; restart recovers durable completed attempt without dispatching again. Confirmed failure settles only if delayed execution impossible; object/charges remain.
- FENCE-05: wrong request/version response cannot settle fence; consumed/rebound/evidence objects excluded; competing receipt consumption/cleanup uses global→tenant→document→slot→receipt/outcome order. Real PG+RustFS barriers prove no delete remains executable after newhold success.
- COMPAT-03: existing append operationIds dmAppendVersion/bffAppendVersion, request ref AppendVersion and201 CommandReceipt equal BP02; UploadError/429 are explicit new failure delta. Existing hold wire operation/body/error/success and successful authorized replay remain intact.

## Root development approval checkpoint — 2026-10-31 18:55 UTC

Earlier draft, pending-review and author-only checkpoints above are historical and superseded here. Independent security89 and architecture90 final re-reviews PASS after all findings were resolved, including exact-version discovery, two-copy/retry accounting, additive action contracts, append SDK compatibility and decision100 deletion/hold fencing. Both explicitly accept potentially indefinite NEW-hold503/full charges for genuinely unresolved deletion as a development availability tradeoff only; no legal-policy, production or security-control sign-off.

Actual decisions95531 (OptionA stage sequencing) and18639 (durable fence plus fail-closed ambiguity) were applied and independently verified at specification scope. Claude90943 returned no decision because of quota and was not applied. Normal known-outcome cleanup and durable completed-attempt recovery remain required; no timeout/lease/restart/absence shortcut settles an executable deletion.

Root repeated `node_modules/.bin/openspec validate --all --strict`:10/10 PASS; `node_modules/.bin/redocly lint openspec/changes/dkp-004-rustfs-upload/contracts/rustfs-upload.openapi.json --config tools/contracts/redocly.yaml`: valid, zero warnings. Strict Ajv8.20.0 draft2020 plus formats2.1.1 compiled all three private schemas; five inspector and eight synthetic receipt boundary/unknown-field rejection fixtures passed. These fixtures prove syntax bounds, not scanner evidence. Structural regression checks passed12 common BP02 schemas (only Action enum additive), sole catalog action delta, existing SDK operation IDs/AppendVersion/201, preserved ACL/hold contracts,22 DM/BFF operations with body/response parity, closed objects, minimal discovery and no202. `git diff --check` passed; frozen pnpm install changed no package/lock files.

Root changes after final review are administrative approval/checklist/status labels; normative contracts and requirement text remain unchanged. Reviewed artifact fingerprint is in approval.md. Runtime91–99 exist before execution and remain unstarted pending specification integration. Actual RustFS/standard ClamAV/inspection/fault/coverage/real HTTPS tests and independent implementation review remain mandatory, unexecuted gates. No BP03 runtime acceptance is claimed.
