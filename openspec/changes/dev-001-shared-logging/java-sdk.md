# Normal Java producer SDK — #457

Implement the existing v1 producer protocol as a small replaceable outbound
adapter. This detail adds no wire fields, authorities, dependencies, or optional
hardening gates. The common writer and physical observer are already running;
keep them running while implementing and testing the SDK.

- Add `modules:observability-java-sdk`, package
  `org.documentknowledge.loggingclient`, separate from the JDK-only metadata
  package. It may depend on `observability-support`, the existing pinned JSON
  library and JDK HTTP. It must not import a service layer, runtime container,
  registrar, reader, or observer implementation. Permit service adapters and
  bootstrap to consume it; retain positive and negative dependency checks.
- Bootstrap supplies the original `BuildIdentity`/`ExecutionIdentity`, installed
  vocabulary and already-issued scope credential. Manifest installation and
  producer registration remain operator/server responsibilities. Never send or
  retain registrar/observer/reader credentials in this client.
- A typed event API accepts metadata and reviewed vocabulary identifiers with
  bounded numeric/boolean lifecycle details. Validate and sanitize before
  enqueue. No arbitrary message, attribute map, SQL/request body, exception
  message or tenant/actor authority input. Additional typed diagnostic fields
  follow the same existing contract during producer wiring.
- `emit` performs no HTTP/disk I/O and never waits for delivery. One owned
  background dispatcher drains at most2048 events/8MiB, with at most32 events
  per raw32768-byte batch and8192 bytes per event. Overflow/rejection and closed
  client state are explicit; never recursively log logging failures.
- Use only authenticated fixed `/batches` and `/receipts` routes, verified HTTPS,
  finite connection/whole-operation deadlines and bounded response bytes. No
  redirects, implicit credentials, arbitrary response allocation, or generated
  client's nullable default timeout. Preserve original event IDs and scope on
  replay; a request failure has UNKNOWN persistence, not proof of loss.
- Publish bounded delivery counters/state: pending, unknown, committed,
  rejected/dropped and expired. Only exact valid scoped HTTP200 commit receipts
  establish COMMITTED; socket success/202 never does. Normal retries and later
  durable spool/replay retain the original identity and reviewed finite bounds.
  The first working in-memory vertical is partial SDK delivery, not completion
  of spool/restart recovery or all #457 acceptance.
- Verify typed sanitation, encoded byte/batch/queue bounds, duplicate IDs,
  nonblocking emission during delayed/unavailable HTTP, honest receipts,
  normal recovery and bounded close; >=75% changed line/branch coverage in the
  pinned Java25 Linux environment. Prove a real SDK→API→PostgreSQL→query vertical.
  Reuse existing auth/tenant/redaction checks. #458/#459 and further exceptional
  complexity remain OPEN/P3 and do not pre-gate this normal working vertical.

Independent exact-revision review and development selection precede SDK runtime
edits. Full #457 remains OPEN through all SDKs/producers, normal shared-store E2E,
reviewed PR, exact-head green CI, dev integration and post-dev verification.
