# Infrastructure

The [local identity and DM runtime](docker-compose/README.md) implements only the dependencies used through BP-02: independently protected APIs, BFF/HTTPS portal, standalone Keycloak and a scoped persistent DM PostgreSQL database. Images are pinned by digest; runtime users are non-root and generated secrets remain ignored. RustFS is selected for BP-03 and is not started here.

[Kubernetes](kubernetes/README.md) and [GitOps](gitops/README.md) remain future implementation boundaries, not production deployment claims. Production security, workload mTLS, retention, backup/recovery and release acceptance remain explicitly gated.
