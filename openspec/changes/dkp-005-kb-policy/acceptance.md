# Authoritative acceptance preservation — #167

The following acceptance is copied verbatim from issue167; this draft does not check off implementation criteria.

- Preserve verbatim complete BP04 acceptance and applicable System Design collection/audience/membership/policy invariants, tenant/actor authority and logical KB database ownership/hexagonal boundaries.
- Define versioned APIs/contracts and semantic compatibility, audience revisions/visibility snapshots, current-authority checks and fail-closed inter-service boundaries without implementing later processing/publication readiness.
- Include a concrete meaningful test plan covering concurrency, negative tenant/ACL/catalog checks, audit/policy revision behavior, real persistence/E2E and >=75% source coverage for later implementation.
- Create/review an OpenSpec proposal/design/tasks/spec delta in a separate worktree from current origin/dev; record all unresolved architecture/security approval-only decisions honestly.
- Obtain independent architecture and security specification review and required OpenSpec/contract checks before recorded automatic specification approval. Open a reviewed PR; do not automatically merge under latest user-provided AGENTS.md.

Issue36 acceptance, verbatim:

- [ ] Create child issues before independently executable/delegated tasks; prepare and independently review the OpenSpec/design/contracts before new runtime changes.
- [ ] Implement the complete BP-04 acceptance from System Design without fabricating later-package readiness.
- [ ] Pass meaningful unit/integration/negative tests, at least75% automated source coverage and relevant real E2E/contract/architecture/security checks.
- [ ] Resolve independent implementation review; integrate reviewed PR(s) into dev only after required CI passes.
- [ ] Record completion/integration evidence, archive the approved change, close verified child issues and save a resumable /compact checkpoint.

System Design §14 BP-04 row, verbatim:

| BP-04 | KB collections, memberships, audience revision contracts | Cross-domain access + concurrent policy tests |

System Design §6.2, verbatim:

**KnowledgeCollection**: collection ID, owner scope, classification ceiling, audience rule, collection status, revision. **Membership**: membership ID, document ID, sharing scope, activation status, revision, author and change reason. **AudiencePolicy**: allowed principals/tenants/firms/resource scopes, explicit deny rules, revision and effective date. Membership can be many-to-many; collection membership must not grant privileges beyond document ACL and tenant entitlements.

System Design §2.2 selected DM-072, verbatim:

1. KB maintains a monotonically ordered **membership/policy revision per affected collection/audience scope**, exposed by an authenticated, versioned authorization/compatibility API.
2. A publication command identifies intended version, expected document aggregate revision, audience classification, and the set of evaluated membership/policy revision tokens.
3. DM validates classification compatibility against KB before committing the publication pointer, checks its own aggregate version and approval/readiness. A mismatch or unavailable KB **blocks** activation.
4. Because KB and DM are independently transactional databases, a concurrent KB change after the last read remains possible. The design does **not** falsely claim a globally serializable commit. KB changes must independently enforce collection/member/document audience policy and may not expand beyond DM document ACL; Search always evaluates both authoritative services at response boundary.
5. When a revision conflict becomes known, an event or reconciliation job marks the membership/view invalid, prevents forbidden content exposure, and initiates operator-visible remediation. A pre-existing published version remains unchanged until a permitted publication command succeeds.
6. Every search result must pass publication, tenant, classification, collection membership, document ACL, and evidence visibility checks, or be excluded. Authorization dependency failures are fail-closed; avoid use of stale positive authorization caches.

**Guarantee:** No unauthorized content is returned by conforming Search/Content APIs after an authoritative denial is observed at the response boundary. **Non-guarantee:** No cross-service atomicity; a narrow race between last check and HTTP transmission is accepted under DM-063, not eliminated by policy revision tokens. Independent KB membership mutation paths need the same safety rules.

System Design §7.2 authorization paragraph, verbatim:

Authorization requires **all** applicable assertions: authenticated actor; global/tenant entitlements; allowed collection scope; document ACL (explicit denies win); operation permission; current publication, classification, legal/retention visibility and content-evidence scope. For administrative draft review, use a distinct privileged action; do not blur with end-user `read` access. Do not expose result count, score, graph existence, citation or file URL derived solely from unauthorized evidence.

System Design §5 ownership rows, verbatim:

| Knowledge Base (KB) | Collections, audience policies, memberships, KB configuration, policy revisions | Collection operations, membership/sharing, compatibility/authorization checks | Direct writes to DM version or ACL |
| Knowledge Search | Search profiles, index manifests, reconciliation state, query/retrieval behavior | Hybrid search, graph exploration, citations | Declaring document or audience visibility authoritative |

Traceability: proposal/design + knowledge-base cover aggregate ownership and administration; knowledge-policy covers DM-072/current access; kb-persistence covers logical ownership and transaction evidence; verification.md maps each criterion to executable future tests. Publication/search guarantees are preserved as consumer obligations, not claimed implemented by BP-04.
