# Foundation version policy

Verified against official distribution/registry metadata on 2026-10-31. Exact transitive dependency resolution belongs in the Gradle and pnpm lockfiles; direct Java versions belong in `gradle/libs.versions.toml`, JavaScript versions in workspace manifests. Updates require reviewed compatibility evidence, regenerated locks and passing gates. No floating versions or unverified container tags.

| Selection | Pin | Authority / compatibility |
|---|---|---|
| Java LTS | Temurin 25.0.5+8 | [Adoptium API](https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=mac&vendor=eclipse); local archive SHA-256 `32cd4d2151c91aa00d0fc911edfd05245ed40fa6b1aa894d5f35c928cd67a59d` |
| Gradle | 9.8.2 | [Distribution metadata](https://services.gradle.org/versions/current), [Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html); Java 25 supported from 9.1 |
| Micronaut platform | 5.2.4 | [Maven metadata](https://repo.maven.apache.org/maven2/io/micronaut/platform/micronaut-platform/maven-metadata.xml); [Micronaut 5 baseline](https://micronaut.io/2026/05/20/micronaut-framework-5-0-0-released/) is Java 25 |
| Node LTS | 24.22.1 | [Official archive](https://nodejs.org/en/download/archive/v24.22.1); build and test runtime |
| pnpm | 12.16.1 | [Official package metadata](https://registry.npmjs.org/pnpm/12.16.1); frozen lock installation |
| OpenSpec | 1.16.0 | [Official project](https://github.com/Fission-AI/OpenSpec), [package metadata](https://registry.npmjs.org/@fission-ai/openspec/1.16.0); official CLI initialization and strict validation |
| PostgreSQL selection (CID-01) | 18.6 | [Supported release table](https://www.postgresql.org/support/versioning/); PostgreSQL 19 is still RC, so excluded |
| Neo4j Community selection (CID-01) | 5.26.32 LTS | [Release notes](https://neo4j.com/release-notes/database/), [LTS direction](https://neo4j.com/blog/developer/neo4j-v5-lts-evolution/); latest patch of the maintained LTS channel |

PostgreSQL and Neo4j are selected for later adapter integration, not installed by this foundation. Neo4j 5.26 runs in its own Java 17/21 runtime per [server requirements](https://neo4j.com/docs/operations-manual/current/installation/requirements/); the Java 25 API runtime does not imply that the database must use Java 25. Driver compatibility, production security and actual database behavior remain unverified until adapter integration. The foundation demonstrates that no data-store runtime is needed for its process probes; it does not claim persistence compatibility tests have passed.

Container references are digest-pinned in `infra/docker-compose/*.Dockerfile`, resolved with `docker buildx imagetools inspect`: Temurin 25.0.5_8 JDK/JRE, Node 24.22.1 bookworm-slim and unprivileged Nginx 1.30.2 alpine. Build them independently and inspect running users with the documented smoke gate. No model/processing images are selected prematurely.

Frontend, validation and test-tool exact versions and any compatibility-driven selection are recorded in manifests/locks. A newer package release is not automatically compatible with the lint/test stack; satisfy documented peer ranges and actual build/type/test gates before adoption.

## Foundation dependency remediation (2026-10-31)

The initial pinned dependency scan found [basic-ftp GHSA-c475-qrg2-pj4r](https://github.com/advisories/GHSA-c475-qrg2-pj4r), [glob GHSA-3g3m-2fjr-6pw4](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4) and [minimatch GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249). The latest npm OpenAPI generator wrapper still brought affected FTP/proxy and glob dependencies; replacing this wrapper with the checksum-verified, pinned Java generator removes those unused paths entirely.

There is no patched minimatch 3.x or 10.x release for the reported advisory. Four exact parent-specific overrides select minimatch 11.0.1 for ESLint, its config-array dependency, TypeScript ESTree and Spectral core. ESLint/ESTree already consume the named API; actual lint is a compatibility gate. Spectral 1.24.0 used the older callable default API, so its one matching helper has a versioned pnpm patch adapting that import and invocation to the named API. `pnpm dependencies:test` verifies positive and negative basename/recursive source matching; contract valid/invalid fixtures remain a separate integration gate. This is not a blanket cross-major override. Reassess/remove overrides and the patch when maintained parent releases adopt fixed dependencies.
