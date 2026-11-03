# Original compilation manifest builder — #457

Implement the existing `producer-protocol.md` pre-manifest identity requirement
as a small standard-library Python build adapter in
`tools/runtime/log-build-manifest.py`, with tests in the existing pinned Linux
Python runtime profile. Preserve `application-provenance.py` and its original
application-image evidence: it implements a different algorithm and cannot be
renamed into `dkp-public-inputs-v1`. This detail adds no public HTTP route,
credential authority, signing policy or production approval.

## Inputs and original identity

- Mint a nonnil UUID buildId before compilation. Retain the supplied component,
  Git HEAD and dirty state separately from the source fingerprint; runtime runId
  is separate and never minted by this builder. Two identical compilations get
  different buildIds while retaining the same sourceFingerprint.
- Implement the exact normative `dkp-public-inputs-v1` algorithm: enumerate all
  tracked public inputs with `git ls-files --stage -z`; reject unresolved stages,
  symlinks, unsafe/non-UTF-8 relative paths and nonregular files. Include dirty
  staged/working bytes, modes, locks, public docs and tracked generated SDK SOURCE.
  Never use a guessed component input subset or a verifier's different snapshot
  fingerprint as a substitute.
- Rows are exactly `{path,sha256,bytes,executable}`, sorted by UTF-8 path bytes.
  Hash UTF-8 RFC8785 canonical JSON of those rows. Worktree location and private
  local-data are not input fields. Hash file content in bounded chunks; reject
  changed file identity/size/mode/content during preparation rather than seal a
  mixed snapshot. A final unchanged-input comparison is also required.
- Reject nonignored untracked source/configuration before enumeration except
  explicitly declared generated build outputs. Stage intended new source first.
  The existing component `build/` and `dist/` outputs and the exact generated
  build-info output path are outputs, never inferred source exclusions. Reject a
  TRACKED generated runtime build-info/manifest output instead of silently
  excluding arbitrary tracked paths. Do not include node_modules/.git/private
  local-data; reject accidentally tracked private/output inputs instead of silently
  omitting them. Do not omit existing tracked generated SDK source.

## Preparation, manifest, and receipt

Preparation captures the original identity/input inventory before compilation.
Finalization verifies that inventory again and uses the same original buildId,
component, HEAD, dirty flag and source fingerprint. The generated manifest has
exactly the already selected v1 fields, strict canonical JSON and <=1MiB bytes.
No manifest bytes/hash or final artifact receipt feeds into its own inputs.

The trusted build runner supplies its actual bounded toolchain/dependency
records and reviewed vocabulary; the adapter hashes the canonical records and
vocabulary rather than inventing fingerprint constants. Preserve those records
as build evidence. Toolchain/dependency/compiled symbol capture and
Gradle/Vite/image embedding must be wired to real commands before actual
application provenance is reported. A unit-test record proves the builder only.
First lifecycle-only wiring can use a reviewed catalog with no exception/frame
entries; it cannot claim full exception/source instrumentation. Never derive
catalogs from caught exception messages, console objects or event input.

Finalize the manifest before embedding it in the JAR/image/portal artifact.
After packaging, emit a separate receipt with the raw manifest SHA-256 and
finished artifact SHA-256/image digest, tied to the preparation identity. These
are output identities, not manifest/source inputs. Refuse a changed preparation,
manifest or source when sealing a receipt. The builder does not automatically
install/register a manifest or emit a log event. Use the existing private
manifest operator and service authentication when later wiring producers.

Keep the first adapter small and reusable by trusted build runners. Initialization
and build commands may do bounded filesystem work; runtime/business `emit`
remains free of HTTP/disk waits. Write generated output only to explicitly
selected ignored build output paths, with no source mutation or credential data.

## Verification and delivery boundary

Test exact deterministic rows/canonical bytes and digest against known vectors;
independent worktree paths; identical HEAD/source with distinct compilation IDs;
staged/unstaged content, mode and lock changes; clean/dirty HEAD claims; reject
symlinks/unmerged index/non-UTF8 or unsafe paths/untracked source; generated-output
self-reference rejection; source mutation before finalize/receipt; manifest
vocabulary/hash/size validation; original identity preserved through a test
artifact and external receipt. Enforce >=75% source line and branch coverage in
the existing pinned Python Linux profile and preserve meaningful RED/GREEN.

Independent exact-revision detail review and standing owner development selection
precede implementation. Review source/evidence before local commit. Actual
Gradle/Vite/image adoption, original two-build FE/BE/infra shared-store proof,
Java durable restart recovery, JS/TS Beacon+Node and Python SDKs remain OPEN until
implemented and verified. #458/#459 exceptional matrices remain deferred/P3.
The full #457 PR/exact-head CI/dev/post-dev gates and BP00–BP17/v0.5 goal remain.
