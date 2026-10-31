# DKP-004 development specification approval

Approved on 2026-10-31 under the owner's standing automatic specification authorization after independent security89 and architecture90 final PASS and root strict/contract checks. Parent35; specification88; research86; stage decision87; cleanup/hold decision100. This is development implementation approval only, not production deployment, CID07/CID15, legal-policy or architecture/security-control sign-off.

## Accepted decisions and resolved findings

Actual Codex decision95531 preserves verified-upload completion without version creation, then explicit append201 with atomic one-use receipt consumption. BP05 retains final atomic version/run/intent/outbox202 and legacy compatibility obligations. Exact-version content uses explicit content.review authority AND current ACL; minimal command receipts and existing append SDK names/DTO/success remain intact. Bounded actor-owned consumed-receipt discovery enables the real browser journey.

Actual Codex decision18639 resolves cleanup/hold concurrency with durable DM document deletion fencing, one-shot exact-version dispatch and atomic hold placement coordination. A new hold returns existing503 without mutation while a deletion remains executable or unknown. Authorized original201 replay remains intact. Both reviewers explicitly accepted potentially indefinite new-hold503/full charges as a disclosed development availability tradeoff. Settlement requires bound terminal response, no possible further execution and authoritative absence before freeing charges; timeout/lease/restart/absent HEAD is insufficient. Normal known-outcome cleanup/recovery remain in scope.

Copy retries require settled prior writer and exact cleanup before another possible original; no uncharged/SDK retry copies. Frozen public ACL/catalog Action adds only content.review, with existing schemas and hold/ACL wire behavior preserved. Reviewed candidate RustFS/ClamAV images and Java libraries require actual release compatibility/provenance/security tests during implementation, not assumed success from manifests.

## Scope and remaining gates

Independent authors/reviewers are distinct. Root strict10/10, configured Redocly zero warnings, three strict JSON schemas,13 negative syntax fixtures,12 common-schema comparisons and22 DM/BFF parity checks passed; see verification.md. Runtime issues91–99 were created before work and remain unstarted at approval. No runtime source/dependency change is part of this approval. All required real PG/RustFS/standard definitions/sandbox/fault/HTTPS/coverage evidence, independent implementation review, reviewed dev PR and passing integration checks remain mandatory.

## Reviewed artifact fingerprint

SHA-256 of this sorted manifest: `e0facfe24f840857a3988b7bd19fddcf3a53cb763e8819b96d32ecdb44a966af`.

```text
61a97b8a3b9e47105164e4a1b676831b5c6785d975d8aa9a4cb07294135d3ff5  docs/architecture/system-design.md
001785b9002ada4d79d0e8e3e4152f953303d39f14f85d9b6cd080a7e107f0f2  docs/implementation/queue.md
db7a9611141bb7cb5b067086dae7807bf438c8efabd12fed8941922477059ff9  openspec/changes/dkp-004-rustfs-upload/contracts/content-and-limits.md
55879b5fcf97e0bb609d7908d3246013793df9216742ccd5b2f9ff5be3fe69bc  openspec/changes/dkp-004-rustfs-upload/contracts/grant-catalog.schema.json
63f9e0c8df0eb852b8539424672a9fd3f157be799caada50461748e15c8a1c97  openspec/changes/dkp-004-rustfs-upload/contracts/inspection-result.schema.json
3cd7d00ba2e1ade85d15438f129a738455d0eee265c269f8b6cb1ca355010a7e  openspec/changes/dkp-004-rustfs-upload/contracts/rustfs-upload.openapi.json
376b64fa9a9fa6613ffd39e2d8e60d8f45ae92b06769cd02cb0c3ec4048f966f  openspec/changes/dkp-004-rustfs-upload/contracts/upload-persistence.md
418db85043df5cc9e43d801c6cbd1923bb2c1f5f639c1d3bc61b7b4c5e812630  openspec/changes/dkp-004-rustfs-upload/contracts/verified-receipt.schema.json
c894158b620d31fc2c3015bd0451ec7dc76608e34205503c9093629207c63df1  openspec/changes/dkp-004-rustfs-upload/design.md
f76db47939d420eb1f0ecc011a90926b2147083b6b84f76a2c7209bda1faf0a8  openspec/changes/dkp-004-rustfs-upload/proposal.md
e7ac00a310f9fc5943725e647553588d15f632f76cfd5e6da518752e590d4bba  openspec/changes/dkp-004-rustfs-upload/specs/browser-bff/spec.md
389fcdc3bda858ec263792d6c0e607f4d9e6d5a2e3163deca88449aa54285db7  openspec/changes/dkp-004-rustfs-upload/specs/content-gateway/spec.md
a7a9063afa49638ca160e62f88e4c4294235c208a5150a5dbcd149fa31593dbc  openspec/changes/dkp-004-rustfs-upload/specs/dm-persistence/spec.md
5eed3f300788d0320c7a5a6b9022e6cde9d86575e9934780d797c2837b756222  openspec/changes/dkp-004-rustfs-upload/specs/document-authority/spec.md
8d869b94d0aa58cbd7f10197db27cea6ecea3f491dddace60614ceff4849b94b  openspec/changes/dkp-004-rustfs-upload/specs/document-management/spec.md
4a62d6100912354a760c0600995c68800001c5e998f61d9564ed2a597e7475a4  openspec/changes/dkp-004-rustfs-upload/specs/local-runtime/spec.md
696d066783b9a8e66dcca9710c259dfd916ba73d9c8c2edfba45c5cfef80f1ce  openspec/changes/dkp-004-rustfs-upload/specs/portal-shell/spec.md
05e23b9f933a665a64f799681a61897b570a6985c9a474993f9c7d39398661f2  openspec/changes/dkp-004-rustfs-upload/specs/upload-security/spec.md
41aa22c5379e10a0e97707e2b979f879c519508211950d19023da7b9772dff2a  openspec/changes/dkp-004-rustfs-upload/tasks.md
656fb1acea675edf09c18d606316da0d6e7c037e978aa7f86476e7616a087639  openspec/changes/dkp-004-rustfs-upload/verification.md
```

This is the approval checkpoint fingerprint. Task execution, evidence and administrative status evolve; normative behavior changes require proportionate independent re-review. Root architecture sequencing and full-project queue are included in the reviewed scope. The whole-project goal stays active beyond BP03.
