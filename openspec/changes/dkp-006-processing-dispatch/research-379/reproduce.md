# Reproducing research evidence

Run from a clean checkout. Use a separate private temporary directory for downloaded public artifacts, classpaths and results. Do not add these dependencies to the application build or publish locks from this research. No credentials, Docker pulls, provider launches or existing runtime state are required.

## Integrity and provenance

`candidate-pins.json` supplies Maven metadata/POM/source/JAR URLs and SHA256 values. Fetch with normal TLS verification, then compare the actual bytes with the recorded identities before compiling. Do not use insecure TLS or a tag as an immutable identity. The four direct coordinates are:

- `io.temporal:temporal-sdk:1.40.0`
- `io.temporal:temporal-testing:1.40.0` (test scope)
- `org.apache.kafka:kafka-clients:4.4.0`
- `io.github.erdtman:java-json-canonicalization:1.1` (rejection failures expected)

The observed 53-artifact graph came from an isolated Gradle9.8.2 Java project with only `mavenCentral()` and all four coordinates as research dependencies. A second resolvable configuration omitted `temporal-testing` and yielded42 production-candidate coordinates. Export `runtimeClasspath.resolvedConfiguration.resolvedArtifacts` and compare each `group:name:version` and JAR SHA256 against `transitiveGraph`; do not substitute the current application's BOM. POM licenses were recursively obtained from Maven Central parents until a license declaration was found; each POM URL/hash is recorded. A production integration must independently resolve its actual BOM/locks and re-scan.

For registry reproduction, request an anonymous pull token from `https://auth.docker.io/token` for the exact repository, hold it in memory, and use it only as a non-logged authorization header to `https://registry-1.docker.io/v2/<repository>/manifests/<tag-or-digest>`. Accept OCI/Docker index/manifest types; hash exact response bytes. Compare selected linux/amd64 and linux/arm64 descriptor hashes, then config blob hashes and labels. Do not save/print tokens or put them on command lines. Only public metadata is needed. The offline unit test independently hashes the six saved original manifest/index files. It does not authenticate Docker registry signatures.

The OSV query used `https://api.osv.dev/v1/querybatch`, one Maven package/version tuple for each of the53 resolved artifacts, then fetched each matching `/v1/vulns/<id>`. The snapshot is explicitly untriaged and must be refreshed. Feed-reported fixed versions are not validated dependency overrides. `advisory-matches.json` preserves upstream advisory/fix references without importing complete advisory prose.

## Shared Java and browser-value corpus

Requirements: JDK25.0.5 and Node24.22.1. The Java classpath below must be the **verified isolated candidate graph**, not arbitrary local application classes. Obtain the reference `canonicalize`5.1.0 npm tarball from its recorded URL, verify both `dist.integrity` (SHA512) and computed SHA256, and extract only `package/lib/canonicalize.js` to a private file with `.mjs` extension. That ESM file must match `librarySha256`; no modification or npm installation is needed. It has no declared runtime dependencies.

Set `DKP379_EVIDENCE` to a private temporary directory; `DKP379_CP` to the isolated candidate JAR classpath; and `DKP379_REFERENCE` to the verified extracted `.mjs` path. These are public research inputs, never authority paths. With `JAVA_HOME` pointing at25.0.5:

```sh
export DKP379_SOURCE="$PWD/openspec/changes/dkp-006-processing-dispatch/research-379"
mkdir -p "$DKP379_EVIDENCE/classes"
"$JAVA_HOME/bin/javac" -g -cp "$DKP379_CP" -d "$DKP379_EVIDENCE/classes" "$DKP379_SOURCE/CanonicalizationProbe.java" "$DKP379_SOURCE/CandidateApiProbe.java"
node --input-type=module <<'JS'
import {readFileSync,writeFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
const source=process.env.DKP379_SOURCE;
const {inputRows}=await import(pathToFileURL(source+'/conformance.mjs'));
const {vectors}=JSON.parse(readFileSync(source+'/conformance-vectors.json'));
writeFileSync(process.env.DKP379_EVIDENCE+'/input.tsv',inputRows(vectors));
JS
"$JAVA_HOME/bin/java" -cp "$DKP379_EVIDENCE/classes:$DKP379_CP" CanonicalizationProbe "$DKP379_EVIDENCE/input.tsv" > "$DKP379_EVIDENCE/output.tsv"
"$JAVA_HOME/bin/java" -cp "$DKP379_EVIDENCE/classes:$DKP379_CP" CandidateApiProbe
node --input-type=module <<'JS'
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
const source=process.env.DKP379_SOURCE;
const {assess}=await import(pathToFileURL(source+'/conformance.mjs'));
const {default:reference}=await import(pathToFileURL(process.env.DKP379_REFERENCE));
const {vectors}=JSON.parse(readFileSync(source+'/conformance-vectors.json'));
const rows=assess(vectors,readFileSync(process.env.DKP379_EVIDENCE+'/output.tsv','utf8'),reference);
console.log(JSON.stringify(rows,null,2));
JS
node --test --experimental-test-coverage "$DKP379_SOURCE/conformance.test.mjs"
```

Expected API output: `WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE`, `WORKFLOW_ID_CONFLICT_POLICY_FAIL`, and `true:-1:1:10000:5000:5000` on three lines. Kafka `all` normalizes to `-1`. A missing SLF4J implementation warning in this isolated research graph does not start a client connection or grant a production logging choice.

Expected observations are preserved in `observed-results.json`: Java38/45, with seven rejection failures; JS31 positive passes. Raw-input negatives are not passed through `JSON.parse` and then claimed valid: duplicate names would already have been lost. Tests verify exact UTF8 hashes, result identity/completeness and byte-preserving input generation.

Actual Java probe coverage was measured with JaCoCo0.8.16 agent on both executions and a report restricted to the two research classes (32/32 lines;4/4 branches). Node's coverage of `conformance.mjs` was100% lines/branches/functions. These figures cover the observation adapters, not third-party dependency conformance or the BP05 implementation.

## Scope of actual evidence

The only executions were metadata retrieval, isolated dependency resolution, fresh local compilation, configuration inspection and finite canonicalization probes. No Temporal server, Kafka broker, application worker, Docker provider, PostgreSQL database, mTLS workload, production permission or30-minute workflow was exercised. Full approved integration/security/license/runtime acceptance remains pending.
