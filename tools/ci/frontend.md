# Shared pinned Linux frontend checker (#439)

Run `node tools/ci/frontend.mjs` from an isolated project worktree. An optional first argument selects a **new** private evidence directory; the default is a unique directory under ignored `local-data/frontend`. Local execution and the Foundation portal job call the same entry point and the same container checks. The host needs Docker and Node; it installs no global tools and modifies no shared runner settings. Coordinate resource availability before starting Docker alongside real-provider acceptance.

The immutable Node 24.22.1 and Playwright 1.64.1 image digests are reused from the repository's existing real-identity runner; pnpm is exactly 12.16.1. Chromium and OS dependencies come from that Playwright image. The runner builds for Linux amd64 so host architecture does not select a different environment. It verifies Linux, Node and pnpm before the gates.

The ordered checks preserve frozen-lock installation, formatting, lint, strict typecheck, production build, portal coverage, separate acceptance-helper coverage at 75% for lines/branches/functions/statements, and mock portal Playwright E2E. Browser installation on the host is replaced by the pinned browser image. The containers/uploads jobs retain separate real OIDC, DM and private-provider acceptance. Passing mocked portal E2E proves no real-provider integration.

The host stages only allowlisted public frontend build inputs, including working source edits. Local data, credentials, private key files, caches, node_modules, test reports and Git metadata are excluded; symlinks are rejected. Docker receives this temporary context rather than the repository root. No source, cache, credentials, Docker socket or evidence directory is host-mounted. Each invocation has unique image/container names, drops Linux capabilities, limits the test container to 4 GiB RAM, two CPUs and 768 processes, runs as UID 10001, and removes only its own container/image/context. The container uses disposable writable storage and network access for frozen dependency installation. There is no shared writable package cache.

Private evidence includes a SHA-256 fingerprint over sorted build-input names and bytes, the input manifest, environment versions, immutable built-image ID (also used to create the container), commands and exit statuses, gate logs and available coverage/Playwright reports. Host Docker command logs/results are also retained. The parent evidence directory is mode 0700 and metadata/log files 0600. Do not attach local reports to public issues without reviewing their content. CI uploads the same reports as a short-lived workflow artifact. Any failed gate stops the sequence and returns nonzero; source staging/build/copy/cleanup failures also fail. Existing evidence directories are refused to prevent overwriting earlier results.

Lightweight orchestration tests (no Docker launched):

```sh
node --experimental-test-coverage --test-coverage-lines=75 \
  --test-coverage-branches=75 --test-coverage-functions=75 \
  --test-coverage-include='tools/ci/frontend*.mjs' \
  --test-coverage-exclude='tools/ci/frontend*.test.mjs' \
  --test tools/ci/frontend.test.mjs
```

These tests verify gate order, helper coverage preservation, failure propagation, private-context selection, symlink rejection, report collection and cleanup ownership. They supplement an actual successful pinned-Linux run; they do not replace it.

The frontend context includes exactly `docs/prototypes/DKP_Interactive_Design_v0.5.html` as its public rendered visual reference; other documentation remains excluded. The portal Playwright gate also runs paired reference/React visual comparisons with the same pinned browser and viewport. These comparisons preserve their bounded assertions and attached deviations; they do not turn mock browser coverage into real-provider or complete pixel-fidelity acceptance.
