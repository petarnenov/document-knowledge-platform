# BP05 prerequisite research — issue 379

Status: **candidate evidence only; runtime selection blocked**. Base `9cf712ec`, observed 2026-11-01. This packet changes no approved requirement, wire schema, build dependency, SDK, runtime or prerequisite. It grants no architecture, security-control, CID, license or production approval. Independent review and applicable checks remain necessary. BP03/BP04 integration and the approved BP05 implementation gates remain dependencies.

## Exact candidates and evidence

`candidate-pins.json` records exact coordinates, artifact/POM/source-JAR SHA256, inherited POM-license chains, the isolated resolved graph and image identities. Registry evidence contains original response bytes, not reformatted copies. Anonymous registry credentials were held in memory and never saved. Only manifests/config metadata were fetched: no image layers, pulls or provider execution.

| Candidate | Evidence and disposition |
|---|---|
| Temporal SDK/testing 1.40.0 | [Official release](https://github.com/temporalio/sdk-java/releases/tag/v1.40.0), exact Maven metadata/POM/source/binary bytes. Same SDK/test version. Apache-2.0 declared by POM. Candidate only; raw graph has advisory matches. |
| Temporal server 1.32.1 | [Official release](https://github.com/temporalio/temporal/releases/tag/v1.32.1); registry label source revision `af08a5e5f2dd40f545f2afac7780daaa6987001e`, MIT license declaration. Server image rather than auto-setup. Digest identity does not authenticate a publisher or prove image contents safe. |
| Kafka broker/client 4.4.0 | [Official download page](https://kafka.apache.org/community/downloads/), exact client Maven bytes and `apache/kafka` registry metadata. Client POM declares Apache-2.0. Broker labels identify Apache but do not supply a source revision: release-to-image build provenance remains unverified. |
| Java JCS 1.1 | RFC8785 [Appendix G](https://www.rfc-editor.org/rfc/rfc8785#appendix-G) links this implementation; [upstream README](https://github.com/erdtman/java-json-canonicalization) advertises this coordinate. Last Maven metadata update is 2018. **Not acceptable as a standalone raw-input canonicalization boundary:** seven actual rejection failures below. Maintenance evidence also remains unresolved. |
| JS reference `canonicalize` 5.1.0 | [Upstream](https://github.com/erdtman/canonicalize), exact npm tarball integrity and SHA256 plus library SHA256; Apache-2.0, no declared dependencies. Reference for parsed JSON values only, not a duplicate-key or UTF8 parser. No production package was installed. |

| Image | Index SHA256 | Linux/amd64 manifest SHA256 | Linux/arm64 manifest SHA256 |
|---|---|---|---|
| temporalio/server:1.32.1 | acecbab5b0b8ae9cf4012b508866ca97feda3709577bf157b537239f40029049 | 2695f2564ec51aa84232bf3fa59b31e2cce99bec437a64f5bc487cbe21b2de0e | 2bc795fc7e740235f9afcbfcff7049a55c5a3f0d87b6512d82687a485467795b |
| apache/kafka:4.4.0 | 833b867a9585a141da455dc2b57431c65cbccb27f11c91f694444cca9f777e0f | 76af3d0116abbb33b4cd964af410d4921c33d1f663e7a95d5453a9c35a84869b | 77b56ddfb7104fc5814a3d0127fcff49983220c7c7ad9bfc1f0af4d439228329 |

Each digest above uses the `sha256:` prefix when referenced. Index bytes were hashed locally; selected child-manifest/config bytes were additionally checked against their descriptor digests. Unknown/unknown attestation descriptors in indexes are not runnable platforms and were not verified as signatures/SBOMs. No unsupported architecture is selected. Pinning a multiarch index still requires the selected platform manifest to be checked at execution.

## JDK, dependency and security findings

An isolated Gradle 9.8.2 project using Maven Central and JDK 25.0.5 resolved the four direct candidates into **53** distinct artifacts. A separate production-only configuration (SDK, Kafka client, Java JCS) resolved **42**; `temporal-testing` and its additional artifacts are not proposed as production dependencies. This is an unmodified candidate graph, **not the repository/BOM-integrated graph or approved locks**. It includes `io.nexusrpc:nexus-sdk:0.6.0-alpha`; no Nexus business capability is authorized by resolving it.

All 53 POM license declarations were found directly or through a recorded parent chain (63 distinct POMs fetched). This is declared metadata, not a complete bundled/native-code NOTICE inventory or legal acceptance. Kafka compression artifacts include native code; matching Java classpaths does not prove native library compatibility on both target architectures.

The OSV package/version query reported **24 untriaged advisories across seven coordinates**, six in the production candidate graph and one (`json-smart`) only in testing. `advisory-matches.json` records IDs, feed-reported fixes and upstream references. Jackson core/databind 2.15.4, LZ4 1.11.2, Guava 33.4.8-jre, Micrometer 1.9.9, Snappy 1.1.10.7 and testing Json-smart 2.5.0 are affected by feed matches. **No clean scan, exploitability finding, suppression or approved override is claimed.** Feed fixes are leads for a separately tested dependency convergence proposal, not permission to upgrade transitives blindly. Re-query before selection; triage vendor advisories and inspect actual reachability plus shaded/native code. Image base-layer SBOM, signatures, vulnerability/license scans and provenance are still required.

Kafka 4.4 [official Java guidance](https://kafka.apache.org/44/operations/java-version/) explicitly supports Java 25; its [tagged JVM Dockerfile](https://github.com/apache/kafka/blob/4.4.0/docker/jvm/Dockerfile) uses a Java 25 JRE base. That does not identify the precise JRE patch inside these image layers. Temporal SDK's [tagged README](https://github.com/temporalio/sdk-java/blob/v1.40.0/README.md) states Java 8+ runtime and Java 21+ build requirements, not an exact JDK25 certification matrix. Temporal server is not a Java process; SDK-to-server and PostgreSQL18.6 interoperability still need actual tests.

Fresh `javac` and Java execution on **25.0.5**, using the resolved graph, exercised public Temporal duplicate-ID/conflict options and Kafka producer configuration. `acks=all` is internally normalized to `-1`; idempotence, max-in-flight1 and 10s/5s/5s configuration values were observed. No client, worker, broker or workflow server was started. API/configuration success is narrower than network/TLS/history/transaction compatibility.

## Shared canonicalization corpus and observed rejection gaps

`conformance-vectors.json` contains 45 finite cases: 31 positives (including every finite RFC8785 Appendix B sample), two non-finite Appendix B rejections and twelve additional raw-input negatives. Expected canonical UTF8 hashes are explicit. Cases cover recursive UTF16 ordering, array order, no Unicode normalization, binary64 boundaries/negative zero, escaping and large integers encoded as strings. The corpus is a seed, not exhaustive binary64, depth/resource or parser-fuzz certification.

Actual Java candidate: **38/45 pass; seven fail**. It accepts null-valued duplicate keys, escaped duplicate null keys, lone high/low surrogate values, a lone-surrogate key, a leading-zero number and malformed UTF8. The source parser checks `Map.put(...) != null`, which misses a prior null value; its byte constructor uses permissive UTF8 decoding. Failure is recorded rather than hidden behind an added sanitizer. The JS reference matches all **31 positive** expected byte strings/hashes; no raw-negative pass is claimed because it receives parsed values.

A future selection must either establish a maintained conformant Java implementation or independently review a strictly bounded raw-input/I-JSON validation boundary around a suitable serializer, and repeat shared Java/browser vectors plus malformed UTF8/duplicates/surrogates/overflow/depth/size tests. Do not hash a normal map serializer, silently replace invalid characters, normalize Unicode, accept NaN/infinity, or coerce BP05 revisions beyond exact schema bounds. No new wrapper implementation or exception to the maintained-dependency rule is approved here.

## Remaining gates without scope reduction

1. Resolve and lock a reviewed dependency convergence graph, including testing, gRPC/Netty/protobuf/Jackson interactions with the current Micronaut/JDK25 baseline. Repeat source/license/NOTICE/native/shaded/SBOM/security checks and the candidate probes; test both supported image platforms. No inherited vulnerable graph may be published as "verified" from this packet.
2. Authenticate image/release provenance and verify actual image contents, nonroot/resource/TLS profiles and exact JRE patch where relevant. Execute Temporal1.32.1 schema/setup/restart against its separately owned PostgreSQL18.6 database/credentials; do not reuse DM tables or treat auto-setup as production migration approval.
3. Execute real Temporal SDK/server mTLS and authorization negatives; exact workflow-ID duplicate/conflict behavior, immediate-worker/start lost-ACK reconciliation, attempt fencing, cancellation, durable 30-minute acceptedAt limit, restart/history replay and compatible deployment/versioning. Pure Workflow code must not perform I/O; absent later-stage capability must remain WAITING_FOR_CAPABILITY, not fake READY.
4. Execute durable global50/tenant5 ambiguous-plus-active accounting, pending1000/100, fairness, original deadlines through queue/retry/restart, immutable input/checkpoint recovery and stale workload/current-authority negatives. SDK configuration cannot enforce those DM invariants for the application.
5. Execute real Kafka4.4 broker/client TLS/hostname/authz, scoped producers/consumers, KRaft persistence, outbox retries/crash/restart/duplicates and ordered run aggregate sequences with document routing keys. Preserve at-least-once semantics: PG commit and Kafka acknowledgment are not atomic. Preserve selected finite producer budgets; local one-broker setup is not replication3/minISR2 or production HA evidence.
6. Complete strict canonicalizer selection/conformance and approved full BP05 contract/E2E/fault/coverage gates, legacy append201 compatibility and atomic202 version/run/intent/outbox behavior. Preserve CID07/15 and every architecture/security/production gate. This research neither waives prerequisites nor starts implementation.

## Verification and reproducibility

See `reproduce.md`. Research probes: two Java classes **32/32 lines, 4/4 branches** covered by JaCoCo0.8.16; Node comparison helper **100% lines/branches/functions**, seven unit/data tests. Candidate failure counts are intentional evidence, not a passing canonicalizer gate. Initial research-only probe wiring encountered Kafka `acks` normalization and ESM loading differences; corrected the observation adapter, not any candidate dependency. No provider state was touched.
