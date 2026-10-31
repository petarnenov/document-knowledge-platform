# DKP-004 BP-03 design — DRAFT task #88

## Authority and source freeze

Source of truth: public System Design §§7.2/7.4/7.5/8.1/8.3/10/13/14, canonical BP01/BP02 specifications, reviewed versioned contracts and owner-selected RustFS. This change is a proposal, not architecture/security/production approval. Independent reviews #89/#90 and strict validation must resolve all blockers before development approval.

Actual away decision `/Users/petarnenov/.config/away/runs/20261031T183012Z-codex-95531` proposes OptionA after the original Claude run failed without a decision. Root accepted its actions as a proposal only. Independent #89/#90 must review root-owned System Design§8.1.1 and queue staging clarification with these contracts. No final architecture/security/production approval is inferred.

Sequence: initialize authenticated existing-document slot→bounded binary PUT→quarantine→standard scan+isolated structure verification→durable opaque receipt→existing explicit append201. Completion creates no version. BP05 retains atomic version/run/intent/outbox plus202 ACCEPTED_PENDING_DISPATCH and must explicitly resolve legacy pre-processing versions/append compatibility. No partial202/PROCESSING may be exposed.

## Existing foundation and dependency direction

DM owns document aggregates, ACLs, retention, versions, command outcomes and audit in one PostgreSQL database. Its application layer depends on pure ports. VerifiedArtifactAuthorityPort currently returns UNAVAILABLE in runtime and permits only explicit test fixtures. New adapters must replace that binding with real verified evidence, never browser hash/MIME/key authority.

The bounded admin-owned catalog supplies exact issuer/subject/tenant/action grants; DM ACL is independently required, DENY wins. Shared identity contracts contain technical identities/ports, not DM domain models. S3, scanner, inspector, database and HTTP types stay in adapters. BFF owns no domain database and forwards only authenticated fixed routes.

## File trust and admission invariants

Every upload belongs to one exact verified actor, tenant and existing document. All IDs, storage keys and security-relevant facts are server-derived. A slot/token is only a narrow upload locator, never bearer authorization. Current membership, explicit operation and current DM ACL are evaluated for each command, body boundary, replay and content request.

Cap each file at100MB; 100MB means exactly100000000bytes in the proposed frozen profile. PDF maximum1000 inspected pages; at most5 admitted uploads per tenant with bounded global resources. Admission and terminal/retry transitions persist atomically so concurrent requests cannot bypass limits. Metadata remains independently bounded16KiB; binary transport must not reuse that JSON cap.

Bytes enter private quarantine, with streaming SHA-256 and actual-length verification. Server-declared format is a constraint, not trust. No receipt/original promotion occurs until signature, structural and standard malware checks all succeed. Scanner/store/inspector failure or uncertain evidence returns a safe unavailable/non-success result, never clean status.

Slots cannot select arbitrary URLs, buckets, object keys or filesystem paths. Retries cannot overwrite a verified original or swap the object after inspection. All persisted evidence binds exact tenant/document/actor, content hash/size/format, object identity and inspection/scanner versions. A receipt identifier alone cannot widen actor/resource scope.

## Structural inspection and isolation

PDF/DOCX/PPTX/XLSX are the complete accepted family. Reject signature/format mismatch, truncation, unsupported/encrypted content, dangerous active content and structural-limit violations before any downstream parser sees bytes. Office ZIP validation rejects traversal, duplicate/ambiguous entries, unexpected macro/OLE executables, external relationships, bombs and unsafe XML.

PDF inspection checks real page count and rejects encryption, embedded files, JavaScript, launch actions and unsupported active structures. Office checks validate exact package content types and required main document parts. Do not equate ZIP magic with Office verification. OCR/extraction/preview remain later packages; BP03 performs no semantic extraction or rendering.

Use a separately isolated non-root inspection boundary with read-only root, private bounded temp, CPU/memory/PID/time limits and denied network. It receives bounded bytes and a narrow typed operation; no IdP/S3/DM credentials, arbitrary path, URL, script or shell command. Errors expose bounded codes only; parser diagnostics and document content never enter browser/log responses.

ClamAV uses its maintained standard signature set, not a tiny synthetic replacement. A valid freshness check precedes clean evidence. INSTREAM is length-framed and bounded; every error, timeout, limit, malformed/partial response, stale definitions or unavailable scanner fails closed. Signature updater has only approved update egress; untrusted inspection has none.

## Storage and runtime trust

First object store SHALL be RustFS behind the S3-compatible port. Use private quarantine and verified-original namespaces, server-derived non-user keys, scoped runtime credentials and explicit deny; no bucket ACL assumption, anonymous access or root credential in application runtime. Native IAM/admin bootstrap compatibility must be proven on the selected release.

Required operation compatibility must be tested against the actual pinned RustFS image: SigV4/path style, private bucket policy, put/get/head, immutable object identity/version behavior, conditional writes where used, range, checksum/readback and failure handling. Do not infer MinIO admin compatibility or silently fall back to permissive credentials.

