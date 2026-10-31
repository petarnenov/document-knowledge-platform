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

## Issue118 transport decision — 2026-10-31 20:14UTC

Original exact JDK25.0.5+8 URLConnection retry reproducer observed two physical DELETE requests despite SDK maxAttempts1. Actual decision99108 Content-Length0 candidate failed all five unchanged production-factory TLS tests in Linux: zero dispatch, zero skips. It is rejected. Evidence: `/tmp/dkp-118-linux-option-a.log` and cached JUnit XML `dkp-118-option-a`.

Actual Codex round2 `/Users/petarnenov/.config/away/runs/20261031T201305Z-codex-12702` selected exact AWS SDK2.57.8 Apache5 transport. Applying scoped CA trust, hostname checks,5s connect/30s socket/60s call, SDKmaxAttempts1 and cross-region disabled unchanged; remove failed header override; retain URLConnection only in test scope for scoped-IAM probes. The exact downloaded Apache5 source disables transport retries and redirects. This is implementation authorization, not acceptance.

Pending mandatory gates: resolved dependency maintenance/security review, unchanged five-test Linux real-TLS physical-dispatch matrix, real pinned scoped RustFS harness and all three independent coverage floors, independent110 re-review, applicable PR/integration checks. No issue closure or production/security sign-off follows from this decision.

### Verified candidate evidence — 2026-10-31 20:23UTC

