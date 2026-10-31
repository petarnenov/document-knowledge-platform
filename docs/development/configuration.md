# Environment configuration

DKP-001 has no business state, identity provider, data store or tenant access. Only minimal process probes are public. Database, object-store, broker, workflow, parser, model, OIDC and workload-identity integration require future reviewed changes with fail-closed negative tests. A caller-supplied tenant header has no authority.

Local service defaults are supplied in each bootstrap's versioned application configuration. Tests start isolated embedded servers on random ports and control readiness in process; there is no HTTP toggle. Local Compose publishes only loopback ports and runs without credentials. No development setting bypasses authentication or resource authorization.

Environment-specific nonsecret settings are injected at runtime using Micronaut environment/property conventions. Never package `.env`, secret values or deployment endpoints in a jar or frontend bundle. Future adapters must fail startup or fail closed if required trust/configuration is missing; no stub may return an allow decision. Secrets belong in the environment's external secret mechanism, with independent credentials and migrations per logical domain database.

Production configuration is intentionally absent. Kubernetes/GitOps structure is planning guidance; production identity, mTLS, residency, backups, operational ownership and release authorization remain explicit gates. Automatic specification approval and merging apply to development integration, not deployment.
