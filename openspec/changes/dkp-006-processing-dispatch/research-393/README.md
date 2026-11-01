# BP05 RFC 8785 candidate research (#393)

Status: RESEARCH ONLY; no runtime selection or specification approval. A maintained pure-Java selection remains blocked. This packet changes no production dependencies, approved behavior, shared research, contracts, or deployment configuration. It preserves all 45 shared research-379 vectors and adds 38 public synthetic vectors. A finite corpus is conformance evidence, not a proof over every JSON value.

## Observed result

Actual Temurin 25.0.5+8, Node 24.22.1, verified primary Maven artifacts, strict raw-input validation:

| Composition | Shared vectors | Additional vectors | Selection limitation |
| --- | ---: | ---: | --- |
| Jackson 3.2.4 + Titanium JCS 3.0.0-M4/tree-io 1.0.0-M2 | 43/45 | 37/38 | Incorrect positive/negative smallest subnormal rendering; valid root null rejected |
| Jackson 3.2.4 + erdtman java-json-canonicalization 1.1 | 45/45 | 32/38 | Maintenance unresolved; valid root null/boolean/string/number/negative-zero APIs rejected |
| Jackson 3.2.4 + unchanged canonicalize 5.1.0 through GraalJS 25.4.4.1.1 | 45/45 | 38/38 | Experimental polyglot composition only; additional architecture, resources, security, license and compatibility gates open |

Titanium's smallest subnormal bytes are `{"n":4.9e-324}` and its negative counterpart; RFC 8785 Appendix B requires `{"n":5e-324}` and its negative counterpart. Its published source JAR `com/apicatalog/jcs/Jcs.java:236` uses `Double.toString`. The observed results preserve these failures explicitly. `Jcs.canonize(Object)` returns byte[], not the String shown in an outdated README example. No candidate serializer was patched or replaced with a custom serializer.

`observed-results.json` records every actual result, including all failures. The 83 inputs comprise 42 valid positives and 41 raw invalid cases. The unchanged JavaScript reference independently checks 31 shared plus 11 added value-level positives. Passing these value-level cases cannot prove raw-input rejection; the 41 negative results come from the executed Java compositions. Existing shared cases and expected bytes are unchanged.

## Strict parser composition (proposal, not approved implementation)

`StrictCompositionProbe.strictValue` uses the JDK UTF-8 decoder with REPORT, Jackson STRICT_DUPLICATE_DETECTION and FAIL_ON_TRAILING_TOKENS, and standard JSON parsing without permissive extensions. A parsed-tree validation walk rejects nonfinite double conversions and malformed Unicode strings/keys using the JDK UTF-8 encoder with REPORT. The walk produces no canonical bytes. This catches escaped/nested/null-valued duplicate properties, lone/reversed surrogates, leading zero/plus/invalid fraction/exponent, trailing roots, malformed UTF-8, NaN/Infinity/overflow and oversized finite-decimal-to-double overflow. Paired surrogates, root primitives and null remain valid. IEEE-754 rounding, including finite values outside the safe-integer range and underflow, is not silently changed into a new integer-only policy.

Only an upstream serializer generates canonical bytes. The Titanium path receives the validated Java value; erdtman receives the original validated UTF-8. The optional Graal path receives the original validated JSON as data through standard `JSON.parse`; only fixed, checksum-verified upstream module code is evaluated. No input JSON is evaluated as code.

## Maintenance, versions, APIs and provenance

`artifact-pins.json` records primary HTTPS URLs, SHA-256, byte sizes and declared POM licenses for all 20 coordinates, including sources. SHA binding does not establish publisher signature verification; signatures were not verified. `primary-observations.json` binds selected primary maintenance metadata and exact hashes observed on 2026-11-01.

