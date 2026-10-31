## ADDED Requirements

### Requirement: Scoped persistent upload admission
Uploads SHALL bind exact verified issuer/subject/tenant/document and require current document.version.append catalog AND ACL authority. Init SHALL atomically reserve persistent tenant/global count and two-copy bytes. Max5 tenant slots,10 global and frozen byte quotas SHALL include scanning, verified-unconsumed and cleanup-pending objects. Slot/token SHALL confer no bearer authority.

#### Scenario: Concurrent sixth upload
- **WHEN** six same-tenant initializations race with distinct keys
- **THEN** at most5 slots are admitted; remaining429 occurs without leaked state or partial quota records

#### Scenario: Tenant or actor substitution
- **WHEN** another tenant/issuer/actor guesses a slot or supplies storage authority
- **THEN** it cannot transfer/inspect/complete/consume it; protected resource404 is indistinguishable

### Requirement: Bounded binary quarantine
Only reviewed fixed binary routes SHALL accept1..100000000 bytes, separately from16384byte JSON guards. Server SHALL compute SHA256 and actual length while streaming to private quarantine. MIME/extension/client checksum SHALL not establish trust. Current operation/ACL/ownership SHALL be checked before transfer and finalization; interrupted/ambiguous writes SHALL remain charged for reconciliation.

#### Scenario: Length and body boundary
- **WHEN** actual bytes exceed100000000 or differ from admitted size
- **THEN** transfer fails413 or422 SIZE_MISMATCH with no verified receipt/version and durable cleanup

#### Scenario: Mutation guards for raw bytes
- **WHEN** binary PUT lacks exact Origin or session CSRF at BFF
- **THEN** no body is forwarded or upload state changed

### Requirement: Real isolated structural and malware verification
Every accepted PDF/DOCX/PPTX/XLSX SHALL pass exact signature/package, structural limits and standard ClamAV scan before receipt issuance. Frozen limits SHALL include PDF1000 pages, archive/XML/deadline/expansion caps. Encryption, macros, active content, external references and unsafe archives SHALL fail. Inspection SHALL be non-root/resource-bounded/no-egress without storage/IdP/DB credentials.

#### Scenario: All supported families
- **WHEN** real benign synthetic PDF, DOCX, PPTX and XLSX complete verification
- **THEN** each receives actual byte-bound evidence; no family is replaced with a stub or filename check

#### Scenario: Malicious or unavailable verification
- **WHEN** EICAR, active content, bomb, timeout, stale standard definitions or scanner/inspector error occurs
- **THEN** no clean receipt/version is issued; content stays inaccessible with safe errors/audit

### Requirement: Durable verification state and expiry
Slots SHALL follow the closed state/lease/retry contract and expire60min after creation without retry extension. State/quota/command/audit facts SHALL persist across restart. Cleanup SHALL remain durable until exact deletion/absence, never release quota from lease expiry alone. Active legal holds or unavailable hold authority SHALL block object deletion; retained version originals SHALL never be expired by upload cleanup.

#### Scenario: Restart with verified unconsumed slot
- **WHEN** DM restarts after receipt issuance but before append
- **THEN** the slot/evidence/quota survives and the same scoped receipt can be consumed only under current policy and unexpired state

#### Scenario: Expired object deletion fails
- **WHEN** cleanup encounters storage outage or hold
- **THEN** the work and charges persist; no object is exposed or retention bypassed

### Requirement: Immutable verified receipt and single consumption
Complete SHALL return200 exactly{uploadId,receiptId}, create no version and bind immutable evidence to actor/tenant/document/hash/size/MIME/exactS3version. Existing append SHALL atomically consume one receipt with version/revision/command/audit and return201 exactly{documentId,revision}. Another command SHALL not reuse it. Rollback SHALL preserve usability; activeVersionId SHALL remain unchanged.

#### Scenario: Concurrent receipt consumption
- **WHEN** two distinct authorized append commands target one receipt
- **THEN** one201 commits; the other409 RECEIPT_CONSUMED, without second version/partial audit

#### Scenario: Append rollback and replay
- **WHEN** append fails before commit or its committed command is retried
- **THEN** rollback leaves receipt usable; authorized same-key replay returns only original201 receipt without consuming twice

### Requirement: Honest staged processing boundary
BP03 SHALL implement verified completion then explicit append201 only. It SHALL expose no202/PROCESSING or readiness claim. BP05 SHALL retain atomic DocumentVersion+ProcessingRun+ProcessingIntent+outbox and202 ACCEPTED_PENDING_DISPATCH, with explicit reviewed legacy-version/append compatibility. Root proposed System Design staging clarification SHALL require independent reviews89/90 and recorded approval.

#### Scenario: Upload succeeds before workflows exist
- **WHEN** a verified upload is explicitly appended in BP03
- **THEN** immutable UNREVIEWED version exists but no processing/publication success or active pointer change is fabricated

### Requirement: Two-copy retry accounting fence

A slot SHALL reserve at most one source plus one possible original copy. Exact attempted key/lease SHALL persist before write. Another original copy SHALL be forbidden until prior writer is settled/fenced and all possible prior versions are deleted/authoritatively absent. Timeout, canceled future, expired lease or absent HEAD alone SHALL NOT prove absence. Holds/outage/unknown outcomes SHALL retain quota and block retries503 across restart.

#### Scenario: Crash after possible original PUT
- **WHEN** RustFS may have stored an original but the verification transaction or process fails
- **THEN** durable possible-copy/cleanup facts and full quota survive; no new copy/key/SDK retry exceeds the2x reservation before confirmed cleanup

#### Scenario: Cleanup held or unavailable
- **WHEN** a failed copy is held, delayed, unknown or cannot be deleted/checked
- **THEN** subsequent verification cannot create another copy and quota remains charged; safe cleanup can later permit reuse of the reserved half