Use CA-verified scoped TLS and restrictive ignored generated credentials, with no machine-global trust change, committed secrets or secret logs. Local persistent volumes and non-root/read-only/cap-drop/resource/network boundaries must match actual runtime requirements. Production topology, resilience, workload mTLS and release approval remain BP16/BP17 gates.

## Immutable receipt, append and content boundaries

The opaque receipt is resolved through the application verification port in exact actor/tenant/document scope. A successful append uses real verified original facts, uniquely numbers an immutable version and preserves activeVersionId. Receipt/version/idempotency/audit state must commit together; object storage is not falsely claimed to participate in a PostgreSQL transaction.

Ordinary document.read must never become privileged draft review. Content access requires its reviewed explicit action plus current membership/catalog/ACL and exact version association. No endpoint serves arbitrary object references, quarantine, scanner failures, missing/withdrawn sources or fabricated preview. Publication and generated safe preview remain later packages.

Gateway streams bounded chunks and supports only reviewed safe range behavior; no permanent S3 links, redirects or presigned downloads. Each new full/range request rechecks current policy. Safe attachment disposition, canonical MIME, nosniff and no-store prevent active browser rendering. A narrow post-final-check network race remains explicit; already downloaded bytes cannot be revoked.

## Relational ownership and transactions

New slot/receipt/evidence tables belong only to DM's logical database and schema, with tenant/document foreign keys, strict bounded states and immutable verified facts. Scoped migration role performs DDL; runtime has only required DML and cannot alter immutable receipt/version/evidence or sibling ownership. Schema details and transition constraints must freeze in contracts before approval.

Admission serializes tenant resource accounting; state changes use expected state/revision and unique idempotency scopes. Retries reauthorize current actor/operation/ACL before stored success. Append transaction consumes/binds receipt and writes immutable version, aggregate revision, minimal receipt, idempotency and audit atomically. Failure cannot leave a consumed receipt without its version or double-append it.

Storage writes before DB verification can leave orphans; reconciliation must never expose or promote them without all evidence. No cleanup routine may delete retained verified originals or bypass legal hold/unresolved retention. Destructive lifecycle/purge remains BP15; quarantine reclamation policy needs explicit reviewed boundaries rather than an invented legal duration.

## Candidate pins and public provenance

Research #86 observed RustFS1.0.4 index sha256:ed9210d6411d77ffceb70d94b964a3cb7d225983368bc25bebe01e995293dcc2 and ClamAV1.5.4 index sha256:bda39705e291105049c3936e2803ac90b955172af8030f94f05dce1252df6c6d. Manifest records exist locally; actual architecture/platform and release compatibility tests are acceptance gates, not completed evidence.

Proposed pinned AWS SDK Java2.57.8 and PDFBox3.0.8 must resolve against the approved Java25/Micronaut pinned baseline without unmanaged transitive drift. Lock dependencies, reproduce SDK generation and record effective versions/SBOM. No dependency/runtime source edits occur in this specification task.

