# Frozen proposed DM upload/receipt relational contract

Task #88, proposed away95531 stage contract; reviews #89/#90 pending. This is not a migration or implementation approval. All tables are owned solely by DM in its existing logical database/schema. No sibling service may read/write them directly.

## Table shapes

`dm.upload_slots`: primary key(tenant_id,upload_id), same-tenant document FK, actor_issuer/actor_subject, created_at/expires_at, expected_document_revision, declared_format, declared_size, state, state_revision, body_idempotency_key/body_sha256, actual_size, quarantine_object_identity, attempt_count, transfer_attempt_count, lease_id/lease_until, possible_original_key, possible_original_version_id, copy_write_settled, copy_cleanup_confirmed, failure_code, cleanup_pending and quota_held. Server-generated UUIDs, timestamps and object identities only.

`dm.upload_receipts`: primary key(tenant_id,receipt_id), UNIQUE(tenant_id,upload_id), exact document+actor FK/binding, size_bytes, sha256, detected_mime, immutable_original_identity, verification_evidence_id, created_at, expires_at. Verified facts are insert-only; no runtime UPDATE/DELETE. No client can set these fields. The identity contains a server-owned bucket/key plus exact non-null S3 version ID; no caller key or mutable latest alias.

`dm.upload_verification_facts`: primary key(tenant_id,evidence_id), same-tenant slot FK, source SHA/size/object identity, detected format/MIME, inspector plugin/version/image/profile digest, inspected_at, scanner engine version, standard signature database version/timestamp/digest, scanned_at and result=VERIFIED. Insert-only. Failed attempt/audit codes persist separately without content/provider diagnostics.

`dm.upload_receipt_consumptions`: primary key(tenant_id,receipt_id), same-tenant receipt FK, document/version FK, actor_issuer/actor_subject, append command scope/key/fingerprint and consumed_at. UNIQUE(tenant_id,document_id,version_id). Insert-only. A receipt can have at most one consumption, and a version can consume at most one receipt. No mutable consumed flag on immutable receipt facts.

`dm.upload_command_outcomes`: primary key(tenant_id,actor_issuer,actor_subject,operation,resource_scope,idempotency_key), canonical_body_hash, status, upload_id and nullable receipt_id, created_at. Insert-only. CHECK status in(200,201); typed response shape derives from operation, never arbitrary persisted JSON. Existing append remains in dm.command_outcomes with exactly documentId/revision.

`dm.upload_resource_accounts`: primary key tenant_id, admitted_count, reserved_transient_bytes, promised_original_bytes, retained_original_bytes, revision. Nonnegative bounded counters; global row in a separately named `dm.upload_global_resource_account` has one constant key. Rows locked in the fixed global→tenant→document→slot→receipt/consumption→command/outcome order for all transitions touching these rows; counters are not advisory caches. Reconciliation never drops charges on expired leases alone.

`dm.upload_cleanup_work`: primary key(tenant_id,upload_id,object_identity), exact server-owned identity, reason=REJECTED|EXPIRED|TRANSFER_ORPHAN|FAILED_VERIFIED_COPY|VERIFIED_QUARANTINE_COPY, created_at, attempt_count, next_attempt_at, lease_id/lease_until and completed_at. Tasks survive restart until exact-object deletion or authoritative absence is confirmed. Persistent evidence/version originals are not cleanup targets. Current legal hold or unavailable hold authority blocks deletion and keeps charges; upload expiry never overrides retention of a consumed version.

`dm.upload_audit_facts`: insert-only server event UUID, tenant/document/slot, verified actor, operation, state_revision and occurred_at plus bounded outcome code. No file body/name, raw scanner output, credentials or public object URL. Distinct from logs/events; final compliance audit lifecycle remains BP15.

## Constraints and authority

Every foreign key includes tenant; document/version associations also include document_id. Receipt consumes only its bound document and exact issuer/subject. SHA256 lowercase64hex, size1..100000000, known format/MIME mapping only. Receipt expiry equals slot expiry and cannot be extended by retries. Immutable S3 version ID is mandatory; release incompatibility blocks acceptance rather than substituting mutable keys.

State enum: INITIALIZED, RECEIVING, QUARANTINED, VERIFYING, VERIFIED, CONSUMED, REJECTED, EXPIRED. State revision is nonnegative JavaScript-safe integer. RECEIVING/VERIFYING require bounded lease; other states have no active work lease. Verification attempts <=3; transfer attempts <=3 under the same original expiry. REJECTED/EXPIRED cannot revive; VERIFIED cannot be overwritten or re-inspected into different evidence.

