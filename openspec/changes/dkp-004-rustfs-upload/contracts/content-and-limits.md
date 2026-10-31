# Frozen proposed content, verification and operational limits

Task #88; development profile proposal awaiting independent #89/#90 review. These are operational bounds, not classification/retention law or production sizing. No runtime override may relax the reviewed profile. A stricter unsupported input fails safely; supported-family acceptance still requires valid fixtures of every family.

## Limits

| Limit | Exact value |
|---|---|
| JSON metadata/command | 16384 bytes; BP02 Action enum adds only content.review; all other BP02 schemas/constraints unchanged |
| Binary source | 1..100000000 bytes; MB is decimal |
| Per-tenant active slots | 5, including receiving/quarantined/verifying/verified-unconsumed and cleanup-pending rejected/expired |
| Global active slots | 10 |
| Reserved transient bytes | 1000000000 tenant, 2000000000 global; reserve twice declared source size for quarantine+verified copy |
| Retained original bytes | 5000000000 tenant, 100000000000 global; transfer atomically from reservation on consumption; no purge implied |
| Slot/receipt expiry | 60min from initialization, unchanged by retries |
| Binary transfer deadline | 300s total,30s idle; count actual streamed bytes independent of Content-Length |
| Complete deadline | 240s total; scan120s, structural inspection60s, remaining storage/evidence60s |
| Work lease | 300s, always bounded by slot expiry; at most3 verification attempts |
| Concurrent scan/inspect jobs | 1 each; bounded admitted work only, no unbounded memory queue |
| Standard definitions freshness | <=24h since last successful standard database update, server UTC; absent/future/unknown timestamp unavailable |
| Clamd | non-root;2CPU,4GiB memory, PID128; StreamMaxLength>=100000000; all scan/archive limits detected and treated as failure |
| Structural inspector | non-root;1CPU,768MiB memory, PID64, private1GB temp; no egress; read-only root;60s killed deadline |
| ZIP expansion | <=512000000 bytes total, <=10000 entries, <=32000000 bytes per XML part, expansion ratio<=100 |
| Archive nesting | no nested archives/embedded packages; canonical paths only; no symlink/absolute/traversal/duplicate names |
| PDF | <=1000 real pages, <=100000 indirect objects, object nesting<=64; strict parse, no encryption/AcroForm/XFA/JS/launch/embedded files |
| XML | no DOCTYPE/DTD/entities/XInclude/external resolution; depth<=64, attributes<=100 per element, XML text/entry limits above |
| Content response | full original<=100000000 bytes; exactly one range or full body, chunks<=65536 bytes |
| Content transport | 300s total,30s idle; reauthorization immediately before response transmission |

Admission also reserves retained-original capacity for unconsumed slots; retained+promised bytes cannot exceed its separate bound. Original storage and reservations must remain bounded even when a client never consumes a receipt. Counters include unknown/failed writes until reconciliation establishes exact absence. Expiry is resource admission expiry only, not a legal retention deadline.

## Format and evidence contract

Accepted detected MIME mapping: PDF=application/pdf; DOCX=application/vnd.openxmlformats-officedocument.wordprocessingml.document; PPTX=application/vnd.openxmlformats-officedocument.presentationml.presentation; XLSX=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet. Declared family must match inspected main package/content types and real magic. Generic ZIP/MIME is insufficient.

Use strict PDFBox3.0.8 inside the isolated inspector for PDF structural validation/page count. OOXML uses bounded ZIP entry iteration plus secure JDK XML APIs, exact required main part and content-type/relationship validation. Reject macros/VBA, executable/OLE/ActiveX parts, external relationships and unknown active package types; no Office process executes. Do not render, extract business text or evaluate spreadsheet formulas.

DM streams exact quarantined bytes for SHA256, standard ClamAV scan and structural inspection, binding each result to identical source hash/size/version. Clean evidence requires engine+standard DB version/time/digest, inspector image/version/profile digest and verification timestamp. Any partial/error/limit result is non-clean. Only byte-identical readback-verified immutable originals can back receipts.

