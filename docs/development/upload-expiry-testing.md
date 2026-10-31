# Upload expiry test profiles

Owner-approved execution split: [#178](https://github.com/petarnenov/document-knowledge-platform/issues/178), DKP-004 / BP-03.

## Routine regression

From the repository root, with the project's pinned Java environment:

```sh
node tools/runtime/upload-expiry.mjs fast
```

This runs the Document Management application tests, including the existing
`UploadLifecycleTest` injected-clock expiry, fixed-deadline retry, receipt and
cleanup scenarios. It does not launch Docker or the real-time browser expiry
profile. Advancing the test clock avoids a one-hour wait; this command does not
set a five-minute production lifetime or guarantee a fixed build duration.
Existing full CI and coverage gates still apply.

The application regressions exercise the original admission one microsecond
before, exactly at and one microsecond after its deadline. Delayed transient
retries and late verification must preserve that exact `expiresAt`; consumed
receipts retain only their authorized original replay and version discovery.
Expiry with an active hold preserves both copies, evidence and full charges.
An unsettled DELETE remains charged despite observed absence and service
recreation; the separate known-terminal cleanup control releases only each
confirmed copy and releases admission after the last copy. These are isolated
injected-clock application tests, not physical-provider or real-time evidence.

## Final real-time acceptance

Prepare and verify a dedicated owned BP-03 runtime using the existing runtime
procedure. Use its identity directory and parent runtime environment file:

```sh
node tools/runtime/upload-expiry.mjs acceptance \
  --security-directory /absolute/path/to/owned-runtime/identity \
  --runtime-env-file /absolute/path/to/owned-runtime/runtime.env
```

This selects the existing strict HTTPS `expiry` browser profile. It waits the
genuine sixty-minute lifetime and remains a required final BP-03 acceptance
gate. It does not provision, restart or stop the provider stack. Do not run
provider faults, quota saturation or other mutations against that same fixture
while the expiry profile owns it. Independent source work and tests may run
concurrently when they cannot disturb its runtime or resources.

Record fast regression and real-time acceptance results separately. Fast test
success cannot be reported as evidence that the long scenario passed. No
production TTL, contract, authorization, acceptance or coverage gate changes.

The selector regression tests are:

```sh
node --test tools/runtime/upload-expiry.test.mjs
```
