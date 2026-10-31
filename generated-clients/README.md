# Generated operational SDKs

Java and TypeScript sources are generated from the shared OpenAPI 3.1 operational probe contract with OpenAPI Generator 7.26.0. Do not edit generated `src` directories manually. Run `pnpm contracts:generate`, then `pnpm contracts:check`; commit contract and generated source changes together. Hand-maintained compilation configuration remains outside generated source directories.

These clients are not used as domain dependencies. Each deployed service has its own configured base URL; the default localhost URL is a development convenience. Non-2xx responses, including unavailable readiness, are surfaced by the generated client error handling.

The Java generator is downloaded directly from Maven Central by `tools/contracts/generator.mjs`, pinned to SHA-256 `1760050094997b9cc790cc1350be095f15da8c08a996b184d3eadc4ccda5e35e`. The acquisition matched the official Maven artifact's published SHA-1; the stronger locally computed SHA-256 is the execution gate. Every cached/downloaded jar is checked before Java runs. The cache is outside the repository under `$XDG_CACHE_HOME/dkp-tools/openapi-generator` or `~/.cache/dkp-tools/openapi-generator`. A checksum mismatch stops execution; remove the corrupt cache entry and retry only after inspecting its provenance. No npm generator wrapper is required.
