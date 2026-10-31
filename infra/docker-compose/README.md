# Local identity and DM runtime

BP-02 runs seven independent components: DM, KB, Search, browser BFF, HTTPS portal, Keycloak and the used DM PostgreSQL database. It does not run an object store; the owner selected RustFS for BP-03. This is an isolated development composition, not production security or availability acceptance.

Run from this worktree's repository root with the pinned Java 25 toolchain:

```sh
python3 infra/docker-compose/bootstrap-security.py
docker compose --env-file local-data/security/runtime.env -f infra/docker-compose/compose.yaml config --quiet
docker compose --env-file local-data/security/runtime.env -f infra/docker-compose/compose.yaml up --build --wait --wait-timeout 240
python3 tools/runtime/smoke.py
python3 tools/runtime/identity-provider-check.py
python3 tools/runtime/document-database-check.py
node tools/runtime/identity-e2e.mjs
python3 tools/runtime/document-database-check.py --exercise-outage
```

The last check deliberately stops and restarts only the local DM database after the real browser draft journey. It verifies readiness becomes503 while liveness remains200, then checks recovery, scoped roles and a digest of persisted document state. Do not run it against a shared environment or during another task's runtime acceptance. It restores the database in `finally`; failure remains a failed gate.

Portal is https://localhost:18443 and the IdP is https://idp.localhost:18444. Public API test ports18081–18083 bind loopback and independently require verified bearer/tenant context. PostgreSQL has **no published port** and shares a private internal network with DM only. Local SQL transport is unencrypted inside that isolated network, with SCRAM credentials; no production transport/mTLS claim is made. Production transport/workload identity remains BP-16/CID-06.

Bootstrap creates new synthetic credentials only under this worktree's ignored `local-data/security/`; never copy credentials from another worktree or print `runtime.env`/resolved Compose configuration. It preserves complete existing material and rejects incomplete/unsafe material rather than replacing it. It does not alter global certificate trust. The realm import has an explicit synthetic user UUID; generated grants use that exact subject and real-provider verification checks the issued subject and persisted administrator view. Tenant membership alone never grants an operation.

`authority/` is0550 with `grants.json` and a base64-encoded32-byte `cursor.key`0400, mounted read-only in DM. Catalog entries explicitly enumerate development actions and delegation ceilings for the synthetic actor's two tenants; there is no generic administrator/owner grant. Catalog changes require a controlled administrator replacement preserving safe permissions. Cursor key replacement invalidates prior cursors. Never commit generated keys, passwords, realm imports or operational catalogs.

The pinned PostgreSQL18.6 image runs as999:999, drops all capabilities, uses a read-only root filesystem, private tmpfs/socket and a persistent named volume. `fsync`, `full_page_writes` and `synchronous_commit` are on. Initial SQL creates separate NOINHERIT/non-superuser migration/runtime roles, revokes PUBLIC database/schema access and prevents runtime access to the administrative database. Migration credentials bind only the separately qualified startup Flyway boundary; request repositories receive the DML-only runtime datasource. The local process still has startup configuration: stronger production secret/process isolation is not claimed. Immutable version/audit tables prohibit runtime UPDATE/DELETE, and Flyway history is inaccessible to runtime.

Keep the generated DB credentials and named volume together. Recreating a container preserves data; `down` preserves the volume. Do not delete the volume or regenerate credentials as an automatic recovery step. Password rotation, audit retention and production backup/recovery require their still-open owner gates. Development retention remains unresolved, with null deadline and destruction forbidden.

CI runs unit/branch coverage, real PostgreSQL CAS/role/restart tests, hardened-container smoke, real-provider account-mutation/subject checks and real HTTPS browser metadata journeys. A fake artifact authority is test-only; public version append remains503 until BP-03 verifies a RustFS receipt.