Use ClamAV's maintained standard database with signature verification enabled and a persistent database volume. Freshclam update egress is limited to required official update endpoints. No tiny test-signature database may replace production-standard definitions in acceptance, including CI. EICAR is an extra negative fixture, never a substitute for the standard database/freshness gate.

Inspector accepts only an authenticated fixed internal POST of bounded application/octet-stream plus enumerated expected format; returns closed bounded typed evidence. DM alone supplies a generated scoped service credential over CA-verified TLS; inspector has no S3/IdP/DB credentials and no outbound network. This local control does not claim BP16 SPIFFE/mTLS completion. Scanner is private and reachable only by its DM adapter/updater as required.

## Content action and stream contract

Proposed tenth candidate action `document.content.review` is exact issuer/subject/tenant catalog authority AND same-action DM ACL ALLOW without DENY. Explicit catalog grants may seed this creator action at create and may appear in delegation ceiling only through the existing constrained target+ceiling path. No implicit creator/admin/document.read/document.inspect-draft permission. Independent security review must accept this development policy before adoption.

Only privileged exact-version original review is enabled. Resolve version under tenant+document and its receipt-bound immutable verified object. Current membership, trusted issuer, content.review operation, ACL and exact-version visibility must succeed for each request and immediately before first byte. Publication state/ordinary read/citation/rendered preview are not bypassed or simulated; unsupported paths remain denied/unavailable.

Return attachment only, canonical detected MIME, no-store, nosniff and server UUID filename plus canonical extension. No S3 location, token, object key, caller filename or unsafe header echo. Do not generate HTML/Office inline preview. Requested bytes are verified against stored immutable source identity/hash before delivery; object substitution or integrity uncertainty503 before response.

Range accepts one RFC-style bytes=start-end, start- or -suffix. Clamp end to last byte; suffix greater than length yields whole object206. Zero suffix, reversed/overflow/non-numeric/empty/multiple/other-unit/unsatisfiable ranges return416 after authorization. Full request200; range206 with exact Content-Range/Length. No multipart ranges, conditional304, redirects or signed links. Each range is independently authorized.

Bounded stream integrity is established by exact immutable object version plus full-object SHA verification before initial delivery (using bounded temporary storage), not by trusting mutable latest or ETag. Temporary content remains within reserved private gateway disk/memory limits; at most2 streams globally and1 per actor, transient disk budget200000000bytes. Unavailable capacity429; cleanup after disconnect/failure is mandatory.

## Error and replay policy

JSON errors are exactly {code}, uppercase frozen enum in OpenAPI. Session401; invalid selector403; missing catalog503; missing upload/mutation operation403 before lookup; missing status/content operation404 before lookup. Missing document/slot/receipt/version, wrong issuer/actor ownership or ACL deny404. Expected revision/state/key conflicts409 only after authorization. Malformed400, oversize413, unsupported media415, semantic422, quota429, dependency503.

All unsafe requests require Origin+CSRF at BFF, including application/octet-stream. Each command requires scoped Idempotency-Key. Init returns201 {uploadId}; body PUT200 {uploadId}; complete200 {uploadId,receiptId}; append201 exactly {documentId,revision}. Completion does not create a version. Status GET is actor-owned and current version.append+ACL authorized; it exposes only uploadId/state/expiresAt/failureCode.

Init canonical fingerprint includes expectedRevision/document/declared family+size. Binary fingerprint is server-computed actual hash+size scoped to slot and actor; a retry body must be bounded/hashed before stored success, never overwrite existing bytes. Complete fingerprints empty closed JSON; transient503 does not store successful command outcome. Different complete key may retry within the same expiry/attempt budget.

Every replay rechecks current original operation, membership, slot ownership and current ACL; unavailable authority503. Expired unconsumed slot/receipt409, except an already committed same append replay returns its original receipt after current authorization. Complete replay may return original verified receipt only while unexpired/unconsumed; consumed receipt cannot fabricate fresh reusable success. Status is not an inspection/content privilege.

## Private protocol and credential separation

Inspector success200 is exactly contracts/inspection-result.schema.json, <=4096byte JSON; non-success returns bounded {code} only. Request headers are X-DKP-Format enumerated family and Authorization Bearer generated service-only key, never forwarded browser identity. Expected bytes<=100000000,60s; unknown fields/headers cannot select paths/URLs/tools. Caller verifies response sourceSHA/size/format/profile and independently combines malware evidence.