- [Titanium's immutable security policy](https://raw.githubusercontent.com/filip26/titanium-jcs/c9111af69cca83d4c690d10783a6be93cd57d6f4/SECURITY.md) supports 3.x and explicitly excludes 2.x. [Maven metadata](https://repo.maven.apache.org/maven2/com/apicatalog/titanium-jcs/maven-metadata.xml) selects 3.0.0-M4; this is a milestone, not a stable-version assertion. Repository activity in October 2026 establishes recent maintenance, not a service-level guarantee. Its actual conformance failures block selection regardless.
- [erdtman's Maven release](https://repo.maven.apache.org/maven2/io/github/erdtman/java-json-canonicalization/maven-metadata.xml) remains 1.1, last updated in August 2018; [the repository](https://github.com/erdtman/java-json-canonicalization) reports last push October 2020. No maintained-support policy was established. This is unresolved maintenance, not an assertion of an official unsupported declaration. The strict parser repairs the seven original raw-parser acceptance failures, but does not repair primitive-root APIs or maintenance.
- [Jackson core's primary metadata](https://repo.maven.apache.org/maven2/tools/jackson/core/jackson-core/maven-metadata.xml) selected 3.2.4. The actual published dependency metadata selects databind/core 3.2.4 and annotations 2.22. This research does not update the application's existing platform-managed 3.2.3; any approved composition must resolve platform compatibility first. Initial six research coordinates declare Apache-2.0.
- [GraalJS 25.4.4.1.1](https://github.com/oracle/graaljs/releases/tag/graal-25.4.4.1.1) is a non-prerelease published September 2026. [Official documentation](https://www.graalvm.org/javascript/docs/) permits standard OpenJDK operation; this packet proves only the actual pinned version on actual Java25.0.5. The optional engine adds 14 Maven coordinates: two POM aggregators and 12 JARs. Declared licenses include UPL-1.0, MIT and Unicode/ICU; exact per-coordinate declarations are in the pins. This is provenance, not license approval or a full vulnerability audit.
- [canonicalize 5.1.0](https://registry.npmjs.org/canonicalize/5.1.0) is the unchanged official shared reference; npm declares Apache-2.0 and Node >=22. Its exact tarball SHA-256/SHA-512 and module SHA-256 come from research-379. The Node engine requirement is honored by the reference run; executing its unchanged module on Graal is the measured experiment, not an upstream compatibility promise.

## Experimental engine limits remain open

The experiment denies host access/class lookup, guest IO, native access, environment access, thread/process creation and polyglot access. An actual System class lookup is denied and `process` is absent. These settings do not establish a hardened sandbox or full hostile-input resource confinement. See the [official sandbox guide](https://www.graalvm.org/dev/security-guide/sandboxing/).

The child JVM uses Xmx128m, two active processors and a 60-second host-process timeout only to bound this research run. These are not approved production limits, a native-memory cap, hard guest CPU accounting or a proved cancellation mechanism. Temurin emits interpreter-only/JVMCI and Unsafe warnings; raw stderr is retained with a hash and is not suppressed. Engine extraction uses a fresh private research user.home. Startup latency, throughput, CPU, Java/native/guest memory, concurrency, parser depth/input limits, cache/native-file permissions, process containment, cancellation/settlement, full license/security/BOM review and operational support remain UNPROVEN. No Graal dependency is installed in an application. This optional result does not unblock the maintained pure-Java requirement or authorize a polyglot architecture.

## Reproduction

From the repository root, with the pinned existing JDK and Node available:

```sh
JAVA_HOME=/absolute/canonical/temurin-25.0.5-home python3 openspec/changes/dkp-006-processing-dispatch/research-393/reproduce.py --work-directory /absolute/canonical/parent/new-research-directory
node --test --experimental-test-coverage --test-coverage-include='**/research-393/compare.mjs' --test-coverage-lines=75 --test-coverage-branches=75 --test-coverage-functions=75 openspec/changes/dkp-006-processing-dispatch/research-393/compare.test.mjs
python3 -m unittest discover -s openspec/changes/dkp-006-processing-dispatch/research-393 -p test_reproduce.py
```

The output directory must be fresh with a canonical existing parent (on macOS use `/private/tmp`, not the `/tmp` alias). The script verifies every pinned artifact and the unchanged reference before compilation, uses only binary JARs on the classpath with an empty sourcepath, and executes all three compositions. Downloading sources onto a wildcard classpath would accidentally ask javac to compile dependency sources; the script avoids that error. Observed candidate failures are retained, not converted into a false selection PASS. The script does not invoke Gradle, containers, providers, shared runtime or application configuration.

Artifacts are retained in coordinate-qualified paths, including the distinct `org.graalvm.polyglot:js` and `org.graalvm.js:js` POMs whose identical basenames collided in the original run. The original failure and corrected regression evidence remain recorded under issue #402. The checked `execution-bindings.json` binds the actual compiled classes, verified artifacts and per-mode raw output hashes from the final run. Instrumented outputs are byte-identical to the uninstrumented 83-row outputs. `instrumentation-pins.json` separately binds JaCoCo 0.8.16, EPL-2.0, used only for research harness coverage. Third-party libraries are not included in claimed harness coverage. `ProbeEdges.java` exercises research entrypoint guards; it is not a canonicalization implementation.

Actual checks: two Node assessor tests and four Python reproducer regression/negative tests PASS; all 249 candidate rows executed. Strict Java harness 41/41 lines and 34/34 branches; optional engine harness 25/26 lines and 7/8 branches. Python reproducer 63/66 lines, 19/22 branches; Node assessor 100% lines/branches/functions. The missing optional engine host-guard rejection branch is not manufactured into an engine-security claim. No E2E, production integration, formal architecture/security approval or readiness claim is made.

## Required decision

Keep BP05's canonicalizer prerequisite BLOCKED pending independent review and an explicitly approved maintained conforming selection. A possible upstream Titanium correction plus a conforming root-value API would require new pinned artifacts and full unchanged vector reproduction. A strict-parser/erdtman choice needs explicit maintenance and full API resolution. A polyglot composition would require a separate approved specification and all open compatibility/resource/security/operations gates. None is selected by this research packet.

The saved `execution-bindings.json` file provenance uses explicit `path` and `sha256` fields. This lossless representation retains every recorded artifact/output digest while avoiding API-named path assignments that secret scanners can mistake for API credentials; no scanner exclusions are required.
