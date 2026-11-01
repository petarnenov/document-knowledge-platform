# Isolated UI/UX manual preview — #440

This runtime is separate from foundation `18443/18444`, BP-03 `19443/19444` and BP-04 `20443/20444`. It publishes only `https://localhost:24443` and its real Keycloak issuer `https://idp.localhost:24444/realms/dkp`. It uses the current worktree's actual frontend, BFF, DM and PostgreSQL; no mock identity, persistence or upload authority is introduced. Root coordinates startup after active acceptance workloads release build capacity.

## Supported behavior and limits

The seven-service foundation supports the shell, both themes, real OIDC sign-in/out, selecting a synthetic tenant, metadata-only draft creation/list/detail/update, and currently authorized access-rule/legal-hold operations. The portal must retain the current server checks on every action. A hold has no automatic expiry; retention remains unresolved and destruction forbidden.

RustFS, malware scanning and structural inspection are **not provisioned** here. DM upload authority is explicitly disabled. Upload attempts remain unavailable; this runtime provides no upload, immutable-version, processing, rendered-preview, review, publication, search, collection, graph, purge or audit acceptance evidence. The foundation nginx JSON ingress limit remains **4 KiB**, including metadata serialization overhead; a very long description may receive413. This preview does not change that contract or any deadline/security control.

A successful start proves owned containers are healthy, not successful user journeys. `verify --browser` runs the actual existing real-identity/draft suite with strict HTTPS. Local checks, published-source CI, integration and production acceptance are separate states.

## Ownership and startup

The runner requires an explicit unique project matching `dkp-uiux-440-` followed by twelve lowercase hexadecimal digits. Its directory must be exactly `local-data/security/uiux-preview/<that-project>` in this worktree. There is no fallback project or target. Choose a fresh project, record it in the issue's private local handoff, and use that same pair for every operation. Replace the example suffix before execution if it has ever been used.

From the owning worktree, with pinned Node24.22.1, Docker Compose supporting `!override`, and Java25/OpenSSL/keytool available:

```sh
node tools/runtime/uiux-preview.mjs start \
  --project dkp-uiux-440-0123456789ab \
  --directory local-data/security/uiux-preview/dkp-uiux-440-0123456789ab

node tools/runtime/uiux-preview.mjs verify \
  --project dkp-uiux-440-0123456789ab \
  --directory local-data/security/uiux-preview/dkp-uiux-440-0123456789ab

node tools/runtime/uiux-preview.mjs verify --browser \
  --project dkp-uiux-440-0123456789ab \
  --directory local-data/security/uiux-preview/dkp-uiux-440-0123456789ab
```

Do not execute these commands until the integration owner coordinates available ports, source freeze and runtime capacity. Startup refuses existing directories/resources, remote Docker endpoints, symlink paths or any other project's material. It invokes the existing private bootstrap with exactly24443/24444, validates the resulting material, then uses only the base composition and `compose-uiux-preview.yaml` with the explicit `-p` project. Credentials are newly generated; they are never copied from another stack. The runner suppresses raw subprocess output and never prints credentials or resolved Compose configuration.

The overlay preserves the base hardening and private database network, removes host API probe bindings, remaps every identity/trust/catalog/certificate mount and OIDC origin/callback, and labels its containers/networks/volume. PostgreSQL's volume is project scoped; no external volume or network is adopted. `nginx-uiux-preview.conf` changes the base edge's port/origin only and retains its limits and logging protection.

Startup stores owner-only `preview-receipt.json` with source/material hashes, project/directory, actual container/image/network identities and the database volume. Build outputs/dependencies and private material are excluded from the source hash. Runtime/source/material changes invalidate verification. A failed startup retains a `prepared` receipt and private material for diagnosis; the runner does not erase state, retry a different stack, loosen checks or perform automatic cleanup.

The real-browser command builds a project-specific test-runner image and uses only the recorded preview portal/provider container IDs and fresh public CA. It calls the existing `runtimeArguments` helper with explicit24443/24444 and uploads disabled. Public CA trust exists only in the disposable test runner. Browser secrets enter its environment without being placed in command arguments; raw Docker/HTTP diagnostics are never printed. Resource identity is rechecked after the suite.

## Manual browser access

Use a **dedicated browser profile**, not a profile simultaneously signed into another localhost runtime. Cookies are host scoped, not port scoped: the BFF's `__Host-DKP-SESSION` cookie on localhost and the provider's cookies on idp.localhost can overwrite another stack's browser session despite separate containers and ports.

The fresh CA is not installed into global host trust. Import only this runtime's public `ca.crt` into a dedicated profile's certificate authority store where the browser supports profile-scoped trust (for example, a separate Firefox profile). Keep hostname and HTTPS verification enabled. The file is at the owned directory above; never expose `ca.key`, realm imports, truststore passwords or `runtime.env`. Manual access may require the profile's explicit CA setup before both HTTPS origins open.

The generated synthetic account name is `dkp-tester`. Obtain its generated password locally from the owner-only runtime file using the person's authorized local secret-handling flow; never put it in chat, issue bodies, logs, screenshots or commits. A test credential is not a production identity or permission grant.

## Verification and retirement

The legacy `smoke.py`, `identity-provider-check.py`, `identity-e2e.mjs` CLI and upload orchestration select other fixed ports/projects. **Do not run them directly for this preview.** Use this runner's explicit project/path and optional browser verification. Do not run database outage, upload fault or expiry tests against a manual runtime.

Record source hash, exact project/directory, public URL, verification state and supported scenarios in #440. Keep ownership receipts and credential-bearing files local and private. The runner offers no teardown command. If retirement is needed, first independently verify the receipt and exact owned resource labels, then coordinate a task-scoped stop using the identical project/env/base/overlay tuple. Preserve the database volume and generated credentials together; never use `down -v`, prune, regenerate material, or stop/restart another project's resources as recovery.

Unit/YAML checks (no Docker invocation):

```sh
node --experimental-test-coverage \
  --test-coverage-include='tools/runtime/uiux-preview.mjs' \
  --test-coverage-lines=75 --test-coverage-branches=75 --test-coverage-functions=75 \
  --test tools/runtime/uiux-preview.test.mjs
```
