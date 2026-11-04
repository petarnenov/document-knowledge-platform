# Rank4 finite tooling inventory — proposed

Existing #457/#224. Every row below is PROPOSED_NOT_ADOPTED. Discovery binds tracked production Python, shell and Node files under tools/runtime, tools/ci, tools/contracts, tools/dependencies and infra/docker-compose. Manual classification corrects top-level Node programs without argv and libraries using process APIs. Tests retain their own test-run boundary; they are not service producers. No per-command implementation or acceptance is claimed.

Mechanisms: **P** explicit inner-stage diagnostic port plus external command boundary; **B** source-distribution/child command boundary through the existing Node SDK; **S** typed external infrastructure observations plus probe exit boundary; **L** pure library/caller port, no independent process identity; **O** logging-support internal bounded status plus external invocation observation (no recursive self-delivery); **T** test-driver invocation boundary. Each adopted row requires source-bound unit/negative coverage and an actual successful/failing invocation with common-store receipt/query proof. Inner provider stages additionally require real stage/cause E2E, not only an exit code.

| Tracked path | Class | Required mechanism | Verification family |
| --- | --- | --- | --- |
| `infra/docker-compose/bootstrap-security.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `infra/docker-compose/dm-postgres-probe.sh` | ENTRYPOINT | S | owned infra readiness/transition E2E |
| `infra/docker-compose/inspector-network.sh` | ENTRYPOINT | S | owned infra readiness/transition E2E |
| `infra/docker-compose/keycloak-probe.sh` | ENTRYPOINT | S | owned infra readiness/transition E2E |
| `infra/docker-compose/probe.sh` | ENTRYPOINT | S | owned infra readiness/transition E2E |
| `tools/ci/bp03-aggregate-check.sh` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/ci/bp03-aggregate-linux.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/ci/bp03-aggregate-selftest.sh` | ENTRYPOINT | T | pinned test-driver result and cleanup |
| `tools/ci/java-dm-check.sh` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/ci/java-dm-linux.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/ci/python_runtime_inside.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/ci/test-java-dm-check.sh` | ENTRYPOINT | T | pinned test-driver result and cleanup |
| `tools/ci/verify.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/contracts/check.mjs` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/contracts/drift.mjs` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/contracts/generate.mjs` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/contracts/generator.mjs` | LIBRARY | L | caller unit/negative + dependency boundary |
| `tools/contracts/lib.mjs` | LIBRARY | L | caller unit/negative + dependency boundary |
| `tools/contracts/null-model.mjs` | LIBRARY | L | caller unit/negative + dependency boundary |
| `tools/contracts/sdk-tree.mjs` | LIBRARY | L | caller unit/negative + dependency boundary |
| `tools/dependencies/bp03-sbom.mjs` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/runtime/log-tool-build.py` | ENTRYPOINT | O | original pinned build/archive/owned compiler tests; delivery NOT_ADOPTED |
| `tools/runtime/log-tool-verify.py` | LIBRARY | L | closed original artifact/identity/content negative tests; runtime NOT_ADOPTED |
| `tools/runtime/log-tool-compile.py` | ENTRYPOINT | O | original pinned compiler/catalog tests plus later external invocation observation; NOT_ADOPTED |
| `tools/runtime/tool-diagnostics.py` | LIBRARY | L | pure original binding/event unit/negative coverage; transport NOT_ADOPTED |
| `tools/runtime/accept_dev_logging.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/application-provenance.py` | LIBRARY | L | caller unit/negative + dependency boundary |
| `tools/runtime/clam-definitions.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/dev-log-viewer.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/dev-logging.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/document-database-check.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/generic-temp-system.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/identity-e2e.mjs` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/runtime/identity-provider-check.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/log-build-manifest.py` | LIBRARY | L | caller unit/negative + dependency boundary |
| `tools/runtime/log-identity-peer.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-java-build.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-java-catalog.py` | LIBRARY | L | caller unit/negative + dependency boundary |
| `tools/runtime/log-java-install.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-native-bff.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-node-build.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-node-deliver.mjs` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-node-package.mjs` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-portal-adopt.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-portal-build.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-portal-image.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-portal-record.mjs` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-python-build.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-python-compile.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-python-deliver.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-volume-observer.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-worker-forward.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-worker-image.py` | ENTRYPOINT | O | logging runtime/helper + external actual observation |
| `tools/runtime/log-worker-records.py` | LIBRARY | L | caller unit/negative + dependency boundary |
| `tools/runtime/provider-job-control-acceptance.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/provider-job-probe.mjs` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/runtime/provider-jobs.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/rustfs-compat-harness.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/rustfs-runtime.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/smoke.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/synthetic-documents.mjs` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/runtime/upload-acceptance.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/upload-accounting-acceptance.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/upload-durability.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/upload-expiry.mjs` | ENTRYPOINT | B | Node/command checks + exact actual invocation |
| `tools/runtime/upload-faults.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/upload-runtime.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |
| `tools/runtime/verification-workspace-system.py` | ENTRYPOINT | P | pinned Python runtime/infra + stage/cause E2E |

The inventory also includes these finite invocation surfaces; expanded task/check IDs are registered vocabulary, never private command arguments:

| Surface | Required mechanism and acceptance |
| --- | --- |
| Root package scripts: `contracts:check`, `contracts:generate`, `contracts:drift`, `openspec:check`, `format:check`, `format`, `dependencies:test` | B; actual script/check start/result and registered failure diagnostics. |
| Portal package scripts: `dev`, `build`, `preview`, `lint`, `typecheck`, `test`, `test:coverage`, `test:e2e`, `test:e2e:identity` | B for tool/check boundaries; existing browser Beacon remains the separate original application producer. |
| Gradle wrapper and owned registered task invocations from build/verification tools and workflows | B through the original build adapter; actual task/build result, original compiled application manifest preserved, no fabricated compiler identity. |
| Foundation jobs: `linux-python-parity`, `java` with its four service matrix values, `architecture`, `portal`, `contracts-and-specification`, `containers`, `uploads`, `supply-chain` | CI offline collection plus actual import; exact run/attempt/job/matrix/check association. Inner Python/Node provider checks still require P/B adoption above. |
| BP03 aggregate job `aggregate`; repository hygiene job `basic` | Same CI collection/import, original result and cleanup retained. |
| Docker/Compose entrypoints: PostgreSQL, RustFS, ClamAV, inspector namespace, Nginx, identity provider, DM/KB/KS/BFF/portal, logger query/gateway/viewer and external volume observer | S for owned infrastructure state and operation outcomes; original app/worker/SDK request telemetry remains separate. No raw vendor log forwarding, guessed vendor buildId or changes to their isolation. |

Before runtime selection, replace any ambiguous row with its actual owner/caller and verify discovery against package/Gradle/workflow/Docker entrypoints. The implementation inventory gate rejects newly discovered unclassified production entrypoints. It reports missing adoption rather than automatically blessing these proposed rows. Future task additions cannot silently inherit an implementation claim.
