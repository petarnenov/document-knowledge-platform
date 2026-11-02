# BP05 advisory convergence research — issue 392

**Research candidate only; pin selection and implementation remain blocked.** Base `f63f635`, observed 2026-11-01. This subtree changes no production source, dependency, lock, approved contract, architecture/security control or prerequisite. Independent review is pending. No provider, image layer or container was started or pulled.

## Proposed isolated convergence

The four research roots stay Temporal SDK/testing1.40.0, Kafka client4.4.0 and Java JCS1.1. Temporal-testing stays outside the production-candidate configuration. The canonicalizer is retained solely to expose its known failure; it is not selected.

| Module/family | Original affected version | Research candidate | Primary release evidence |
|---|---|---|---|
| Jackson BOM and matching modules | core/databind2.15.4 | BOM2.22.4; annotations2.22 as specified by that BOM | [Jackson2.22 release history](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.22) |
| Micrometer BOM/family | core1.9.9 | 1.17.2 | [Official1.17.2 release](https://github.com/micrometer-metrics/micrometer/releases/tag/v1.17.2) |
| Guava | 33.4.8-jre | 33.7.2-jre | [Official33.7.2 release](https://github.com/google/guava/releases/tag/v33.7.2) |
| LZ4 Java | 1.11.2 | 1.12.1 | [Official1.12.1 release](https://github.com/yawkat/lz4-java/releases/tag/v1.12.1) |
| Snappy Java | 1.1.10.7 | 1.1.10.12 | [Official1.1.10.12 release](https://github.com/xerial/snappy-java/releases/tag/v1.1.10.12) |
| Json-smart — testing only | 2.5.0 | 2.6.0 | [Official2.6.0 release](https://github.com/netplex/json-smart-v2/releases/tag/v2.6.0) |

These are published stable upstream releases, not arbitrary major-version substitution or vendor certification. Micrometer Central metadata reports1.18.0-RC1 as latest; this proposal deliberately uses stable1.17.2. Upstream release activity is recorded in `release-evidence.json`; it is not an enterprise support SLA. Jackson3 is not substituted for Jackson2.

The isolated `research.gradle` uses enforced research BOMs plus strict constraints. This is **not a proposal to blindly force the same graph into the application**. Actual Micronaut BOM/locks, all other services and generated-code integration need their own convergence review and tests.

Fresh Gradle9.8.2/JDK25.0.5 resolution yields **54 combined /43 production-candidate artifacts**, versus53/42 originally. Eighteen resolved coordinates changed or were added. New Micrometer commons/observation and other transitive changes are included, not hidden as unchanged. `candidate-graph.json` records every JAR SHA256, POM/license-parent chain, changed source-JAR identity, BOM identity and query identity.

All54 artifact license chains reached declared POM licenses; changed source JARs were fetched and hashed. These are declared metadata facts, not a complete source/native/shaded/NOTICE/SBOM inventory or legal acceptance. The four probe-only JUnit/assertion-tool artifacts are separately identified and excluded from both candidate counts.

## All24 original advisory matches

`advisory-triage.json` binds every original ID to aliases, current Maven affected ranges/events, feed-reported fixes, exact original scope, proposed resolved coordinate, a fetched primary upstream advisory/issue/security source and explicit preconditions. No original match was suppressed or classified as not applicable.

The original seven affected coordinates comprise six production-candidate modules and testing-only Json-smart. Most Jackson findings require particular deserialization configurations or types; this does not justify retaining the affected library. Compression/native extraction and Guava Java-deserialization findings likewise retain their stated preconditions. Future BP05 feature reachability remains unproven; absence of a feature in these probes is no waiver.

A fresh OSV package/version query of the **entire exact54-artifact graph** returned **zero matches** at this observation time. Request/response hashes and all54 result identities are recorded. This is narrower than vulnerability-free: it does not scan image layers, native/shaded contents, future feed updates or actual application reachability. Repeat vendor/feed review before any selection and deployment.

For Micrometer the primary source is [Spring's CVE bulletin](https://spring.io/security/cve-2026-40984); for Json-smart it is the [upstream depth issue](https://github.com/netplex/json-smart-v2/issues/232) and fixed release. GitHub's advisory database remains secondary metadata for these two. The other22 entries link fetched upstream repository security advisories.

## Actual Java25 behavior and compatibility evidence

`ConvergenceProbe.java` executes seven bounded local checks: ignored record properties with naming strategy; string/depth parser limits plus legal input; Temporal payload/failure conversion; the SDK's actual Micrometer reporter bridge; Kafka's actual LZ4/Snappy codec round trips; ordinary Guava serialization compatibility; and Json-smart bounded-depth rejection.

The same compiled probe on the **original graph genuinely fails** because a record's ignored input field receives the untrusted value. On the candidate graph all seven checks pass. This causally demonstrates one repaired behavior, not all24 exploit reproductions. No large exploit payload, external connection, worker, workflow server or broker was used.

`PayloadCompatibilityProbe.java` writes one finite Temporal protobuf/JSON payload using the original graph, reads/re-encodes it with the candidate, then repeats the reverse direction. Both directions pass with equal payload bytes/facts. This finite stored-payload fixture does not certify all converters, application DTOs or real workflow-history replay.

The existing379 public SDK/Kafka configuration probes were freshly compiled/run with the candidate graph: duplicate-ID/conflict options, idempotence, normalized `acks=-1`, max-in-flight1 and original10s/5s/5s budgets match. No client was connected. The canonicalization corpus remains **38/45**, with the exact seven known raw-input failures; dependency alignment does not repair JCS1.1.

Actual compression used this host's native libraries on JDK25.0.5. Native-access and protobuf `Unsafe` future-removal warnings were retained. No production JVM flag was added to silence them. Linux/amd64+arm64 interoperability and future Java restrictions remain gates; this host run is not either platform matrix.

## Remaining blockers

1. Independent review of this candidate; actual application/BOM convergence and all-service tests before pin selection. No production graph or approved lock was changed.
2. Maintained strict raw-input canonicalizer selection. Known JCS rejection failures remain; no sanitizer, parser waiver or alternative implementation is approved here.
3. Actual Temporal SDK/testing/server1.32.1 and PostgreSQL18.6 schema/restart/history/TLS/authorization interoperability; Kafka4.4 broker/client TLS, authorization, persistence and fault tests on both supported platforms.
4. Authenticated image provenance/signatures, SBOM/license/NOTICE/security/native/shaded analysis, scoped nonroot configuration and exact JRE patch evidence. No image-layer evidence was obtained here.
5. Full approved BP05 atomic202/outbox/version compatibility, quotas/deadlines/fault/E2E gates and BP03/BP04 integration. All CID07/15, architecture/security and production approvals remain separate.

## Reproduction and test scope

See `reproduce.md` and `observed-results.json`. Node evidence-checker tests exercise missing/substituted records and advisory coverage, inconsistent versions/licenses, incomplete queries and newly reported findings; a zero-match snapshot never returns approval. Java and Node coverage cover these research adapters only, not third-party library internals or the application.
