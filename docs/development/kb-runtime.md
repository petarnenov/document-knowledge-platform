# Isolated KB development runtime

This BP04 profile adds the `knowledge_base` logical database and `kb` schema,
current KB file authority and an authenticated, metadata-only DM dependency.
It uses a separate Compose project (`dkp-bp04`) and ports 20443/20444/20081/20082.
The existing BP03 runtime and its authority are outside this profile.

The source is prepared for integrated DM sharing, KB bootstrap and portal
implementations. Source/unit/configuration checks do not establish that those
components or the full cross-domain acceptance scenarios have passed. Issue192
remains open until genuine integration, timing, restart and authority-race checks
pass. Production, CID07 and CID15 approval are separate gates.

## Private preparation

Use JDK25, OpenSSL, Python3 and modern Docker Compose supporting `!override`.
From the repository root, choose a new ignored child directory:

```sh
python3 tools/runtime/kb-security.py prepare --directory local-data/security/kb-runtime-new
python3 tools/runtime/kb-security.py verify --directory local-data/security/kb-runtime-new
```

Preparation generates fresh synthetic Keycloak identity, distinct database
passwords, an independent KB catalog with explicit delegation ceilings and
synthetic firm/resource-scope relations, and a canonical base64 cursor key with
32 random bytes. Its issuer and browser callback are fixed to the new ports.
The subject and tenant-a/tenant-b membership come from the synthetic realm
fixture. DM authority adds the separately approved content-review and sharing
actions. No development tenant checks or identity validation are bypassed.

A separate DM-transport CA signs a certificate for `dm-sharing`. Only the proxy
receives its server key. KB receives its public CA plus its own catalog/cursor
key; APIs never receive CA signing keys or the full preparation directory.
Private files remain ignored, owner-only and recorded by hash. Repeated prepare
validates a complete unchanged directory; partial, changed, expired or unsafe
material is rejected and preserved. It does not rotate or adopt authority.
Never publish `runtime.env`, `state.json`, generated files or expanded Compose
configuration. `config --quiet` validates without displaying secret values.

## Build and startup after component review

The owner coordinates startup after the exclusive BP03 acceptance run ends and
DM189/KB190/portal191 are ready. These commands create no BP03 resources:

```sh
docker compose --env-file local-data/security/kb-runtime-new/runtime.env -f infra/docker-compose/compose.yaml -f infra/docker-compose/compose-kb.yaml config --quiet
docker compose --env-file local-data/security/kb-runtime-new/runtime.env -f infra/docker-compose/compose.yaml -f infra/docker-compose/compose-kb.yaml build
docker compose --env-file local-data/security/kb-runtime-new/runtime.env -f infra/docker-compose/compose.yaml -f infra/docker-compose/compose-kb.yaml up -d --no-build
```

KB PostgreSQL uses the reviewed immutable PostgreSQL18.6 image pin. It has its
own data volume, internal network and provisioning administrator. Only its
one-shot migrator gets migration credentials. That job uses the existing pinned
Flyway/PostgreSQL libraries and the fixed KB migrations; KB starts only after
successful completion. The request process receives only `dkp_kb_runtime`.
Runtime cannot create schemas/tables, read Flyway history, connect to sibling
databases or rewrite immutable policy/audit/command facts. Durability explicitly
requires `fsync`, `synchronous_commit` and `full_page_writes` on.

The PostgreSQL health probe checks provisioning and durability; it does not
pretend migrations already ran. After migration, the bundled
`/usr/local/share/dkp-kb-database-check.sql` checks all13 owned business tables,
role restrictions and immutable-table privileges. KB readiness separately checks
its actual runtime connection/schema, current whole catalog, current private
cursor key and the actual DM dependency. Liveness remains independent.

## DM transport and boundaries

KB and DM share no network. Their BFF, identity and database networks are
separate. The private TLS proxy joins only the two sharing networks and exposes
no host port. It forwards exactly the two approved sharing POST paths and the
existing operational readiness GET. Other paths/methods and query strings are
rejected. Sharing calls preserve the original verified bearer and selected
tenant; cookies, asserted actor headers and arbitrary request headers are not
forwarded. Operational probes carry no bearer, tenant or body.