Runtime has only necessary SELECT/INSERT and scoped state/counter/cleanup UPDATE; immutable receipt/evidence/consumption/outcome/audit facts have no UPDATE/DELETE. Migration/history/public/sibling DB privileges remain denied. No production retention policy or cleanup duration for retained originals is created.

## Atomic transitions

Initialize: authorize current document.version.append+ACL, lock global→tenant accounts then current document/slot in the fixed order, check expected document revision, enforce count/bytes, insert INITIALIZED slot+command outcome+audit and charge two-copy reservation in one transaction. No document aggregate revision change. Concurrent same command returns one slot only after current authorization.

Receive: current operation+ACL+slot actor, claim INITIALIZED→RECEIVING with unique lease. Stream bounded bytes to a new immutable quarantine object; never overwrite an existing identity. Recheck authority and count/hash/size before QUARANTINED+outcome+audit transaction. Unknown storage outcome remains charged with durable cleanup/reconciliation; it cannot issue verification evidence.

Complete: current operation+ACL+slot actor; claim QUARANTINED→VERIFYING with unique lease, attempt+1. No DB lock remains held across network/inspection. Scan and inspect the exact readback SHA/size, then store immutable verified original and verify its exact version/hash. Final policy, lease, expiry and source identity checks precede VERIFIED+receipt+evidence+outcome+audit in one transaction.

Transient scanner/inspector/storage failure records bounded failure and returns503; VERIFYING returns to QUARANTINED only after the exact lease is safely closed and any possible original copy from that attempt is authoritatively deleted/absent. Retry uses a new idempotency key, max3 attempts under the original expiry. Terminal malicious/structural/size failure→REJECTED and durable cleanup. Expired/ambiguous leases require recovery; they never release quotas or imply clean content.

Append: existing application UoW locks current document and exact scoped receipt, reauthorizes original append action+ACL, checks expected revision, unexpired immutable evidence/object identity and absent consumption. Version+consumption+document revision+existing command outcome+audit+slot CONSUMED+counter reclassification commit together. Rollback leaves receipt usable; unique constraints resolve competing consumption.

Existing append-command replay first rechecks current membership/catalog/ACL then returns original201 CommandReceipt even though its receipt is consumed. Different append command with consumed receipt returns409 RECEIPT_CONSUMED after authorization; wrong actor/tenant/document or unknown receipt404. No successful replay invents a second version or exposes inspection/content facts.

Reclaim: slot expires60min from initialization without extension. New body/complete/append returns409 UPLOAD_EXPIRED after current authorization. Persist EXPIRED+cleanup work; count/reserved quota remains until every nonretained object is deleted/absent. CONSUMED releases admitted count and reclassifies retained original bytes, but any quarantine copy stays charged until deletion confirmed. Restart reconciles persisted charges/leases rather than resetting them.

## Port changes

Keep pure storage, scan, inspection, admission, receipt and transaction ports in DM application. S3 SDK/HTTP/SQL types stay in adapters. Replace verify-only trust with UoW-bound receipt lookup/locking and atomic consumption. S3 verifies exact identity/hash outside SQL, then the transaction confirms unchanged bound evidence; no distributed transaction claim.

A receipt may expose the existing VerifiedArtifact value internally only after the real authority has established all evidence. Returning VerifiedArtifact alone MUST NOT consume it or permit reuse. Runtime external authority remains unavailable; tests cannot substitute permissive scanner/storage/evidence bindings into production factories.

## Exact storage identity and database bounds

Private persisted receipt shape is contracts/verified-receipt.schema.json, never a browser request schema. Operational bucket names are dkp-quarantine and dkp-originals. Server key is lowercase SHA256(UTF8 tenant) + '/' + document UUID + '/' + server artifact UUID; raw tenant/path input is not interpolated. Exact non-null versionId is mandatory. Tenant/issuer/subject bounds match BP02; provider version ID<=1024bytes; timestamps UTC microsecond precision.

Retained original capacity is reserved at admission as promised_original_bytes, separately from two-copy transient bytes. On append, promised→retained is atomic with consumption; transient charge for any quarantine copy remains until exact deletion. Expired/rejected unconsumed objects release promised/transient charge only when cleanup succeeds and current hold policy permits it. No negative/unaccounted counters are valid.

