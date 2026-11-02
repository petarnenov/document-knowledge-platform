# Shared development logging runtime — #457

The dedicated read-runtime is a partial implementation of #457. It provides authenticated queries over a persistent logging-only PostgreSQL database. Producer registration/ingestion, the continuous authenticated volume observer, local Mantine viewer, frontend Beacon and all-component/CI delivery remain open. An HTTP200 query is not proof that any producer emitted/committed logs. The common query API requires a dedicated read credential; anonymous local viewing is a separate pending server-side viewer.

Use Python3.14 and the local Docker Unix-socket context. Do not run as root. From the repository:

```sh
python3 tools/runtime/dev-logging.py start
python3 tools/runtime/dev-logging.py status
```

The default private state is `local-data/security/dev-logging/shared`, project `dkp-dev-logging`, gateway URL `https://localhost:18443`, authenticated query URL `https://localhost:18443/api/v1/dev-logs/events`, and retained IdP authority `https://idp.localhost:18444`. Only Nginx publishes host ports, both on loopback; the query service and DB have no host ports. TLS clients must trust the explicit private development CA at `gateway-pki/ca.crt`; do not disable certificate verification. The gateway serves built portal assets. Business BFF/IdP integration remains pending; absent upstreams return gateway failures and are not evidence of functional login or business readiness. API queries require explicit UTC `from`/`to` and support the versioned filters; the operator status command verifies a real authenticated query without printing the private token. The future local viewer uses its token server-side. Never paste private files or raw Docker inspection/SQL output into public issues.

Repeated start verifies a live identical instance without replacing its container/volume/credentials. The first start builds the public source-bound images and provisions the owned DB. It selects two unused private /28 subnets from 10.241.0.0/16 after checking Docker IPAM and active host/VPN IPv4 routes; those exact ranges persist in owner state. Unavailable route inventory or overlapping/exhausted ranges fail closed. No foreign network is removed or adopted. Linux operators require the `ip` command; macOS uses `netstat`. Unknown ownership/schema, changed config, missing/replaced owned storage or unsafe mounts fail closed. `status` is read-only, including after a partial failed start. No action implicitly stops the project or removes its volumes. It is separate from foundation/upload/test projects and must remain running across build/task/harness shutdown.

Controlled update:

```sh
python3 tools/runtime/dev-logging.py update --target query
# For a reviewed gateway-only update:
python3 tools/runtime/dev-logging.py update --target gateway
# For a reviewed update to both:
python3 tools/runtime/dev-logging.py update --target both
```

Run only after the changed source/configuration passes the applicable checks and independent review. The explicit target replaces only the selected query/gateway container(s), preserving the other component, PostgreSQL container and both named volumes. Each image has its own source fingerprint. Never use test cleanup, global prune, `down -v`, a new owner state directory or manual volume recreation to repair this shared service. Diagnose failures and retain the owner state. Explicit owner-requested stops/maintenance are separate operations; this tool exposes no default teardown command. Docker engine availability is required. `unless-stopped` recovers containers after crashes/engine restart; it cannot run while the machine/engine is off.

Dedicated read grants have explicit30-day expiry at initial creation/renewal. Restarting does not extend authority. Before expiry (or to recover an expired read grant), explicitly renew the existing token/reader identity and update the query process:

```sh
python3 tools/runtime/dev-logging.py renew-readers
```

No implicit token rotation. Deliberate credential rotation requires a separately reviewed operational change. The private `operator/read-token` is never mounted into the query process; that process receives only SHA256 grants, its read-only DB password and cursor key. The separate admin secret is provisioned into a narrowly owned read-only PostgreSQL secret volume; it never enters the query environment/mounts/image/CLI arguments. Private files are owner-only. The fixed named data volume and volume identity record survive every normal query update/restart. First startup checks the selected free-space predicate; continuous observer/ingestion admission remains a separate required implementation.

Explicit real disposable acceptance (creates its own uniquely named project; missing configuration fails):

```sh
DKP_LOG_ACCEPTANCE_DIRECTORY=/absolute/private/new-acceptance-directory python3 -m unittest discover -s tools/runtime -p accept_dev_logging.py
```

The acceptance test owns and removes only its unique acceptance containers/networks/volumes/images; it never tears down `dkp-dev-logging`. It uses a synthetic owner-seeded row to verify the query/storage lifecycle. It does not claim authenticated ingestion, cross-build FE/BE/infra delivery, or completed #457/CI/dev integration. Keep acceptance evidence outside public tracked source.
