# Versioned contracts

`openapi/v1/operational.yaml` is the shared OpenAPI 3.1 operational contract for Document Management, Knowledge Base and Knowledge Search. Each service exposes the same probes at its own base URL. The anonymous response contains only status, never tenant/configuration data.

`asyncapi/v1`, `workflows/v1` and `plugins/v1` explicitly contain no implemented contracts. No business routes or events are introduced by this foundation.

Run `pnpm contracts:check` to validate every published OpenAPI/AsyncAPI contract, test valid and malformed fixtures, regenerate and compare SDKs, and compile the generated Java/TypeScript clients. `pnpm contracts:generate` deliberately replaces only generated source directories; review its diff with the contract change. Java compilation uses the repository Gradle build and Java 25 toolchain. Synthetic validator fixtures live under `tools/contracts/fixtures`, outside the published contract tree.

The generator is pinned to OpenAPI Generator 7.26.0 ([official stable release](https://github.com/OpenAPITools/openapi-generator/releases/tag/v7.26.0)); the Java generator jar is downloaded from Maven Central and verified against its pinned SHA-256 on every invocation (see `tools/contracts/generator.mjs`). npm validation tooling is pinned and locked at the repository root. Generated headers retain upstream provenance; they do not grant a license to this repository.