The proxy bounds POST input to16KiB and uses finite in-memory buffers without
disk spooling or upstream retries. Native Nginx timeouts are inactivity bounds,
not a whole-operation deadline or a hard response-body-size limit. The KB
adapter enforces32KiB sharing responses,2KiB readiness responses, verified CA and
hostname, no redirects, and complete-body deadlines: initial sharing<=2s,
final sharing<=1.5s within the same2s final phase, readiness<=500ms. DM owns its
inclusive1.5s server budget. KB's whole8s and its BFF9s budget remain outside
these phases; edge read inactivity is10s. Unknown commits require durable
same-command reconciliation, never an assumption that cancellation rolled back.

## Verification and rollback

Run the pure preparation and actual Compose-merge security tests with:

```sh
python3 -m unittest discover -s tools/runtime -p 'test_kb_*.py'
```

The genuine runtime gate must still exercise current catalog/ACL/classification
changes, complete-set and authorized-subset behavior,100-member limits and
pagination, ACTIVE/no-op/replay, rollback/UNKNOWN outcomes, admission/deadlines,
actual process/database restart durability and accessible browser journeys.
Mocked/unit fixtures do not replace these scenarios. Dependency outages must
make readiness503 while liveness stays200, followed by current-state recovery.

For rollback, stop only this reviewed project with its same files/environment.
Keep its named PostgreSQL volume and private directory for reconciliation.
Do not use `down --volumes`, delete command facts or reuse this directory as a
fresh successful run. No command in the preparation helper starts, stops,
resets or faults any service.

## Owned lifecycle runner (issue227)

After the owner releases runtime execution and the component reviews pass, use
one fresh child directory for an entire run:

```sh
python3 tools/runtime/kb-runtime.py start --directory local-data/security/kb-run-new
python3 tools/runtime/kb-runtime.py verify --directory local-data/security/kb-run-new
python3 tools/runtime/kb-runtime.py e2e --directory local-data/security/kb-run-new
python3 tools/runtime/kb-runtime.py stop --directory local-data/security/kb-run-new
```

`start` refuses an existing directory or occupied profile ports. It records a
unique `dkp-bp04-<12hex>` project and a random run identity before mutation,
requires a clean committed tracked source tree before any Docker mutation,
prepares authority under `security/`, and builds from a separate public source
snapshot read directly from that Git commit. Untracked files are neither copied
nor hashed, including nonignored local sensitive files. Tracked index-versus-HEAD and working-tree-versus-index changes are checked
separately, so opposing staged/working edits cannot cancel; either causes refusal; the runner never stages or commits user files. The
root must first consolidate reviewed packets into a committed branch. The
recorded commit identity and public input hashes, exact built image IDs and exact labeled resource
IDs bind the run. The generated overlay adds ownership labels only. Private
command logs and lifecycle/browser records remain outside immutable authority;
all are owner-only. Runtime verification rejects changed sources, images,
authority, resource sets, failed migrations or unhealthy services. It executes
the actual thirteen-table, role and durability SQL check after migration.

The browser handoff uses the separately reviewed shared runner's explicit
`--kb-runtime` mode and a closed private `browser-state.json`. That record binds
the portal, Keycloak and default network IDs and images to this exact run. The
profile enables only `DKP_KB_E2E_ENABLED=true` and the real `knowledge.spec.ts`
journeys at strict HTTPS ports20443/20444. It requires the genuine CA and rejects
wrong hostnames; no HTTP or TLS bypass is a recovery path. The shared runner
extension must be integrated before this command can pass.

`stop` validates the exact recorded resource set and deletes only its containers
and networks. It preserves both database volumes, authority, source snapshot
and logs. Unknown/replaced resources cause refusal rather than adoption or
broader cleanup. Failed starts retain an explicit failed record and their owned
resources for diagnosis; a failed directory cannot become a fresh successful
run. Source drift can block verification while ownership-based stop remains
available.

These commands provide lifecycle and browser gates, not complete BP04
acceptance. Separate required evidence must still cover strict versus eligible
complete100-document sets; current catalog, ACL and classification races;
100-member pagination; ACTIVE/replay and unknown-commit reconciliation; bounded
admission, stalled dependency and final-phase cancellation; and actual
process/database restart and outage durability. No two-journey result substitutes
for those gates or grants production/security approval.
