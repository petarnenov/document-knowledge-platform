# Infrastructure

Planned: Docker Compose for local dependencies; GitOps-managed Kubernetes for production. All images and model artifacts must use verified immutable digests. No production settings, credentials or service accounts are shipped in this starter.

DKP-001 implements only the four-component [foundation Compose](docker-compose/compose.yaml): three service process scaffolds and a static portal. See the root README for startup, loopback ports and the runtime smoke gate. Independent service images use `service.Dockerfile` with `--build-arg SERVICE=<service-slug>`; the portal uses `portal.Dockerfile`. Both build from repository root with pinned base digests and non-root runtime users. No storage, broker, workflow or model runtime is required for the foundation.

[Kubernetes](kubernetes/README.md) and [GitOps](gitops/README.md) contain future implementation boundaries, not deployable production configurations.