Use foreign keys/unique constraints on exact table tuples, state transition CAS and safe integer revision<=9007199254740991. Slot body/source/evidence/object identities become write-once after successful corresponding transition. Receipt created_at<=expires_at=slot.expires_at; verification timestamps precede receipt issuance; standard DB age<=24h at verification. Consumed version source equals immutable receipt facts.

## Closed state edges and cleanup authority

Allowed edges only: INITIALIZED→RECEIVING; RECEIVING→QUARANTINED on complete bytes; RECEIVING→INITIALIZED only after safe interrupted-attempt cleanup/known absence; QUARANTINED→VERIFYING; VERIFYING→VERIFIED on complete evidence or→QUARANTINED only on safely closed transient attempt with confirmed prior-copy cleanup; VERIFIED→CONSUMED in append transaction. Any unconsumed state→EXPIRED on expiry; nonterminal validation failure→REJECTED. Terminal rejected/expired never revive.

Recovery of an expired lease first reconciles the exact object/attempt result and final authority. It cannot infer absence, VERIFIED or consumption from timeout. Exhausted3 attempts rejects further work with409 UPLOAD_STATE_CONFLICT, retaining charged state until safe cleanup/expiry. An unrecorded or unknown-outcome verified copy must become durable FAILED_VERIFIED_COPY cleanup work; no receipt is inferred from object presence. No new copy attempt is permitted before confirmed cleanup.

Before dispatching deletion, acquire the durable document deletion fence under the account-first row order and authoritatively inspect current holds, eligibility and consumed associations. Hold or unavailable check blocks fence acquisition/deletion and quota release. A standalone check followed by an unfenced network call is forbidden. Expiry authorizes only uncommitted upload-object reclamation, not retained version/evidence purge. Consumed originals are excluded regardless of slot expiry. Quota exhaustion under holds is a deliberate safe state requiring later BP15 policy, never automatic hold release.

## Amendment #90: fixed two-copy reservation and retry fencing

Choose confirmed cleanup BEFORE another copy, not extra-copy reservation. A charged slot may have at most one quarantine source and one possible verified-original copy at any time. Persist the exact proposed original key and write-attempt lease BEFORE PutObject; unknown version/outcome is still a possible full-size copy under the unchanged2x reservation. No unrecorded copy is uncharged.

Before any retry creates another original, the previous writer must be fenced/settled and every possible version at its exact server-generated key must be authoritatively enumerated and deleted/absent. A timed-out request, expired lease, canceled client future or one absent HEAD is insufficient while a delayed write may still complete. Unknown outcome remains blocked503 with durable cleanup and full charges. No concurrent retry/SDK retry may create another copy.

Storage adapter MUST use tested conditional create semantics and bounded write-attempt behavior; never blind retried versioned PUTs. Exact-prefix ListObjectVersions/HEAD/GET and exact-version deletion compatibility/scoped IAM are required for recovery. If settling an ambiguous write cannot be proven, keep the slot blocked; do not reset counters or issue a new key. No deadline bypasses this gate.

Hold/unavailable cleanup authority, storage outage and process restart preserve the possible-copy marker, lease fencing and quota. Resume new verification only after durable copy_cleanup_confirmed tied to the same attempt and current authoritative cleanup. Deleted failed copy frees its occupied half of the existing reservation for reuse, not the reservation itself. A successful receipt permanently binds its verified copy; it never enters this retry path.

No database lock is held across S3/scanner/inspector I/O. Every transaction uses a subset of the same global→tenant→document→slot→receipt/consumption→command/outcome row-lock order; idempotency advisory scope is acquired before row locks. Cleanup takes accounts before document holds/slot so append, initialization and reclamation cannot invert their order. The durable deletion fence, not repeated unfenced checks, serializes hold placement and remote deletion.

## Amendment #89: consumed receipt version discovery

GET /api/v1/documents/{documentId}/upload-receipts/{receiptId}/version returns exactly{documentId,versionId}, at most1024bytes; BFF mirror inserts /dm. Requires current document.content.review catalog+same-action ACL and exact receipt issuer/subject/tenant/document ownership. Unknown/unconsumed/wrong-scope receipt or missing consumption/version404; missing action404 before lookup, unavailablecatalog503. No list/count or metadata.

