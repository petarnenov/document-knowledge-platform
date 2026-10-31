# BP03 public dependency and tested image provenance

The source SBOM is a CycloneDX 1.6 **lock inventory**, including build, test,
platform/BOM and optional dependencies. It is not a runtime-only dependency graph,
a container OS inventory, a license attestation, or production release approval.
An empty license list means the generator supplied no license evidence.

## Reproduce the source artifact

Run from the repository root with the pinned Node toolchain and frozen pnpm install:

```sh
node tools/dependencies/bp03-sbom.mjs . build/sbom
node --test --experimental-test-coverage \
  --test-coverage-include=tools/dependencies/bp03-sbom.mjs \
  --test-coverage-lines=75 --test-coverage-branches=75 --test-coverage-functions=75 \
  tools/dependencies/bp03-sbom.test.mjs
```

`bp03-source.cdx.json` and `bp03-source-provenance.json` are public build artifacts.
The generator copies only Gradle dependency locks (including settings plugins),
the multi-document pnpm 12 lock and exact Python tooling requirements to a fresh
public staging directory. Docker receives only that read-only directory; it never
receives the checkout, `.git`, `local-data`, runtime environment files, or credentials.
Source symlinks and unsupported/unpinned lock entries fail closed.

Generation uses the already selected image
`ghcr.io/google/osv-scanner:v2.7.0@sha256:4f3b7e2c64b9d38a5febc5a7e4a3dfef1d1362f726dea7a2ef4762b46469fcd0`
with `scan source --no-resolve --all-packages --format cyclonedx-1-6 --recursive`.
`--all-packages` is mandatory: the default standard output contains only affected
packages and can be empty for a clean scan. Settings locks are staged under the
conventional `settings/gradle.lockfile` name for scanner discovery, while provenance
retains their original path and hash. Every unique package from every input must
match the generated component set exactly. Missing packages, invented packages,
duplicates, scanner failures and reported vulnerabilities prevent publication.

The artifact records every original input's SHA-256, generator source SHA-256,
scanner image identity and source scope. Stable sorting and absence of fabricated
clock values make identical inputs and scanner results byte reproducible. OSV
vulnerability data remains a live external input: this artifact is not an immutable
or exhaustive vulnerability attestation. A later newly reported vulnerability makes
generation fail. No dependency relationship or runtime reachability is invented.

The actual public BP03 snapshot scan found 770 unique components: 269 Maven,
500 npm (including platform-specific optional packages) and one Python package,
from 29 effective lock inputs. This includes the settings plugin lock that the
original recursive scanner's 28 extraction calls did not discover by filename.
The generator independently checks set parity against all 29 original files.

## Bounded tested image identity evidence

These identities describe the reviewed development runtime that completed the real
private RustFS, genuine standard ClamAV and two-theme/four-format HTTPS journeys.
A local image ID is an observed build result, not a registry-published reproducible
image digest or a signature/provenance attestation. Later fresh per-run builds can
have different IDs; these observations do not approve a different run's readiness.

| Component | Identity / public source |
| --- | --- |
| RustFS 1.0.4 registry index | `sha256:ed9210d6411d77ffceb70d94b964a3cb7d225983368bc25bebe01e995293dcc2` |
| ClamAV 1.5.4 registry index | `sha256:bda39705e291105049c3936e2803ac90b955172af8030f94f05dce1252df6c6d` |
| Worker build JDK 25.0.5_8 index | `sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507` |
| Worker / namespace JRE 25.0.5_8 index | `sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0` |
| Observed `dkp-bp03-structure-inspector:local` image ID | `sha256:ed8d257f7ca13ae32ff0ebac16dcbacfac87e2441d7203e9c5834df999eab616` |
| Observed `dkp-bp03-inspector-network:local` image ID | `sha256:b4fe3f6c53a8e1697b854897334a832eaebfa51f82b21cda8a0899cbe38f53c1` |
| Inspector profile | `b2700939fc4b890650e053f3cd20b8407bbdf992d1de2082d4b8a6f29f51f5af` |
| Sorted Java path-NUL-source-bytes-NUL SHA-256 | `0f23a107d26fc189114de07ea8e2851eafa3666f83d9b3e8da26fd0e0ab7a164` |

Public build sources are `infra/docker-compose/inspector.Dockerfile`,
`inspector-network.Dockerfile`, `inspector-network.sh` and `compose-upload.yaml`.
Root-maintained local verification logs are `/tmp/dkp-125-real-inspector.log`
(actual production TLS adapter: all four formats and wrong key/profile/CA),
`/tmp/dkp-131-private-matrix.log` (private native IAM/anonymous matrix), and
`/tmp/dkp-98-real-https-negative-matrix.log` (two themes, four exact-byte uploads,
ranges, consumed receipt, gzip, genuine EICAR, malformed PDF, tenant isolation,
current explicit denial and logout). These local paths are review evidence locators;
the logs are not embedded into the public source SBOM. Runtime state and private
execution records must never be uploaded as artifacts.

## CI publication

The contract/dependency CI job runs the Node coverage command above and generation
following frozen dependency installation and before successful artifact publication.
Independent review and actual CI remain required before integration. Upload only `build/sbom/bp03-source.cdx.json` and
`build/sbom/bp03-source-provenance.json` using the workflow's pinned artifact action.
A failed scan/parity check must fail the job, not publish an empty fallback. Keep
existing vulnerability, secret, real-provider, worker and HTTPS gates independent.
The workflow publishes only these two generated public artifacts and fails if they
are missing. Full container inventory/signing
and release provenance require separately scoped image analysis and release gates.
