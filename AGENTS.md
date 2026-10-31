# AI Coding Agent Instructions

- Source of truth: approved OpenSpec changes, public architecture guidance, and versioned contracts.
- Do not invent approval for architecture, security controls, or production-readiness gates.
- Maintain hexagonal dependency direction and logical database ownership.
- Use Java LTS and compatible pinned maintained library versions.
- Add tests with every change; minimum 75% automated source coverage plus E2E happy paths where relevant.
- Treat all uploaded content as untrusted. Never weaken tenant checks for local development.
- Never commit credentials, private repositories, employer/customer data, or internal architecture documents.
- Always open PRs for review; do not automatically merge.