Lookup joins only immutable tenant+document+receipt+consumption+version association, resolves at most one row and rechecks current authority/ACL and binding immediately before response. Consumed receipt remains discoverable after upload admission expiry because this is exact retained-version review, never permission to reuse it. No scan of hidden rows, pagination, total or existence hint. The opaque receipt alone grants nothing.

Possible-copy recovery records may carry an unknown versionId before PUT outcome is known; this is never a verified receipt identity. They bind the exact server-generated key/write attempt and require authoritative exact-key version enumeration after writer settlement. Only a discovered exact non-null version can be deleted or bound as evidence. Never treat an unknown version as mutable latest or delete another key/prefix.

## Decision #100 amendment: durable deletion fence versus new legal hold

Actual away18639 (/Users/petarnenov/.config/away/runs/20261031T185045Z-codex-18639) proposes A+C: durable DM fence plus fail-closed remote uncertainty. This is a proposal requiring independent89/90 acceptance, not legal/security/production approval. No SQL locks are held over network; durable serialization survives lock release.

`dm.upload_deletion_fences`: one unresolved fence per(tenant_id,document_id), immutable attempt_id UUID, upload_id, exact immutable bucket/key/non-null versionId, executor_instance_id, created_at and phase=AUTHORIZED|DISPATCHED|SETTLED_SUCCESS|SETTLED_FAILURE. Attempt history/terminal evidence is append-only; unresolved active association is durable. Unknown-version recovery cannot dispatch until it resolves the exact non-null version.

Acquisition transaction follows global→tenant→document→slot→receipt/consumption→command/outcome order, with fence rows under the document lock. Check authoritative current holds, eligibility, no retained/evidence/consumed association and no active conflicting fence before inserting immutable attempt+fence and committing dispatch authorization. Fence prevents consumption/rebinding of its candidate; consumed originals and evidence are never eligible.

`placeHold` first performs existing actor/membership/catalog/ACL, current authorization and successful same-command replay handling. In the same document-serialized UoW as new hold insertion, unresolved deletion fence yields503 UNAVAILABLE with NOhold, revision increment, successful outcome or success audit. Previously committed authorized same-key201 replay remains original CommandReceipt. If hold commits first, cleanup cannot authorize its deletion attempt.

Only the bound executor may claim AUTHORIZED→DISPATCHED once under the fence, then invoke exactly one exact-version DeleteObject after that commitment. SDK automatic retries are OFF. No new executor/worker may reuse, rebind or transfer this dispatch authority. Durable phase alone never proves whether a remote request was sent; crash before/after invocation remains unresolved until settlement is independently provable.

Normal settlement requires a validated terminal RustFS response for the exact bound request/attempt/object version, an exhausted one-shot executor capability with no queued/pending dispatch or SDK retry, and authoritative exact-version absence. Record terminal evidence and close active fence atomically with cleanup/accounting updates under the same lock order. Only then may a new hold commit or reserved half be reused.

Confirmed remote failure may settle only with evidence no delayed execution remains possible; record terminal failure and end that attempt, but keep existing object/full charges pending a newly authorized cleanup attempt. HTTP timeout, connection loss, cancellation, lost response, process crash, stale worker or lease expiry are NOT terminal-settlement evidence. Absent HEAD/listing alone cannot authorize fence removal.

Restart preserves unknown fence/executor identity/charges. Without independently reviewable proof that prior executor cannot dispatch AND remote request cannot later execute, do not transfer dispatch authority or release fence; new hold remains503 indefinitely if necessary. A fresh policy read, expired lease or operator guess cannot override this. Normal known-outcome cleanup and recovery of durably completed attempts remain mandatory BP03 capability.

Terminal evidence includes immutable attempt ID, exact object/version, bound SDK request ID/response classification, terminal observed_at, one-shot executor exhausted/no-retry proof and authoritative exact-version absence result. A response for another request/version cannot settle it. Proof implementation and exact RustFS semantics require independent review and fault tests; the spec grants no assumed cancellation or distributed exactly-once guarantee.

Availability cost: an unresolved deletion may indefinitely block new hold placement with503 and retain full quota. UI/error reporting must not claim a hold was placed. This development operational ordering needs explicit89/90 acceptance and does not select legal hold policy for production. At the point a new hold succeeds, no prior fenced deletion can remain executable.