Normal DM S3 credentials allow only required quarantine put/get/head and verified-original put/get/head for approved buckets/prefixes, no admin/user/policy/anonymous operations. Cleanup uses a separately generated narrow credential only in the cleanup adapter, with exact-object delete-version in owned prefixes and no IAM administration. It runs only after durable exact-object work plus committed document deletion fence with current DM hold/retained-association checks; no delete capability is exposed to browser or content port.

Original immutability relies on exact immutable S3 version identity, write-once DB binding and no normal-request delete-version permission, not mutable latest or S3 ACLs. Cleanup credentials never bypass a consumed association/hold; independent IAM and application negative tests must prove both. Native policy limitations or non-null-version incompatibility block acceptance rather than broaden credentials.

## Review amendment: exact version discovery and additive actions

After append succeeds, the portal resolves its known receipt using GET /api/v1/dm/documents/{documentId}/upload-receipts/{receiptId}/version, then calls the existing exact-version content route. Both require current content.review+ACL; append itself never requires review or reveals versionId. No mapping UI affordance substitutes for backend policy. A user lacking content.review may append but cannot discover/download.

Discovery returns only documentId/versionId with no-store; it is exact actor-owned receipt lookup, not a document version listing. Missing current action404 precedes lookup; malformed/unavailablecatalog503; missing/wrong-scope/unconsumed association404; final authority/ACL/association recheck precedes JSON. After consumed receipt expiry, review remains possible under current authority without making the receipt reusable.

Frozen OpenAPI now includes existing DM/BFF ACL GET/PATCH routes and their unchanged reachable schemas, except Action gains the tenth string document.content.review. This enum is used by ACL request entries, returned ACL rules and delegation. contracts/grant-catalog.schema.json makes the identical additive change in actions/delegationCeiling. No other existing BP02 shape/field/bound/status or minimal CommandReceipt changes.

Runtime catalog Action set, DM enum, generated Java/TypeScript SDKs and Zod schemas MUST reproduce the same10 exact strings. Existing9 remain accepted byte-for-byte. Unknown/wildcard/group values still reject; new content.review requires explicit creator catalog grant to seed and exact target+ceiling for delegation. No implicit owner/read/inspect/admin grant. Compatibility tests include old-client9-action payloads and new-action round trips.

A verification retry cannot write another original while a failed/unknown prior copy remains possible. Fixed2x reservation covers only one source and one possible original; durable confirmed cleanup/fencing is mandatory before reuse of that copy allowance. Hold, outage, unknown outcome or restart keeps full quota and blocks copying503. Exact required storage compatibility includes conditional writes and scoped version enumeration/deletion.

## Decision #100 hold/deletion ordering and compatibility

Cleanup and new placeHold share a durable tenant/document deletion fence, acquired in the account-first lock order. Holdfirst prevents cleanup dispatch; fencefirst makes a new hold503 UNAVAILABLE until proven settled, with no hold/revision/success mutation. Current authorized replay of an already committed hold retains201 original receipt. Existing PlaceHold DTO, operationId and success/error wire shapes remain unchanged.

Exact-version deletion has one immutable attempt and single executor, automatic retries disabled. Unknown/lost/delayed outcome survives crash/lease expiry with full quota/fence. No HEAD/list absence, elapsed time, restart or canceled future proves settlement. Newhold/nextcopy cannot proceed until validated bound terminal response+no-further-execution proof+absence and atomic durable fence/accounting settlement.

Potentially indefinite new-hold503 is an explicit availability cost, not claimed legal-policy acceptance. Normal known-result cleanup and durable completed-attempt restart recovery remain required; disabling all cleanup is not completion. Implementation must prove no executable old deletion exists after any newly successful hold.

Existing append SDK names remain dmAppendVersion/bffAppendVersion and AppendVersion. Existing path/method/request DTO/201 CommandReceipt are preserved. BP03 adds explicit upload-specific typed failures (UploadError, including receipt/state/expiry errors) and429 resource admission; common ACL routes retain BP02 Error and all other original shapes/status semantics.
