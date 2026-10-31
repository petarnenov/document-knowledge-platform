# AI Coding Agent Instructions

- Source of truth: approved OpenSpec changes, public architecture guidance, and versioned contracts.
- Do not invent approval for architecture, security controls, or production-readiness gates.
- Maintain hexagonal dependency direction and logical database ownership.
- Use Java LTS and compatible pinned maintained library versions.
- Add tests with every change; minimum 75% automated source coverage plus E2E happy paths where relevant.
- Treat all uploaded content as untrusted. Never weaken tenant checks for local development.
- Never commit credentials, private repositories, employer/customer data, or internal architecture documents.
- Always open PRs for review. The latest owner instruction authorizes automatic merging into dev for all developed tasks, including specifications and implementation, after independent review and passing applicable checks. Automatic specification approval remains authorized under the same gates. This does not grant architecture, security-control or production-release sign-off.
- The active delivery goal is the fully implemented project, not an individual package. Follow docs/implementation/queue.md and continue to the next ready package after verification/integration without asking for renewed approval. Do not mark the full goal complete at a package boundary. Use this project's implementation protocol; do not apply GeoWealth-specific workflows.
