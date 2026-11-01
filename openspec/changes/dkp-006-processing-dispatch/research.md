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
