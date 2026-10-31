# DKP-004 delivery status

BP03 / [#35](https://github.com/petarnenov/document-knowledge-platform/issues/35): RustFS quarantine, verified uploads and safe content. The full BP00–BP17 project goal remains active. BP02 completed through PR80/PR85 and integrated-dev run41229510527. No BP03 runtime implementation or approval is claimed at this checkpoint.

## Task state — 2026-10-31 18:44 UTC

| Issue | State |
|---|---|
| #86 public research | Candidate releases, immutable manifests and compatibility constraints researched; acceptance pending the reviewed specification |
| #87 design decision | Actual Codex consultation95531 selected OptionA as a proposal; verification by independent reviews89/90 pending |
| #88 specification | Initial draft frozen and validated; author resolving three independent review findings before re-review |
| #89 security review | REQUEST CHANGES: missing bounded authorized browser version discovery; all other reviewed security boundaries coherent in development scope |
| #90 architecture review | REQUEST CHANGES: retry-copy quota accounting and missing additive action wire delta; OptionA staging accepted |

Claude consultation90943 ended without a decision because its monthly spending limit was reached. The available Codex fallback produced decision95531 with medium confidence pending review. Only its proposed specification actions are being applied. No production/security acceptance follows from the consultation.

## Public research — #86

Candidate pins checked on2026-10-31, subject to independent review and actual compatibility tests:

| Component | Candidate | Primary source |
|---|---|---|
| RustFS | 1.0.4 | [Official release](https://github.com/rustfs/rustfs/releases/tag/1.0.4) |
| ClamAV | 1.5.4 | [Official release](https://github.com/Cisco-Talos/clamav/releases/tag/clamav-1.5.4) |
| AWS Java SDK | 2.57.8 | [Official release](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.57.8) |
| PDFBox | 3.0.8 | [Official downloads](https://pdfbox.apache.org/download.html) and [security notices](https://pdfbox.apache.org/security.html) |

Registry manifest inspection succeeded for RustFS index `sha256:ed9210d6411d77ffceb70d94b964a3cb7d225983368bc25bebe01e995293dcc2` and ClamAV index `sha256:bda39705e291105049c3936e2803ac90b955172af8030f94f05dce1252df6c6d`. This is image-identity evidence, not a runtime, provenance or vulnerability-test result.

RustFS documents [S3 compatibility](https://docs.rustfs.com/en/administration/protocols/s3), [non-root containers](https://docs.rustfs.com/en/installation/container), [IAM](https://docs.rustfs.com/en/security-compliance/iam) and [native policy administration](https://docs.rustfs.com/en/security-compliance/iam/policies). The exact pinned release must prove required SigV4/path-style, versioned writes/reads/deletion, private buckets, TLS and scoped credential behavior. Unsupported S3 ACLs must not become an assumed control. Root credentials must not be application credentials; MinIO administrative compatibility is not presumed.

The [AWS checksum guidance](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html) describes automatic CRC32 in recent SDKs. [Integrity configuration](https://docs.aws.amazon.com/sdkref/latest/guide/feature-dataintegrity.html) defaults to WHEN_SUPPORTED. Exact-release tests must establish explicit SHA256 upload and exact-version readback, with independent server hashing; validation may be skipped when a supported stored checksum is absent. A silent checksum-disable workaround is not acceptance.

ClamAV acceptance uses the maintained standard signature database, freshness and real benign/EICAR tests. Its [protocol](https://docs.clamav.net/manual/Usage/ClamdProtocol.html), [container guidance](https://docs.clamav.net/manual/Installing/Docker.html) and [support lifecycle](https://docs.clamav.net/faq/faq-eol.html) inform bounded INSTREAM, non-root operation and resource provisioning. Missing/stale definitions, partial results, limits, timeouts and unavailable scanning must not issue receipts. A tiny test database cannot replace standard definitions.

## Proposed package boundary

Upload completion creates verified evidence and an actor/document-bound receipt. Existing explicit version append consumes that receipt atomically with the immutable version and existing201 command receipt; it changes no active pointer. BP05 retains the final atomic version/run/intent/outbox and202 handoff obligation, including compatibility for pre-processing versions. The root-owned System Design/queue clarification is proposed until reviews89/90 accept it and development approval is recorded.

Next: freeze the full draft, independently review it, resolve findings, validate contracts, record reviewed fingerprints and development approval before source implementation. Every independent implementation task requires a prioritized issue before work. No production release, retention destruction or CID07/CID15 sign-off is granted. Formal status remains every15minutes; the next checkpoint is18:59UTC.

## Initial validation and findings

Root strict OpenSpec10/10, configured Redocly zero warnings, strict draft2020 validation of both private JSON schemas, five inspector boundary rejection fixtures, existing BP02 append/receipt wire semantics, closed objects and DM/BFF parity passed. These are specification checks, not runtime evidence.

Author88 is resolving authorized exact-version discovery without widening command receipts, charging every possible failed/retried copy before writes or requiring confirmed cleanup, and freezing the additive content.review ACL/catalog/SDK/Zod contract. Independent89/90 re-review is required before approval. Planned P1 runtime issues91–99 exist before execution; all remain NOT STARTED pending approved specification.

## Follow-up coordination proposal — #100

Both independent re-reviews accepted the initial corrections but found a hold/deletion race. Actual Codex consultation `20261031T185045Z-codex-18639` selected durable document-scoped deletion fencing with conservative treatment of unknown outcomes. Author88 is applying this proposal; independent89/90 acceptance is pending. A new hold must not report success while an earlier remote deletion could still execute. The proposed existing503 response, persistent fence and full charges can remain indefinitely for genuinely unresolved attempts; this availability cost requires explicit development review. Normal known-outcome cleanup and completed-attempt restart recovery remain required. No production or legal-policy approval is inferred. Root also requires preservation of existing append SDK operation and DTO names.

## Development approval — 2026-10-31 18:55 UTC

Final independent security89 and architecture90 re-reviews PASS, explicitly accepting the disclosed development fence availability tradeoff. Root specification checks pass, including12 preserved common schemas (sole additive Action), existing SDK method/DTO/success,22 DM/BFF operations and13 negative schema fixtures. Development approval and reviewed manifest are recorded in the active OpenSpec change approval.md. Specification commit/PR integration is next; runtime91–99 remains unstarted. Earlier pending statements above describe historical checkpoints.