- Apache5 candidate frozen public-factory TLS matrix:5 tests,0 failures/errors/skips,38.464s testcase time; complete Gradle gate52s. Cache `dkp-118-option-b` and log `/tmp/dkp-118-linux-option-b.log`.
- Same image2015 real pinned RustFS combined gate:69 tests,0 failures/errors/skips; storage181/186 lines and100/130 branches; persistence172/180 lines and116/130 branches; verification119/119 lines and180/218 branches. All independent75% floors pass. Evidence `local-data/security/rustfs-compat-104-v3/reports/e823ff91686d`.
- Independent110 storage-production delta PASS. Reviewer excluded own104 test/harness authorship; factory TLS tests were authored independently. Broader runtime/PR/integration remains open.
- Exact transitive locks: httpclient5=5.6.4, httpcore5/httpcore5-h2=5.4.3. [Apache project status](https://hc.apache.org/status.html) identifies maintained stable5.6/5.4 series. [Official news](https://hc.apache.org/news.html) discloses newer maintenance5.6.5/5.4.4; pins are not claimed latest. Apache CNA records published through CVEProject exclude these versions from [CVE-2026-71290](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/71xxx/CVE-2026-71290.json), [CVE-2026-40542](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/40xxx/CVE-2026-40542.json), [CVE-2026-64607](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/64xxx/CVE-2026-64607.json), and [CVE-2026-54399](https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/54xxx/CVE-2026-54399.json). This is a scoped advisory review, not a claim that no vulnerabilities exist; final dependency scanning remains99.
- FreshClam114 actual new pre-launch private record `d08ee4e4b9e24be09d249a80333d278b`, stopped successful genuine updater, strict profile attestation and immutable `generation-20261031-2021-recorded` publication pass. Independent111 source/profile/generation re-review PASS,16 structural tests. Production ClamAvScanAdapter against a new scanner mounting this exact generation read-only passes real benign/EICAR9s (`/tmp/dkp-004-real-recorded-scanner.log`). ActualFinishedAt retained, daily28170 and digest61b24f93039319386a77bf58d6e02a7b285b4fa239163f294d8f0bb4cab3350b.
- Real Linux bootstrap47cases found122 null-success audit vs PostgreSQL NOT NULL; no assertion/control weakened. Root helper now persists finite OK for success;59 application tests pass, HTTP fixture adds committed facts/replay/accounting assertions. Full new Linux image2024 aggregate started; acceptance pending.

### Verified runtime checkpoint — 2026-10-31 20:49 UTC

Linux snapshot2024 full gate completed283 tests with zero failures/errors/skips: DM domain13/application59/inbound10/outbound69/bootstrap47; BFF application2/outbound5/bootstrap30; inspector40; architecture8. DM service97.75% lines/85.34% branches, BFF service97.14%/85.48%; all three outbound area floors pass (storage97.31%/76.92%, persistence95.56%/89.23%, verification100%/82.57%). Worker97.18%/77.16% on Linux. Evidence `/tmp/dkp-004-full-java-2024.log` and external cache `dkp-004-full-java-2024`. This snapshot predates final #128 BFF error mapping and needs a final refresh.

#125 actual isolated worker production-adapter acceptance passed four genuine files plus malformed PDF422 and wrong key/profile/CA503 (`/tmp/dkp-125-real-inspector.log`). No mock or skipped worker acceptance. #131 applied actual decision `/Users/petarnenov/.config/away/runs/20261031T204305Z-codex-51601`: RustFS1.0.4 unsupported ACL placeholder is not privacy evidence. Root ownership, exact absent bucket policy and enabled versioning remain required. Actual pinned private-network apply passes existing synthetic-object and exact-version readback, unsigned current/version GET/list/version-list/PUT403AccessDenied before native app provisioning, repeat fixture proof for both allowed app tenant prefixes, scoped version-list/PUT/GET/version-delete success, outside-prefix/admin/bucket-create/bucket-delete/unversioned-delete denial, and deletion only of six recorded fixture versions. Evidence `/tmp/dkp-131-private-matrix.log`;19 helper tests pass,237/254 lines and107/120 branches. Independent architecture review explicitly supersedes the ACL-based objection; no production/security sign-off.

New full BP03 runtime uses separate19443/19444 origins and private providers without RustFS host ports. #132 corrects only inherited callback to matching configured portal origin; OIDC trust guard unchanged. #126 replaces inherited supervisor health with actual clamd PING. All healthchecked services are healthy; real DM readiness200 in1.044s. Actual HTTPS edge probes reject16385-byte JSON/append and100000001-byte binary declarations with finite413 PAYLOAD_TOO_LARGE JSON plus no-store/nosniff. Browser strict CA/hostname and both-mode OIDC flows reached the expected hidden-receipt rejection; #133 corrects the old BP02 disabled-provider assertion to approved404/NOT_FOUND. All-format whole HTTPS acceptance is still running, not claimed.

### Current-source Linux and real HTTPS evidence — 2026-10-31 21:18 UTC

Linux image2107 aggregate passed284 tests with zero failures/errors/skips including current #135 MIME routing and #139 no-decompression regressions. Evidence `/tmp/dkp-004-full-java-2107.log`, retained cache `dkp-004-full-java-2107/summary.json` and per-module JUnit/JaCoCo. DM97.75% lines/85.34% branches, BFF97.14%/85.44%, worker97.18%/77.16%; all three outbound floors remain above75%. macOS native real-PG HTTP fixture unexpectedly rejected create503 before upload; it is not reported as passing. Actual Linux47bootstrap cases pass; runtime/container evidence is authoritative for the exercised Linux environment.

#138 forced a fresh actual test JVM with native-Linux host-gateway mapping, inspected mounted Docker socket numeric GID and exact supplementary group; no socket permissions changed. `--rerun-tasks` prevents cached provider acceptance. All69outbound tests/three area floors pass, zero skips,82s. Evidence `/tmp/dkp-138-real-linux-gateway-group.log`, private-fixture report `rustfs-compat-104-v3/reports/807b1713c6f5`. Independent110source/test re-review PASS.

Actual rebuilt full HTTPS first passed all4formats in light theme, then #140 test-only response-body timing correction yielded both themes/8genuineuploads+exact-MIME/header/byte/SHA256 downloads/logout in17.8s. Expanded #98 matrix then passed2tests23.4s: each format's bounded/open/suffix206 exact bytes; invalid/multiple416 after authority; consumedreceipt409; valid/invalidgzip400 with unchangedCONSUMEDslot; standard ClamAV EICAR422MALWARE_DETECTED and malformedPDF422INVALID_FILE with REJECTEDstate/no revision or active-pointer change; cross-tenant content/mapping404; explicit ACLcontent.review DENY then new full/range/invalid-range/mapping404 proving authority before range errors. No mock scanner or skipped acceptance. Evidence `/tmp/dkp-98-real-https-negative-matrix.log`. Final finite expected-MIME type narrowing strengthens the test and independently passes strict TypeScript; new fresh-runtime current-source rerun remains required.

Staged public-source gitleaks scans1.91MB with zero leaks; owner MicrokernelBoundaryTest remains byte-identical. Pinned OSV2.7.0 source scan of public-only snapshot2107 processed28lock extracts and reports no issues (`/tmp/dkp-99-public-source-dependency-scan.log`), not a universal vulnerability-free claim. Exact synthetic binary fixtures are intentionally tracked with binary attributes to preserve PDF xref padding; no uploaded/customer/employer content enters source. #142 standard SBOM/provenance work remains open.

#137 first genuine fresh bootstrap generated new private identity/worker authority and a genuinely completed recorded FreshClam generation; four private providers started healthy/running. It failed closed at the60s inspector startup wait before apps. Later unchanged inspector strictTLS health and the full87-operation private native IAM matrix pass, but no retained original probe reason establishes cold-start cause. Diagnosis/new regression and a new complete fresh run remain required. Original failure is not relabelled as a successful bootstrap. No runtime integration or production/security sign-off is claimed.

### Current-source SBOM and fresh bootstrap follow-up (21:29 UTC)

#142: current-worktree generation exited successfully and reproduced the prior 770-component / 29-input BOM and provenance byte-for-byte. Independent generator/CI review passed, with 9 tests and 97.62% line / 99.09% branch coverage. Actual GitHub CI is pending.

#137: initial fresh run failed its unchanged inspector startup deadline. It was stopped through recorded owned-resource cleanup; retained data and immutable definitions remain. Diagnostic-only changes independently passed 21 tests. A separate new fresh run is in progress; no causal conclusion or readiness success is inferred from the first run.

### Actual deployed fault checkpoint (2026-10-31 22:04 UTC)

A new runtime completed genuine bootstrap/verify, then the dedicated OIDC/HTTPS generated-PDF receipt/append/discovery/exact-byte fixture passed (one test, 5.5s). With normal cleanup settled and the full state stable, all five exact owned provider stop/start gates passed: PG, RustFS, scanner, inspector, DM. Each retained the same eighteen-table digest and accounting; dependency outages produced bounded readinessDOWN with livenessUP and recovered health. Evidence: `/tmp/dkp-143-real-{pg,rustfs,scanner,inspector,dm}-fault-2158.log` and private per-exercise hash-only records. The earlier nonquiescent run remains a failed attempt. Authenticated fault requests, combined ambiguous-copy/deletion fences, quota and actual60-minute expiry remain separate open requirements.


## 2026-10-31 22:15 UTC acceptance corrections

- #160: actual pinned Python3.14.0/coverage7.17.0 differential on identical119 tests found default sysmon branch87/120 versus C tracer115/120 for the combined helper; both had280/284 lines. The maintained explicit C tracer is documented at https://coverage.readthedocs.io/en/latest/config.html#run-core. Updated121 tests pass with every one of the six helper source/branch floors above75%; combined helper280/284 lines,116/120 branches. No floor or assertion was removed.
- #161: actual credential-free/no-network curl parser regression reproduces unsupported `head = true` and verifies standalone `head`. Genuine new151 preparation passes native403 exact-version deletion denial, unchanged proof bytes, and app-only private authority validation. Old failed directories/ledgers remain retained.
- #163: actual first combined execution correctly failed the production direct-parent spool guard because the fixture passed different roots to storage and application. The correction gives both adapters the service isolated spool and explicitly creates its0700 directory/0600 authority marker before connecting. Independent #162 re-review passed; no production adapter guard changed. New immutable image `sha256:086a3b1641cb25a9d9093f55aaf15f06c2733d8a8309bc0fdb349ffbe77cb655` and freshly prepared151-2215 run are in progress; eight-case runtime success is not yet claimed.
- Full genuine strict HTTPS both-theme/four-format positive/negative journey passed two tests in24.3 seconds on ready2158. All five provider faults and full eighteen-table durability passed separately on its clean quiescent fixture. This does not substitute authenticated requests during faults, quota or actual sixty-minute expiry.
- #164: root verified the latest user-provided AGENTS.md explicitly requires review and prohibits automatic merging. Persisted governing documents now match it; draft PR146 remains open/unmerged. Earlier standing merge authorization is superseded. No issue closure or integration is claimed.


## #165 physical PostgreSQL restart fixture correction — 22:19 UTC

Actual isolated pinned PostgreSQL probe: sameContainer=true, sameDataMountHash=true, mappedPortChanged=true, postmasterAdvanced=true, passwordAuthenticationConfigured=true. The issue-owned probe resources were removed without touching BP02/BP03 providers. Java fixture refreshes only an observed unique mapped port after exact container/image/Testcontainers-label/all-mount identity checks, accepts bounded explicit IPv4/IPv6 wildcard/loopback bindings only when all map to one valid port, and preserves the thirty-second deadline, advanced postmaster time and exact document/account/fence equality. Root independent source review PASS after requested parser/binding fixes. Test compilation and strict eight-case parser compatibility PASS. New source-bound151-2219 prepared; actual image sha256:353ca2bfd867e2d0d86fd9b55b964cbb7c1877d58911bad47f99b919d7b2e3af contains the current reviewed Java/Gradle bytes. Eight-case execution is in progress; failed2215 evidence remains failed and retained.


## Actual combined profile PASS — 22:21 UTC

Source-bound private151-2219, immutable image sha256:353ca2bfd867e2d0d86fd9b55b964cbb7c1877d58911bad47f99b919d7b2e3af: all COPY-01/02/03 and FENCE-01/02/03/04/05 emitted one closed PASS each, followed by required8/passed8/skipped0. The runner exited0 and wrote exactly one closed result record after checking actual image Java/Gradle source hashes and provider/authority ownership. Physical PostgreSQL restarts preserved container, complete mount fingerprint, documents, accounts and fence facts and advanced postmaster time. Actual mapped-port changes were observed; only existing datasource portNumbers were refreshed. Real scoped RustFS, standard ClamAV and isolated inspector were used; no provider substitutes or skipped cases. Prior failed preparation/execution directories remain retained. CI and integration remain pending; this does not complete quota, authenticated outage or actual sixty-minute expiry gates.


## Independent global-capacity evidence

The browser quota profile deliberately uses only the two genuine catalog-authorized tenants, so its ten-slot observation alone does not distinguish global versus per-tenant rejection. Existing actual real-PostgreSQL evidence does: `JdbcUploadRepositoryTest.globalCapacityIsSharedAcrossTenantsAndFailedAdmissionRetainsExactBalances` admits five slots each for two synthetic tenants through the real service, then denies a third tenant whose admitted count is zero; it verifies global count10, transient2000 and promised1000 with unchanged third-tenant balances. The separate six-concurrent-admissions test verifies exactly five successes. Current forced Linux report807b1713c6f5 records this class21 tests, zero failures/errors/skips, including both named assertions. No production catalog grant or third browser identity was invented. Browser quota execution remains independently pending.


## Actual genuine HTTPS quota PASS — 22:35 UTC

Ready fresh runtime2231, independently reviewed test SHA256acb2e86c155e065253fc61dd67a32b4520f9288416cfbb7a82cbbd8a6b134549 and exact committed runner SHA2567a9435467c3333f5718d5f98b6068571f27385a86192ff8c7b0eb7f28886c663: complete quota profile1PASS5.4s, strict Chromium CA/hostname trustPASS, terminal0. Exact two-tenant quotas/current authority/CSRF+Origin/logout/visible error+retry assertions all executed. Third-tenant global-cap distinction is supported separately by the real-PG21-test report above. No provider substitute, clock/TTL rewrite or skipped assertion. Prior2222/2226 failures remain retained. Genuine sixty-minute expiry and authenticated fault acceptance166 remain pending.

### 2026-10-31 22:56 UTC: remaining gates and reviewed source

The genuine sixty-minute profile is RUNNING on the isolated fresh2238 runtime. Its six real slots have latest expiresAt23:44:19.473759Z; original expiry branch SHA256198cd5cd2fcc4ff67add2d03c3c92c31cccc8c2e23868bac2e1d7e6c33f08f0c and runner7a9435467c3333f5718d5f98b6068571f27385a86192ff8c7b0eb7f28886c663 run from a frozen public snapshot. All32 provider/runtime source inputs matched the implementation source. Do not claim PASS until elapsed-time, renewed genuine OIDC, immutable deadline and final cleanup/receipt assertions finish. No provider mutation runs concurrently on this fixture.

Authenticated faults166/173 independent170 re-review PASS; all138 Python3.14 tests pass, seven helper source/branch floors exceed75%. Browser observer176 independent177 review PASS: original real201 required, exact same-key/body/technical-guard API replay, minimal receipt, actual UI and unique bounded discovery;23 tests cover100% helper lines/branches/functions/statements. Both shared browser helper files together:59 tests,100% line/branch coverage. Final-source genuine fault, both-theme and fresh quota CI remain pending. The prior quota PASS used its then-reviewed observer and is not overstated as the new176 observer result. Explicit helper reports are nested under existing ignored/excluded coverage, preserving production coverage baseline. No private logs/authority are uploaded.

### Owner-requested fast expiry split178 and review-driven tests180

`node tools/runtime/upload-expiry.mjs fast`:64 application tests, zero failures/errors/skips; focused UploadLifecycleTest44. Report-only JaCoCo XML confirms409/418 lines(97.85%) and503/622 branches(80.87%). Exact selector CI command:5 tests,100% lines/branches/functions. Independent179/180 reviewer verified source hashes, fresh JUnit counts and JaCoCo counters. Original-slot -1microsecond/exact/+1microsecond, delayed retries retaining initial expiresAt, consumed replay/discovery, expired activehold and unresolved deletion conservative accounting, plus per-confirmed-copy settlement control are covered. No production source, TTL, session, tenant check or live provider was changed. Real60-minute final acceptance remains separate and RUNNING, and CI/integration remain required.

### 2026-10-31 23:23 UTC first genuine authenticated fault CI success

On remote148c037, Foundation41250740065 job124683539258 completed the real authenticated provider-fault/receipt-state/retry-accounting stage166/173 successfully. This is distinct from the earlier quiescent five-fault143 durability proof. The subsequent updated both-theme browser, combined COPY/FENCE and separate fresh quota stages remain pending at this checkpoint; no full job or real sixty-minute success is inferred.

Approved BP04specPR184 integrated as6262d10 after finalc65c441 all9 Foundation jobs plus hygiene passed. Local merge into BP03 is documentation/specification only: sole adjacent-queue-row conflict preserves both actual statuses, incoming BP04 source equals dev, production/runtime/test/build/lock/published-contract bytes equal reviewed148c037. Independent181 bounded re-review PASS; strict OpenSpec12/12, staged diff clean and secret scan0leaks. Final combined-head CI and dev integration remain required.
