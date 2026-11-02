# Primary research and candidate selections — 2026-11-01

These exact releases are candidates for independentreview, not installed dependencies or production selections. No opaque latest tag. Imageplatform/indexdigests and transitive/JDK25/security/licenseprovenance verification remain mandatory before implementation; unresolvedpins block runtime/publication, not authorize a fallback. Existing Java25.0.5/PostgreSQL18.6/JDBC42.7.14/Flyway12.11.0/Micronaut5 baseline remains unless separately reviewed.

| Candidate | Official evidence | Selection implication |
|---|---|---|
| io.temporal:temporal-sdk1.40.0 and temporal-testing1.40.0 | https://github.com/temporalio/sdk-java/releases/tag/v1.40.0 (official latest release29September2026) | exactsameversion SDK/test; inspectgRPC/JDK25 resolution/CVEs and tests before lockpublication |
| Temporal server1.32.1 | https://github.com/temporalio/temporal/releases/tag/v1.32.1 (officiallatest8October2026) | use realselfhostedTLS/authz+privatePG; pin reviewedmultiarchdigest before runtime |
| Kafka broker/client4.4.0 | https://kafka.apache.org/community/downloads/ (officialrelease22October2026, supported4.4.0) | exactbroker/client; KRaft,TLS/authz anddurabledisk; pinimage digest before runtime |
| Temporal workflow identity | https://github.com/temporalio/sdk-java/blob/main/temporal-sdk/src/main/java/io/temporal/client/WorkflowOptions.java | defaultclosedIDreuse permitsduplicates; explicitlyRejectDuplicate+Failrunningconflict andpermanentDMtombstone |
| Deterministic upgrade | https://docs.temporal.io/develop/java/workflows/versioning | compatibleworker deployments/patching andrecordedhistory replay required; I/O inActivities |
| Timeouts | https://docs.temporal.io/develop/java/workflows/timeouts | product30minute limit intentionallyuses durableTimer+boundedexecution, notinfinite SDKdefault; noautomaticnewlogicalrun |
| Selfhostedsecurity | https://docs.temporal.io/self-hosted-guide/security | mTLS alone notfullauthorization; exact namespace/taskqueue/workload APIpermissions mustbeimplemented/tested |
| Kafkaproducer | https://kafka.apache.org/44/configuration/producer-configs/ | idempotentproducer requiresacksall andbounded ordering/retries; PGatomicity notimplied |

Browsertool verified officialreleasepages andsemantics. DirectPython metadata HTTPS couldnotconnect inthisenvironment (URLError); no fictitiousMavenchecksum/digest claim orregistryprobe result is recorded. Releasepage evidence selects exact candidateversion only; Mavenresolution andregistrydigestverification are future explicitgates. Workflows30min/global50 andstageisolation derivepublicSystemDesign8.2, notvendorsecurityapproval. Kafkaoutboxatleastonceandconsumerdedup followourPGboundary, notavendorexactly-once guarantee.

Fingerprint proposal259 uses [RFC8785 JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785), verified against the official RFC Editor text. Exact Java canonicalizer pin and shared conformance fixtures remain implementation gates; ordinary map iteration/JSON formatting is not a digest definition.

## Follow-up prerequisite evidence — issue 379 (2026-11-01)

[Candidate provenance and shared conformance packet](research-379/README.md) adds verified Maven/registry metadata and isolated JDK25 research probes. Unlike the earlier Python HTTPS attempt, verified-TLS curl succeeded. Candidate dependencies remain unselected: the raw graph has advisory matches and Java canonicalizer1.1 fails seven raw-input rejection cases. No approved requirement, implementation prerequisite or production approval is changed.

## Advisory convergence follow-up — #392

[Research packet](research-392/README.md), independently reviewed under [#397](https://github.com/petarnenov/document-knowledge-platform/issues/397), records a proposed54-artifact/43-production graph and a zero-match OSV snapshot after triaging all24 original matches. It does not approve production pins or prove server/history/platform interoperability. Canonicalizer selection [#393](https://github.com/petarnenov/document-knowledge-platform/issues/393), actual all-service/vendor Java25/PostgreSQL18.6 compatibility, image provenance/security and the remaining BP05 gates remain open.


## Full raw-input conformance research (#393)

[The published research packet](research-393/README.md), independently reviewed under [#400](https://github.com/petarnenov/document-knowledge-platform/issues/400) and [#407](https://github.com/petarnenov/document-knowledge-platform/issues/407), preserves all 83 inputs and 249 candidate observations. Neither tested pure-Java composition meets the full raw-input requirement. The strict GraalJS composition passes 83/83 as an experimental research candidate; it is not selected and does not resolve resource, platform, maintenance, security or production approval gates.

[#402](https://github.com/petarnenov/document-knowledge-platform/issues/402) retains all 56 artifacts in coordinate-qualified paths, including both distinct same-basename JS POMs. [#406](https://github.com/petarnenov/document-knowledge-platform/issues/406) represents all 68 file/hash bindings explicitly and losslessly; the final full candidate and commit-history secret scans pass without exclusions. The earlier unpublished scanner failure and correction are retained in the issue/PR record. Current-head CI and dev integration remain separate from source review; canonicalizer selection and every existing BP05 prerequisite remain open.
