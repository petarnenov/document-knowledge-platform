# BP04 external deadline acceptance fixture — issue422

## Why

Approved BP04 requires cumulative final2s, final DM<=1.5s, local<=500ms, actor1/global16 and no SQL connection/transaction ownership across outbound HTTP. Issue368 and the source-bound354 audit identify missing deployed evidence. Component/fake-clock tests and a9s browser response cannot substitute. PostgreSQL idle state cannot distinguish an idle borrowed Hikari connection.

## What Changes

Propose a dedicated, source-attested development acceptance fixture using actual Keycloak OIDC, KB, DM and PostgreSQL. A private fixed TLS relay gates bounded genuine DM responses; seventeen synthetic identities and exact grants are sealed before startup. External lock barriers, full scoped fact hashes, original-key reconciliation and independent settlement distinguish known rollback from UNKNOWN dispatched COMMIT.

Research supported pinned Hikari7.1.0/Java25 local observation. A transient MXBean sample at a genuinely pending HTTP barrier is direct evidence of borrow state at that fixed point; a validated zero count can disprove retaining a connection across that wait. It is not a continuous borrow trace. Current deployed factory does not register the bean. No observation subprofile is selected or activated by this proposal; absence of sufficient direct deployed boundary evidence leaves the across-HTTP-wait borrow gate OPEN.

## Capabilities

### Modified Capabilities

- `local-runtime`: specify dedicated BP04 timing/admission acceptance ownership, evidence and unresolved pool-observation prerequisites.

## Impact

Only prospective specification, research and closed evidence schema change. No business API/SDK, runtime code, dependency, image, identity, JDBC setting, deadline or control is modified. Existing BP04 authority, cross-database race, logical ownership and failure semantics remain authoritative. Implementation requires independent review, applicable checks and recorded development technique approval under issue-first protocol. This proposal grants no formal architecture/security/CID/production approval and does not close368,354,36 or PR247.