- [RustFS1.0.4 release](https://github.com/rustfs/rustfs/releases/tag/1.0.4)
- [RustFS container isolation](https://docs.rustfs.com/en/installation/container)
- [RustFS S3 compatibility](https://docs.rustfs.com/en/administration/protocols/s3)
- [RustFS IAM](https://docs.rustfs.com/en/security-compliance/iam)
- [RustFS native policy/admin API](https://docs.rustfs.com/en/security-compliance/iam/policies)
- [ClamAV1.5.4 release](https://github.com/Cisco-Talos/clamav/releases/tag/clamav-1.5.4)
- [ClamAV supported branches](https://docs.clamav.net/faq/faq-eol.html)
- [Clamd stream protocol](https://docs.clamav.net/manual/Usage/ClamdProtocol.html)
- [ClamAV official containers](https://docs.clamav.net/manual/Installing/Docker.html)
- [AWS SDK2.57.8 release](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.8)
- [PDFBox downloads](https://pdfbox.apache.org/download.html)
- [PDFBox security](https://pdfbox.apache.org/security.html)

## Delivery and rollback

The frozen contracts and verification matrix accompany this draft. Runtime child issues must exist before execution; unchecked tasks are not approval. Independent specification review, resolved findings, strict validation and recorded development approval precede runtime. Independent implementation review and applicable green checks precede PR merge dev.

Rollback disables new admission and reverts compatible application paths while preserving DB/evidence/original objects. Never automatically drop tables, reinterpret verified states or delete retained content. Full-project delivery continues after BP03; final CID07/CID15 and production controls remain unaccepted gates.

## Frozen API and development policy profile

Normative wire contracts: contracts/rustfs-upload.openapi.json; immutable private evidence: contracts/verified-receipt.schema.json; relational state/UoW: contracts/upload-persistence.md; content/limits/actions: contracts/content-and-limits.md. These are proposed freezes, pending89/90; any conflict must be resolved before approval.

Upload/status commands use document.version.append current catalog+ACL and actor-owned slot. Init requires current expectedDocument revision but does not increment aggregate revision. Safe status reveals only upload ID/state/expiry/bounded failure code. Body/complete use no caller object reference. Current policy rechecks precede replay and finalization; transient failed completion may retry with a new key within fixed attempts/expiry.

New candidate document.content.review is privileged original access, never inferred from inspect-draft/read/creator/admin. It is eligible only for explicit creator catalog bootstrap and exact target+ceiling delegation, with current final rechecks. This proposed development authority matrix needs independent security acceptance; CID07 remains unresolved for production.

Upload endpoints under /api/v1/documents/uploads mirror /api/v1/dm/documents/uploads at BFF. Raw PUT accepts only application/octet-stream and streams with its own limits/deadlines. Existing16KiB metadata guard remains. BFF and DM enforce their own bounds, identity and tenant checks; unsafe Origin+CSRF applies before bytes forward. Binary retries hash actual bytes before any success replay.

Gateway accepts exact document/version IDs only and resolves a trusted original. Fully verify SHA against exact immutable S3 version into bounded private temporary storage before response. Full200/single-range206 attachment uses canonical MIME, UUID filename, no-store/nosniff. At most2 global/1actor streams,200MB disk,64KiB chunks and fixed deadlines; current authority is rechecked immediately before transmission.

Expiry is60min operational admission expiry, never legal retention. Persist quota/cleanup until exact object deletion/absence. Current active legal hold or unavailable hold authority blocks object deletion, preserving charges; consumed retained originals remain outside cleanup. Unresolved legal durations/destructivefalse never become a purge authorization. If hold prevents reclamation, admission safely429 until separately approved resolution.

Inspection isolation is an actual separate image/container, not an in-process utility. Internal route POST /internal/v1/inspect receives bounded raw bytes plus enumerated X-DKP-Format; generated scoped credential over CA-verified TLS restricts caller to DM. Closed response binds format/MIME/size/SHA/pageCount and profile/version. It has no S3/IdP/DB credentials or egress. Production workload mTLS remains open.

## SDK checksum compatibility gate

AWS SDK>=2.30 defaults to automatic CRC32 when supported. Adapter explicitly supplies SHA256 for PutObject and verifies exact-version GetObject byte hash independently; ETag or missing SDK-supported stored checksum never establishes integrity. Exact RustFS1.0.4 SDK2.57.8 compatibility must pass without disabling checksum validation, silent fallback or permissive TLS.

Primary sources: [Java S3 checksums](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html) and [SDK data integrity defaults](https://docs.aws.amazon.com/sdkref/latest/guide/feature-dataintegrity.html). A missing/unsupported checksum operation is an explicit release compatibility blocker, not permission to omit independent readback hashing.

## Consolidated review amendments — #89/#90, re-review pending

#89 identified no browser route to obtain versionId after minimal append. Freeze narrow exact actor-owned consumed-receipt GET→{documentId,versionId}; content.review+ACL and current final checks apply, no metadata/list/count. Append/complete/status responses remain unchanged. Portal/E2E resolves the known receipt before exact-version attachment download.

#90 identified unaccounted failed original copies under2x reservation. Choose confirmed prior-copy cleanup/fencing before another copy. Persist possible key/version/unknown outcome before I/O; preserve charges across holds/outage/restart. No timed-out write/lease expiry or absent HEAD alone proves absence. Global→tenant→document→slot→receipt→outcome lock order applies across admission, append and cleanup.

#90 also requires a wire enum delta: existing ACL schemas/routes and private catalog schema now explicitly add content.review; all other BP02 schemas stay unchanged. SDK/Zod/catalog/domain exact-enum parity and old9-action compatibility are mandatory. Initial reviews requested changes; this amendment is not a PASS or approval. Root must obtain both independent re-reviews and checks.

## Decision #100 proposed deletion/hold serialization

Away18639 selects durable DM document deletion fence with fail-closed unknown outcomes. Check current holds/eligibility/consumption and commit immutable exact-object attempt before dispatch, using accounts-first order. No SQL lock spans network. Same document UoW prevents new hold success while any deletion remains potentially executable; current authorized prior201 replay stays unchanged.

A new hold hitting unresolved fence returns existing503 UNAVAILABLE without mutation. Known terminal response, exhausted single-execution/no-retry capability and authoritative absence permit atomic fence/accounting release. Unknown/timeout/crash/stale lease never authorizes transfer/release, even after absent HEAD/list; remain503/fullcharges until independently provable settlement. Normal cleanup and completed-attempt recovery stay in scope.

This can indefinitely block NEW hold placement and admission after uncertain deletion. That availability cost is explicit and pending89/90 development-policy acceptance; it is not legal/production signoff. Existing hold201 minimal receipt and authorized same-command replay remain unchanged. The narrow content streaming post-check race does not authorize destructive hold races.

Compatibility correction restores existing dmAppendVersion/bffAppendVersion operation IDs and AppendVersion request DTO. Existing append path/method/request/success are stable; new typed upload/receipt failures and429 are an explicit additive failure-contract proposal. Do not claim the entire operation is unchanged.
